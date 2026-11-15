#include "capturekit/guide/engine.hpp"

#include <algorithm>
#include <cmath>

#include "capturekit/util/json.hpp"

namespace capturekit::guide {

GuideEngine::GuideEngine(const GuideParams& p)
    : p_(p), cov_(p), nav_(p), planner_(p), gen_(p) {}

void GuideEngine::on_intrinsics(const bundle::IntrinsicsSample& k) {
  K_.width = k.width, K_.height = k.height, K_.fx = k.fx, K_.fy = k.fy, K_.cx = k.cx, K_.cy = k.cy;
  have_K_ = k.width > 0 && k.fx > 0;
}

void GuideEngine::on_pose(const bundle::PoseSample& s) {
  auto T = geometry::PoseWC::from_tq(s.t_world_from_camera, s.q_world_from_camera_wxyz);
  if (!T.translation().allFinite()) return;
  const int st = int(s.tracking_state);
  if (st == 3 && tracking_ != 3) lost_since_ = s.t_ns;
  tracking_ = st == 0 ? 1 : st;
  if (have_pose_ && s.t_ns <= last_pose_t_) return;  // strictly increasing only
  try {
    track_.push(s.t_ns, T);
  } catch (const std::invalid_argument&) {
    return;
  }
  Vec3 pos = T.translation();
  double yaw = yaw_of(T), pitch = pitch_of(T);
  if (have_pose_) {
    double dt = double(s.t_ns - last_pose_t_) / 1e9;
    if (dt > 0 && dt < 1.0) {
      double a = std::min(1.0, dt / 0.3);
      double v = std::hypot(pos.x() - user_.pos.x(), pos.z() - user_.pos.z()) / dt;
      double w = wrap_pi(yaw - user_.yaw) / dt;
      user_.speed += a * (v - user_.speed);
      user_.yaw_rate += a * (w - user_.yaw_rate);
    }
  }
  user_.pos = pos, user_.yaw = yaw, user_.pitch = pitch;
  have_pose_ = true;
  last_pose_t_ = s.t_ns;
  log_.start(s.t_ns);
  if (tracking_ == 1) {
    map_.add_camera(pos);
    if (std::hypot(pos.x() - last_trav_.x(), pos.z() - last_trav_.z()) > 0.1) {
      nav_.mark_traversed(pos), last_trav_ = pos;
    }
    double h = pos.y() - map_.floor_y();
    if (h > 0.5 && h < 2.5) cam_height_ += 0.02 * (h - cam_height_);
  }
}

void GuideEngine::refresh_levels() {
  if (map_.version() == map_version_) return;
  map_version_ = map_.version();
  nav_.set_floor_y(map_.floor_y());
  cov_.sync(map_);
}

void GuideEngine::on_plane(const bundle::PlaneRecord& r) {
  map_.add_plane(r);
  refresh_levels();
}

void GuideEngine::on_points(const bundle::PointCloudRecord& r) {
  points_seen_ = true;
  for (size_t i = 0; i + 2 < r.xyz.size(); i += 3) {
    Vec3 q(r.xyz[i], r.xyz[i + 1], r.xyz[i + 2]);
    nav_.add_point(q);
    if (have_pose_ && tracking_ == 1) nav_.carve(user_.pos, q);  // the sight ray to a seen point is free space
  }
}

void GuideEngine::on_frame(const FrameObs& f) {
  blur_streak_ = (f.hard_reject && f.motion_blur) ? blur_streak_ + 1 : 0;
  if (f.underexposed) last_underexp_t_ = f.t_ns, underexp_seen_ = true;
}

void GuideEngine::on_keyframe(uint32_t, int64_t t, double sharp) {
  if (!have_K_ || track_.size() == 0) return;
  auto T = track_.interpolate_at(t, 100'000'000);
  if (!T) return;
  std::vector<Vec3> floor_seen;
  Obs o{*T, K_, t, sharp, true};
  cov_.observe(o, &nav_, &floor_seen);
  for (auto& p : floor_seen) nav_.mark_open(p);
}

void GuideEngine::update(int64_t t, std::vector<Instruction>& out) {
  if (!have_pose_ || !have_K_) return;
  const int64_t period = int64_t(1e9 / std::max(0.05, p_.replan_hz * rate_scale_));
  if (last_plan_t_ < 0 || t - last_plan_t_ >= period) {
    last_plan_t_ = t;
    int before = planner_.switches();
    PlanHints hints;
    hints.need_ceiling = !map_.has_ceiling() && map_.surface_count() >= 2;
    hints.need_floor = !map_.has_floor() && map_.surface_count() >= 2;
    plan_ = planner_.replan(t, user_, cov_, nav_, K_, cam_height_, hints);
    if (planner_.switches() != before) log_.note_target_switch();
    log_.note_plan(plan_.target.valid);
    // Safety invariant: a WALK target is never an unknown or blocked cell. A discovery look (tilt where the
    // user already stands) asks for no movement, so it is not subject to it.
    if (plan_.target.valid && plan_.target_changed && !plan_.discover) {
      int ix, iz;
      nav_.to_cell({plan_.target.x, 0, plan_.target.z}, ix, iz);
      if (!nav_.passable(ix, iz)) log_.note_unsafe_target();
    }
    auto ev = cov_.eval_view(pose_from_yaw_pitch({user_.pos.x(), user_.pos.y(), user_.pos.z()}, user_.yaw, user_.pitch), K_, &nav_, false);
    too_close_ = ev.too_close;
  }
  if (last_sample_t_ < 0 || t - last_sample_t_ >= 1'000'000'000) {
    last_sample_t_ = t;
    auto st = cov_.stats();
    log_.sample(t, st.completeness(), st.seen_fraction());
  }
  Situation s;
  s.t_ns = t;
  s.user = user_;
  s.tracking_state = tracking_;
  s.lost_since_ns = lost_since_;
  s.blur_streak = blur_streak_;
  s.underexposed = underexp_seen_ && t - last_underexp_t_ < 2'000'000'000;
  s.plan = plan_;
  s.too_close = too_close_;
  s.completeness = plan_.completeness;
  scratch_.clear();
  life_.clear();
  gen_.update(s, scratch_, life_);
  for (auto& e : life_) {
    log_.ended(e);
    // A movement/turn the user never completed means the target is unreachable for them: do not insist.
    if (e.state == LifeState::Expired && (e.ins.type == InstrType::MoveTo || e.ins.type == InstrType::RotateToward)) {
      planner_.penalize_current();
      plan_.target = planner_.current();
      last_plan_t_ = -1;  // re-plan immediately
    }
  }
  for (auto& i : scratch_) {
    log_.issued(i, s.completeness);
    out.push_back(i);
  }
}

std::string GuideEngine::report_json() const {
  auto st = cov_.stats();
  json::Object o;
  o.set("params_hash", p_.hash()).set("tier", std::string(1, tier()));
  json::Object c;
  c.set("completeness", std::round(st.completeness() * 1e4) / 1e4).set("seen", std::round(st.seen_fraction() * 1e4) / 1e4)
      .set("valid_cells", uint64_t(st.valid)).set("planes", uint64_t(map_.surface_count()));
  o.raw("coverage", c.dump());
  json::Object n;
  n.set("obstacle_cells", uint64_t(nav_.obstacle_cells())).set("free_cells", uint64_t(nav_.free_cells())).set("passable_cells", uint64_t(nav_.passable_cells())).set("floor_y", std::round(map_.floor_y() * 1e3) / 1e3);
  o.raw("nav", n.dump());
  json::Object pl;
  pl.set("target_x", std::round(planner_.current().x * 100) / 100).set("target_z", std::round(planner_.current().z * 100) / 100).set("target_gain", std::round(planner_.current().gain * 10) / 10).set("switches", planner_.switches()).set("targets_reached", planner_.reached_count());
  o.raw("planner", pl.dump()).raw("log", log_.summary_json());
  return o.dump();
}

}  // namespace capturekit::guide

