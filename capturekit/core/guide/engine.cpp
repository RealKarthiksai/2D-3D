#include "capturekit/guide/engine.hpp"

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