namespace capturekit::guide {

OverlayModel GuideEngine::overlay(int64_t t) const {
  OverlayModel m;
  m.t_ns = t;
  auto st = cov_.stats();
  m.completeness = st.completeness();
  m.seen = st.seen_fraction();
  m.tracking = tracking_;
  m.mode = plan_.mode == Mode::Finish ? "finish" : plan_.mode == Mode::Target ? "target" : "sweep";
  m.floor_found = map_.has_floor();
  m.ceiling_found = map_.has_ceiling();
  m.user_x = user_.pos.x(), m.user_z = user_.pos.z(), m.user_yaw_deg = rad2deg(user_.yaw);
  m.has_target = plan_.mode == Mode::Target && plan_.target.valid;
  if (m.has_target) m.target_x = plan_.target.x, m.target_z = plan_.target.z;
  for (auto& [id, pl] : map_.planes()) {
    if (pl.kind != "wall") continue;
    ++m.walls;
    Vec3 d = pl.n.cross(Vec3::UnitY());  // horizontal direction along the wall
    d.y() = 0;
    if (d.norm() < 1e-6) continue;
    d.normalize();
    double half = 0.5 * (std::fabs(pl.u.dot(d)) * pl.eu + std::fabs(pl.v.dot(d)) * pl.ev);
    if (m.wall_list.size() >= OverlayModel::kMaxWalls) continue;  // no per-wall stats for walls that are not kept
    OverlayWall w;
    w.x0 = pl.c.x() - d.x() * half, w.z0 = pl.c.z() - d.z() * half;
    w.x1 = pl.c.x() + d.x() * half, w.z1 = pl.c.z() + d.z() * half;
    if (!std::isfinite(w.x0 + w.z0 + w.x1 + w.z1)) continue;      // never emit NaN/inf coordinates
    const double sf = cov_.stats(id).seen_fraction();
    w.seen_pct = std::isfinite(sf) ? int(std::lround(std::clamp(100.0 * sf, 0.0, 100.0))) : 0;
    m.wall_list.push_back(w);
  }
  m.wall_found = m.walls > 0;

  // The one most useful reason right now, chosen in priority order.
  const char* why = "why.keep";
  const bool dark = underexp_seen_ && t - last_underexp_t_ < 2'000'000'000;
  if (tracking_ == 3) {
    why = "why.tracking_lost";
  } else if (tracking_ == 2) {
    why = "why.limited";
  } else if (blur_streak_ >= int(p_.blur_frames)) {
    why = "why.too_fast";
  } else if (dark) {
    why = "why.too_dark";
  } else if (plan_.mode == Mode::Finish) {
    why = "why.finish";
  } else if (plan_.mode == Mode::Sweep) {
    why = "why.sweep";
  } else if (plan_.discover && plan_.target.valid && plan_.target.pitch > 0 && !map_.has_ceiling()) {
    why = "why.ceiling";
  } else if (plan_.discover && plan_.target.valid && plan_.target.pitch <= 0 && !map_.has_floor()) {
    why = "why.floor";
  } else {
    const OverlayWall* worst = nullptr;
    for (auto& w : m.wall_list)
      if (w.seen_pct < 80 && (!worst || w.seen_pct < worst->seen_pct)) worst = &w;
    if (worst) {
      double rel = wrap_pi(std::atan2(0.5 * (worst->x0 + worst->x1) - user_.pos.x(), 0.5 * (worst->z0 + worst->z1) - user_.pos.z()) - user_.yaw);  // + = left
      double a = std::fabs(rel);
      why = a < deg2rad(45) ? "why.wall_ahead" : a > deg2rad(135) ? "why.wall_behind" : rel > 0 ? "why.wall_left" : "why.wall_right";
    } else if (!map_.has_ceiling() && map_.surface_count() >= 2) {
      why = "why.ceiling";
    } else if (!map_.has_floor() && map_.surface_count() >= 2) {
      why = "why.floor";
    }
  }
  m.why_id = why;
  m.why_text = why_line(why);
  return m;
}

}  // namespace capturekit::guide
