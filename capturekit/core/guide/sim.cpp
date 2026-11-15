#include "capturekit/guide/sim.hpp"

#include <algorithm>
#include <cmath>
#include <deque>
#include <map>

#include "capturekit/guide/coverage.hpp"
#include "capturekit/guide/engine.hpp"
#include "capturekit/quality/quality.hpp"
#include "capturekit/util/json.hpp"

namespace capturekit::guide::sim {

Room make_room(const std::string& name) {
  Room r;
  r.name = name;
  if (name == "L") {
    r.poly = {{0, 0}, {6, 0}, {6, 3}, {3, 3}, {3, 5}, {0, 5}};
    r.rects = {{0, 0, 6, 3}, {0, 3, 3, 5}};
    r.start_x = 1.5, r.start_z = 1.5;
  } else {
    r.poly = {{0, 0}, {5, 0}, {5, 4}, {0, 4}};
    r.rects = {{0, 0, 5, 4}};
    r.start_x = 2.5, r.start_z = 2.0;
    if (name == "furniture") {
      r.boxes = {{0.3, 0.0, 2.1, 0.9, 0.8}, {2.3, 1.6, 3.5, 2.4, 0.75}, {4.4, 0.3, 5.0, 2.3, 2.0}};
      r.start_x = 1.0, r.start_z = 2.8;
    }
  }
  return r;
}

namespace {

struct Surf {
  std::string kind;
  Vec3 o, u, v, n;
  double su = 0, sv = 0;
  int nu = 0, nv = 0;
  std::vector<CellStat> cells;
  std::vector<uint8_t> valid;
  // plane emission state: extent of seen points in (u,v)
  double umin = 1e9, umax = -1e9, vmin = 1e9, vmax = -1e9;
  int npts = 0;
  Vec3 center(int i, int j, double c) const { return o + u * ((i + 0.5) * c) + v * ((j + 0.5) * c); }
};

struct Face {
  Vec3 o, u, v, n;
  double su, sv;
};

bool in_poly(const std::vector<std::pair<double, double>>& poly, double x, double z) {
  bool in = false;
  for (size_t i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
    auto [xi, zi] = poly[i];
    auto [xj, zj] = poly[j];
    if (((zi > z) != (zj > z)) && (x < (xj - xi) * (z - zi) / (zj - zi) + xi)) in = !in;
  }
  return in;
}

double dist_seg(double px, double pz, double ax, double az, double bx, double bz) {
  double dx = bx - ax, dz = bz - az, l2 = dx * dx + dz * dz;
  double t = l2 > 0 ? std::clamp(((px - ax) * dx + (pz - az) * dz) / l2, 0.0, 1.0) : 0.0;
  return std::hypot(px - (ax + t * dx), pz - (az + t * dz));
}

/// 2D segment intersection parameter along (a->b) with segment (c->d); returns t in [0,1] or -1.
double seg_hit(double ax, double az, double bx, double bz, double cx, double cz, double dx, double dz) {
  double rx = bx - ax, rz = bz - az, sx = dx - cx, sz = dz - cz;
  double den = rx * sz - rz * sx;
  if (std::fabs(den) < 1e-12) return -1;
  double t = ((cx - ax) * sz - (cz - az) * sx) / den, u = ((cx - ax) * rz - (cz - az) * rx) / den;
  return (t >= 0 && t <= 1 && u >= 0 && u <= 1) ? t : -1;
}

bool seg_box(const Vec3& a, const Vec3& b, const Box& bx) {
  Vec3 d = b - a;
  double t0 = 0, t1 = 1;
  const double lo[3] = {bx.x0, 0.0, bx.z0}, hi[3] = {bx.x1, bx.h, bx.z1};
  for (int k = 0; k < 3; ++k) {
    if (std::fabs(d[k]) < 1e-12) {
      if (a[k] < lo[k] || a[k] > hi[k]) return false;
    } else {
      double ta = (lo[k] - a[k]) / d[k], tb = (hi[k] - a[k]) / d[k];
      if (ta > tb) std::swap(ta, tb);
      t0 = std::max(t0, ta), t1 = std::min(t1, tb);
      if (t0 > t1) return false;
    }
  }
  return true;
}

struct World {
  Room room;
  double cell;
  std::vector<Surf> surfs;   // coverage targets: walls, floor, ceiling
  std::vector<Face> faces;   // point sources only (furniture sides)
  std::vector<double> weights;

  World(const Room& r, double c) : room(r), cell(c) {
    const auto& P = r.poly;
    for (size_t i = 0; i < P.size(); ++i) {
      auto [x0, z0] = P[i];
      auto [x1, z1] = P[(i + 1) % P.size()];
      double L = std::hypot(x1 - x0, z1 - z0);
      Surf s;
      s.kind = "wall";
      s.o = {x0, 0, z0};
      s.u = {(x1 - x0) / L, 0, (z1 - z0) / L};
      s.v = {0, 1, 0};
      s.n = {-(z1 - z0) / L, 0, (x1 - x0) / L};  // inward for a CCW polygon
      s.su = L, s.sv = r.height;
      add(std::move(s));
    }
    for (auto& rc : r.rects) {
      for (int ceil = 0; ceil < 2; ++ceil) {
        Surf s;
        s.kind = ceil ? "ceiling" : "floor";
        s.o = {rc[0], ceil ? r.height : 0.0, rc[1]};
        s.u = {1, 0, 0};
        s.v = {0, 0, 1};
        s.n = {0, ceil ? -1.0 : 1.0, 0};
        s.su = rc[2] - rc[0], s.sv = rc[3] - rc[1];
        add(std::move(s));
      }
    }
    for (auto& b : r.boxes) {
      faces.push_back({{b.x0, 0, b.z0}, {0, 0, 1}, {0, 1, 0}, {-1, 0, 0}, b.z1 - b.z0, b.h});
      faces.push_back({{b.x1, 0, b.z0}, {0, 0, 1}, {0, 1, 0}, {1, 0, 0}, b.z1 - b.z0, b.h});
      faces.push_back({{b.x0, 0, b.z0}, {1, 0, 0}, {0, 1, 0}, {0, 0, -1}, b.x1 - b.x0, b.h});
      faces.push_back({{b.x0, 0, b.z1}, {1, 0, 0}, {0, 1, 0}, {0, 0, 1}, b.x1 - b.x0, b.h});
      faces.push_back({{b.x0, b.h, b.z0}, {1, 0, 0}, {0, 0, 1}, {0, 1, 0}, b.x1 - b.x0, b.z1 - b.z0});
    }
    for (auto& s : surfs) weights.push_back(s.su * s.sv);
    for (auto& f : faces) weights.push_back(f.su * f.sv);
  }

  void add(Surf s) {
    s.nu = std::max(1, int(std::ceil(s.su / cell)));
    s.nv = std::max(1, int(std::ceil(s.sv / cell)));
    s.cells.assign(size_t(s.nu) * size_t(s.nv), {});
    s.valid.assign(s.cells.size(), 1);
    if (s.kind == "floor")
      for (int j = 0; j < s.nv; ++j)
        for (int i = 0; i < s.nu; ++i) {
          Vec3 c = s.center(i, j, cell);
          for (auto& b : room.boxes)
            if (c.x() >= b.x0 && c.x() <= b.x1 && c.z() >= b.z0 && c.z() <= b.z1) s.valid[size_t(j) * size_t(s.nu) + size_t(i)] = 0;
        }
    surfs.push_back(std::move(s));
  }

  bool los(const Vec3& a, const Vec3& b) const {
    Vec3 d = b - a;
    Vec3 end = a + d * 0.995;  // the target surface itself must not block
    for (auto& bx : room.boxes)
      if (seg_box(a, end, bx)) return false;
    const auto& P = room.poly;
    for (size_t i = 0; i < P.size(); ++i) {
      auto [x0, z0] = P[i];
      auto [x1, z1] = P[(i + 1) % P.size()];
      double t = seg_hit(a.x(), a.z(), end.x(), end.z(), x0, z0, x1, z1);
      if (t > 0.0005) return false;
    }
    return true;
  }

  bool walkable(double x, double z, double margin) const {
    if (!in_poly(room.poly, x, z)) return false;
    const auto& P = room.poly;
    for (size_t i = 0; i < P.size(); ++i) {
      auto [x0, z0] = P[i];
      auto [x1, z1] = P[(i + 1) % P.size()];
      if (dist_seg(x, z, x0, z0, x1, z1) < margin) return false;
    }
    for (auto& b : room.boxes) {  // a person is a disc: Euclidean distance to the footprint, not a square margin
      double dx = std::max({b.x0 - x, 0.0, x - b.x1}), dz = std::max({b.z0 - z, 0.0, z - b.z1});
      if (std::hypot(dx, dz) < margin) return false;
    }
    return true;
  }

  size_t total_valid() const {
    size_t n = 0;
    for (auto& s : surfs)
      for (size_t k = 0; k < s.cells.size(); ++k) n += s.valid[k];
    return n;
  }
};

geometry::Intrinsics make_K() {
  geometry::Intrinsics K;
  K.width = 640, K.height = 480, K.fx = K.fy = 500, K.cx = 319.5, K.cy = 239.5;
  return K;
}

double gt_completeness(const World& w, const GuideParams& gp, size_t* sufficient_out = nullptr) {
  size_t ok = 0, tot = 0;
  for (auto& s : w.surfs)
    for (size_t k = 0; k < s.cells.size(); ++k) {
      if (!s.valid[k]) continue;
      ++tot;
      ok += s.cells[k].sufficient(gp);
    }
  if (sufficient_out) *sufficient_out = ok;
  return tot ? double(ok) / double(tot) : 0.0;
}

// ---- Human model -------------------------------------------------------------------------------
struct Goal {
  InstrType type = InstrType::Finish;
  double yaw_target = 0, pitch_target = 0, dist_left = 0, heading_target = 0, until_s = 0;
  bool turning = false, backwards = false, active = false;
};

struct Walker {
  double x, z, yaw, pitch = 0;
  double speed_scale = 1;
  double slow_until = -1, frozen_until = -1;
  bool done = false;
  Goal goal;
  struct Pending {
    Instruction ins;
    double due;
  };
  std::deque<Pending> pending;
  double path = 0, yaw_rate = 0, pitch_rate = 0, speed = 0;
  int blocked = 0, received = 0, followed = 0;
};

void turn_toward(double& cur, double target, double rate, double dt, double& out_rate) {
  double e = wrap_pi(target - cur);
  double step = std::clamp(e, -rate * dt, rate * dt);
  cur = wrap_pi(cur + step);
  out_rate = step / dt;
}

}  // namespace

std::string simulate(const Config& cfg) {
  const Room room = make_room(cfg.room);
  const GuideParams& gp = cfg.guide;
  World w(room, gp.cell_m);
  Rng rng(cfg.seed);
  const double dt = 1.0 / 30, cam_h = 1.4;
  const geometry::Intrinsics K = make_K();
  const double exposure_s = 0.010;  // well-lit room; blur px = (rotation + translation/2 m) * exposure * fx
  quality::QualityConfig qc;
  quality::KeyframeSelector selector(qc);
  GuideEngine engine(gp);
  const bool use_engine = cfg.policy == "planner";

  Walker me{room.start_x, room.start_z, room.start_yaw};
  const double dtr = deg2rad(1.0);
  const Human& H = cfg.human;

  // Scripted policy: perimeter loop at ~1 m inset facing the wall, then centre spins.
  struct Way {
    double x, z;
  };
  std::vector<Way> script;
  size_t way_i = 0;
  double script_spin = 0;
  int spin_phase = 0;
  if (cfg.policy == "scripted") {
    // inset the rectilinear footprint by moving each vertex along the bisector toward the interior
    auto& P = room.poly;
    for (size_t i = 0; i < P.size(); ++i) {
      auto [x, z] = P[i];
      auto [xp, zp] = P[(i + P.size() - 1) % P.size()];
      auto [xn, zn] = P[(i + 1) % P.size()];
      double ax = x - xp, az = z - zp, bx = xn - x, bz = zn - z;
      double la = std::hypot(ax, az), lb = std::hypot(bx, bz);
      double n1x = -az / la, n1z = ax / la, n2x = -bz / lb, n2z = bx / lb;  // inward normals
      double sx = n1x + n2x, sz = n1z + n2z;
      double ls = std::hypot(sx, sz);
      double cosh = ls / 2;  // cos(half corner angle)
      double d = 1.3 / std::max(cosh, 0.3);  // a decent surveyor walks about 1.3 m off the wall
      script.push_back({x + sx / ls * d, z + sz / ls * d});
    }
    script.push_back(script.front());
    const size_t lap = script.size();  // two laps (the second lap with a different pitch phase)
    for (size_t i = 0; i < lap; ++i) script.push_back(script[i]);
  }
  double wander_t = 0, wander_turn = 0;

  // Per-frame stash for delayed keyframe decisions.
  struct Stash {
    geometry::PoseWC pose;
    double sharp;
    int64_t t;
  };
  std::map<uint32_t, Stash> stash;
  uint32_t frame_id = 0;
  std::vector<quality::KeyframeSelector::Decision> decisions;

  double t80 = -1, t90 = -1, t95 = -1;
  int n_frames = 0, n_keyframes = 0, unsafe_gt = 0, last_target_id = 0;
  std::vector<std::pair<double, double>> series;
  std::vector<std::string> trace, overlay_samples;
  size_t overlay_max_bytes = 0;
  int tracking = 1, tracking_loss_events = 0;
  double lost_until = -1;
  double finish_issued_t = -1;
  int64_t t_ns = 0;
  const size_t total_cells = w.total_valid();
  std::vector<Instruction> new_ins;
  std::map<std::string, int> ins_seen;
  std::vector<std::pair<Vec3, double>> unused;
  (void)unused;
  const int steps = int(cfg.max_s / dt);
  double prev_yaw = me.yaw, prev_pitch = me.pitch;
  Vec3 prev_pos(me.x, cam_h, me.z);
  int step = 0;

  for (; step < steps && !me.done; ++step) {
    const double t = step * dt;
    t_ns = int64_t(step) * 33'333'333;

    // ---- sensors -> engine -------------------------------------------------------------------
    Vec3 pos(me.x + rng.normal() * 0.002, cam_h + 0.01 * std::sin(t * 2 * kPi * 1.5) * std::min(1.0, me.speed), me.z + rng.normal() * 0.002);
    double yaw_s = me.yaw + rng.normal() * dtr * 0.1, pitch_s = me.pitch + rng.normal() * dtr * 0.1;
    geometry::PoseWC T = pose_from_yaw_pitch(pos, yaw_s, pitch_s);
    double omega = std::hypot(me.yaw_rate, me.pitch_rate);
    if (lost_until >= 0 && t >= lost_until) lost_until = -1;
    int state = lost_until >= 0 ? 3 : (omega > deg2rad(110) ? 2 : 1);
    if (omega > deg2rad(160) && lost_until < 0) {
      lost_until = t + 1.5;
      state = 3;
    }
    if (state == 3 && tracking != 3) ++tracking_loss_events;
    tracking = state;

    if (use_engine) {
      bundle::PoseSample ps;
      ps.t_ns = t_ns;
      T.to_tq(ps.t_world_from_camera, ps.q_world_from_camera_wxyz);
      ps.tracking_state = bundle::TrackingState(state);
      if (step == 0) {
        bundle::IntrinsicsSample k;
        k.t_ns = t_ns, k.width = K.width, k.height = K.height, k.fx = k.fy = K.fx, k.cx = K.cx, k.cy = K.cy;
        engine.on_intrinsics(k);
      }
      engine.on_pose(ps);
    }

    ViewCam vc = ViewCam::make(T, K);
    const double cos_max = std::cos(deg2rad(gp.max_incidence_deg));

    // points (5 Hz) from visible surfaces and furniture faces
    if (step % 6 == 0 && state != 3) {
      bundle::PointCloudRecord pc;
      pc.t_ns = t_ns;
      double total_w = 0;
      for (double x : w.weights) total_w += x;
      for (int tries = 0; tries < 160 && pc.xyz.size() < 3 * 40; ++tries) {
        double r = rng.uniform() * total_w, acc = 0;
        size_t idx = 0;
        for (; idx < w.weights.size(); ++idx) {
          acc += w.weights[idx];
          if (r <= acc) break;
        }
        idx = std::min(idx, w.weights.size() - 1);
        Vec3 p, n;
        Surf* sf = nullptr;
        double a = rng.uniform(), b = rng.uniform();
        if (idx < w.surfs.size()) {
          sf = &w.surfs[idx];
          int i = int(a * sf->nu), j = int(b * sf->nv);
          if (!sf->valid[size_t(j) * size_t(sf->nu) + size_t(i)]) continue;
          p = sf->center(i, j, w.cell);
          n = sf->n;
        } else {
          const Face& f = w.faces[idx - w.surfs.size()];
          p = f.o + f.u * (a * f.su) + f.v * (b * f.sv);
          n = f.n;
        }
        VisResult vr = view_cell(vc, p, n, gp.view_margin_px);
        if (!vr.in_image || vr.range > 6.0 || vr.cos_inc < 0.1 || !w.los(vc.pos, p)) continue;
        Vec3 q = p + Vec3(rng.normal(), rng.normal(), rng.normal()) * 0.01;
        pc.xyz.push_back(float(q.x())), pc.xyz.push_back(float(q.y())), pc.xyz.push_back(float(q.z()));
        if (sf) {
          double du = (p - sf->o).dot(sf->u), dv = (p - sf->o).dot(sf->v);
          sf->umin = std::min(sf->umin, du), sf->umax = std::max(sf->umax, du);
          sf->vmin = std::min(sf->vmin, dv), sf->vmax = std::max(sf->vmax, dv);
          ++sf->npts;
        }
      }
      if (use_engine && !pc.xyz.empty()) engine.on_points(pc);
    }
    // planes (1 Hz): rectangles that grow as more is seen
    if (use_engine && step % 30 == 15) {
      for (size_t i = 0; i < w.surfs.size(); ++i) {
        const Surf& s = w.surfs[i];
        if (s.npts < 15) continue;
        bundle::PlaneRecord pr;
        pr.t_ns = t_ns + int64_t(i);
        pr.id = std::to_string(i);
        pr.kind = s.kind;
        Vec3 c = s.o + s.u * (0.5 * (s.umin + s.umax)) + s.v * (0.5 * (s.vmin + s.vmax));
        for (int k = 0; k < 3; ++k) pr.center[k] = c[k], pr.normal[k] = s.n[k], pr.axis_u[k] = s.u[k];
        pr.extent_u = s.umax - s.umin;
        pr.extent_v = s.vmax - s.vmin;
        engine.on_plane(pr);
      }
    }
    // frames (10 Hz): quality from motion, keyframe selection with the real selector
    if (step % 3 == 0) {
      double blur = omega * exposure_s * K.fx + (me.speed / 2.0) * exposure_s * K.fx;
      quality::FrameMetrics m;
      m.blur_risk_px = blur;
      m.sharpness_norm = std::max(0.2, 1.0 - blur / 8.0);
      m.tracking_state = state;
      if (state == 3) m.hard_reject = true, m.reasons |= quality::kTrackingLost;
      else if (blur > qc.blur_reject_px) m.hard_reject = true, m.reasons |= quality::kMotionBlur;
      else if (blur > qc.blur_soft_px) m.reasons |= quality::kMotionBlur;
      m.quality = m.hard_reject ? 0.0 : std::clamp(1.0 - 0.1 * blur, 0.0, 1.0);
      uint32_t id = frame_id++;
      ++n_frames;
      stash[id] = {T, m.sharpness_norm, t_ns};
      decisions.clear();
      selector.push(id, t_ns, m, T, decisions);
      if (use_engine) {
        FrameObs fo;
        fo.t_ns = t_ns, fo.frame_id = id, fo.hard_reject = m.hard_reject, fo.motion_blur = (m.reasons & quality::kMotionBlur) != 0;
        engine.on_frame(fo);
      }
      for (auto& d : decisions) {
        auto it = stash.find(d.frame_id);
        if (it == stash.end()) continue;
        if (d.keyframe) {
          ++n_keyframes;
          if (use_engine) engine.on_keyframe(d.frame_id, it->second.t, it->second.sharp);
          // ground truth with the same rule + true occlusion
          ViewCam gc = ViewCam::make(it->second.pose, K);
          for (auto& s : w.surfs)
            for (int j = 0; j < s.nv; ++j)
              for (int i = 0; i < s.nu; ++i) {
                size_t k = size_t(j) * size_t(s.nu) + size_t(i);
                if (!s.valid[k]) continue;
                Vec3 p = s.center(i, j, w.cell);
                VisResult vr = view_cell(gc, p, s.n, gp.view_margin_px);
                if (!vr.in_image || vr.range > gp.range_max_m * 1.5 || !w.los(gc.pos, p)) continue;
                bool q = vr.range >= gp.range_min_m && vr.range <= gp.range_max_m && vr.cos_inc >= cos_max && it->second.sharp >= gp.min_sharp_norm;
                if (q) s.cells[k].add_view(vr.ray, vr.range, it->second.sharp, it->second.t);
                else s.cells[k].see(it->second.t);
              }
        }
        stash.erase(it);
      }
    }
    // coverage milestones (1 Hz)
    if (step % 30 == 0) {
      double c = gt_completeness(w, gp);
      series.push_back({t, c});
      if (t80 < 0 && c >= 0.80) t80 = t;
      if (t90 < 0 && c >= 0.90) t90 = t;
      if (t95 < 0 && c >= 0.95) t95 = t;
      if (c >= 0.98 && cfg.policy != "planner") break;
    }

    // ---- policy -> motion --------------------------------------------------------------------
    double yr = 0, pr = 0, v_cmd = 0, backward = 0;
    if (use_engine) {
      new_ins.clear();
      engine.update(t_ns, new_ins);
      if (cfg.overlay_every_s > 0 && step % 15 == 0) {  // the session emits the overlay every 500 ms of event time
        std::string oj = engine.overlay(t_ns).to_json();
        overlay_max_bytes = std::max(overlay_max_bytes, oj.size());
        if (step % std::max(1, int(std::lround(cfg.overlay_every_s * 30))) == 0) overlay_samples.push_back(oj);
      }
      {  // ground-truth safety: a target must be walkable in the TRUE room (not just in the engine's map)
        const Target& tg = engine.planner().current();
        if (tg.valid && tg.id != last_target_id) {
          last_target_id = tg.id;
          if (!w.walkable(tg.x, tg.z, 0.25)) {
            ++unsafe_gt;
            if (cfg.trace) trace.push_back(json::str("UNSAFE target " + std::to_string(tg.x) + "," + std::to_string(tg.z) + " at t=" + std::to_string(int(t)) + " user " + std::to_string(me.x) + "," + std::to_string(me.z) + " fallback=" + std::to_string(tg.fallback)));
          }
        }
      }
      for (auto& ins : new_ins) {
        if (cfg.trace) trace.push_back(json::str(std::to_string(int(t)) + "s " + type_name(ins.type) + " " + ins.speak_text + " @(" + std::to_string(me.x).substr(0, 4) + "," + std::to_string(me.z).substr(0, 4) + ") yaw" + std::to_string(int(rad2deg(me.yaw))) + " tgt(" + std::to_string(engine.planner().current().x).substr(0, 4) + "," + std::to_string(engine.planner().current().z).substr(0, 4) + ") mode" + std::to_string(int(engine.last_plan().mode))));
        ++ins_seen[type_name(ins.type)];
        ++me.received;
        if (ins.type == InstrType::Finish && finish_issued_t < 0) finish_issued_t = t;
        if (rng.uniform() > H.compliance) continue;  // ignored
        ++me.followed;
        double due = t + rng.uniform(H.delay_min, H.delay_max);
        if (ins.priority == 3) {  // safety reactions are faster
          due = t + rng.uniform(0.3, 1.0);
          me.pending.clear();
        } else {
          me.pending.erase(std::remove_if(me.pending.begin(), me.pending.end(), [](auto& p) { return p.ins.priority <= 2; }), me.pending.end());
        }
        me.pending.push_back({ins, due});
      }
      // start the next due instruction (urgent ones interrupt)
      for (auto it = me.pending.begin(); it != me.pending.end();) {
        if (it->due > t) {
          ++it;
          continue;
        }
        if (me.goal.active && it->ins.priority < 3) {  // busy: keep waiting
          ++it;
          continue;
        }
        const Instruction& ins = it->ins;
        Goal g;
        g.type = ins.type;
        g.active = true;
        switch (ins.type) {
          case InstrType::RotateToward:
            g.yaw_target = wrap_pi(me.yaw + deg2rad(ins.param("rel_deg")) + rng.normal() * deg2rad(H.yaw_noise_deg));
            break;
          case InstrType::MoveTo:
            g.heading_target = wrap_pi(me.yaw + deg2rad(ins.param("rel_bearing_deg")) + rng.normal() * deg2rad(H.yaw_noise_deg));
            g.dist_left = ins.param("distance_m") * (1 + 0.1 * rng.normal());
            g.turning = true;
            break;
          case InstrType::TiltUp: g.pitch_target = std::min(deg2rad(45), me.pitch + deg2rad(ins.param("deg"))); break;
          case InstrType::TiltDown: g.pitch_target = std::max(deg2rad(-45), me.pitch - deg2rad(ins.param("deg"))); break;
          case InstrType::StepBack: g.dist_left = ins.param("meters", 0.5); g.backwards = true; break;
          case InstrType::Sweep: g.yaw_target = 0, g.dist_left = 2 * kPi; break;  // dist_left = angle left to turn
          case InstrType::SlowDown: me.slow_until = t + 5; g.active = false; break;
          case InstrType::HoldStill: case InstrType::Relocalize: me.frozen_until = t + (ins.type == InstrType::HoldStill ? 3.0 : 5.0); g.active = false; break;
          case InstrType::Finish: me.done = true; g.active = false; break;
          default: g.active = false; break;
        }
        if (g.active) me.goal = g;
        it = me.pending.erase(it);
      }
      if (t < me.frozen_until) me.goal.active = false;
    } else if (cfg.policy == "scripted") {
      if (way_i < script.size()) {
        const Way& wp = script[way_i];
        double dx = wp.x - me.x, dz = wp.z - me.z, d = std::hypot(dx, dz);
        if (d < 0.25 || me.blocked > 45) {
          ++way_i, me.blocked = 0;
        } else {
          // face the nearest wall (outward), sinusoidal pitch sweep, walk toward the waypoint
          double toward = std::atan2(dx, dz);
          double wall_yaw = toward + kPi / 2;  // CCW loop: the wall is on the left
          turn_toward(me.yaw, wall_yaw, deg2rad(H.turn_dps), dt, yr);
          me.pitch = deg2rad(22) * std::sin(t * 0.35 + (way_i >= script.size() / 2 ? kPi : 0.0));
          pr = 0;
          v_cmd = H.speed * 0.8;
          // walk toward waypoint regardless of facing direction (side-stepping while looking at the wall)
          double nx = me.x + dx / d * v_cmd * dt, nz = me.z + dz / d * v_cmd * dt;
          if (w.walkable(nx, nz, 0.25)) me.x = nx, me.z = nz, me.blocked = 0;
          else ++me.blocked, v_cmd = 0;
        }
      } else {
        // centre spins at three pitches
        double rate = deg2rad(20);
        me.yaw = wrap_pi(me.yaw + rate * dt);
        yr = rate;
        script_spin += rate * dt;
        double target_p = spin_phase == 0 ? 0 : spin_phase == 1 ? deg2rad(30) : deg2rad(-30);
        turn_toward(me.pitch, target_p, deg2rad(H.pitch_dps), dt, pr);
        if (script_spin >= 2 * kPi) script_spin = 0, ++spin_phase;
        if (spin_phase >= 3) way_i = 0, spin_phase = 0, script_spin = 0;  // diligent surveyor: repeat the routine
      }
    } else {  // wander
      // straight for 3-6 s, then a short turn burst (random direction), turning away when blocked
      wander_t -= dt;
      if (wander_t <= 0) {
        if (wander_turn == 0) wander_turn = (rng.uniform() < 0.5 ? -1.0 : 1.0), wander_t = rng.uniform(0.6, 1.6);
        else wander_turn = 0, wander_t = rng.uniform(3, 6);
      }
      if (wander_turn != 0) me.yaw = wrap_pi(me.yaw + wander_turn * deg2rad(25) * dt), yr = wander_turn * deg2rad(25);
      me.pitch = deg2rad(15) * std::sin(t * 0.3 + 1.0);
      v_cmd = H.speed * 0.7;
      double nx = me.x + std::sin(me.yaw) * v_cmd * dt, nz = me.z + std::cos(me.yaw) * v_cmd * dt;
      if (w.walkable(nx, nz, 0.25)) me.x = nx, me.z = nz;
      else if (wander_turn == 0) wander_turn = (rng.uniform() < 0.5 ? -1.0 : 1.0), wander_t = rng.uniform(2.0, 4.0), v_cmd = 0;
      else v_cmd = 0;
    }

    if (use_engine && me.goal.active && t >= me.frozen_until) {
      Goal& g = me.goal;
      double scale = t < me.slow_until ? 0.5 : 1.0;
      switch (g.type) {
        case InstrType::RotateToward:
          turn_toward(me.yaw, g.yaw_target, deg2rad(H.turn_dps) * scale, dt, yr);
          if (std::fabs(wrap_pi(g.yaw_target - me.yaw)) < deg2rad(2)) g.active = false;
          break;
        case InstrType::MoveTo: {
          if (g.turning) {
            turn_toward(me.yaw, g.heading_target, deg2rad(H.turn_dps) * scale, dt, yr);
            if (std::fabs(wrap_pi(g.heading_target - me.yaw)) < deg2rad(8)) g.turning = false;
          } else {
            double step_d = H.speed * scale * dt;
            double nx = me.x + std::sin(me.yaw) * step_d, nz = me.z + std::cos(me.yaw) * step_d;
            if (w.walkable(nx, nz, 0.25)) me.x = nx, me.z = nz, g.dist_left -= step_d, v_cmd = H.speed * scale;
            else ++me.blocked, g.active = false;
            if (g.dist_left <= 0) g.active = false;
          }
          break;
        }
        case InstrType::TiltUp: case InstrType::TiltDown:
          turn_toward(me.pitch, g.pitch_target, deg2rad(H.pitch_dps), dt, pr);
          if (std::fabs(g.pitch_target - me.pitch) < deg2rad(2)) g.active = false;
          break;
        case InstrType::StepBack: {
          double step_d = 0.5 * dt;
          double nx = me.x - std::sin(me.yaw) * step_d, nz = me.z - std::cos(me.yaw) * step_d;
          if (w.walkable(nx, nz, 0.25)) me.x = nx, me.z = nz, g.dist_left -= step_d, backward = 0.5;
          else ++me.blocked, g.active = false;
          if (g.dist_left <= 0) g.active = false;
          break;
        }
        case InstrType::Sweep: {
          double rate = deg2rad(20) * scale;  // 'turn slowly': faster would blur every frame
          me.yaw = wrap_pi(me.yaw + rate * dt);
          yr = rate;
          g.dist_left -= rate * dt;
          if (g.dist_left <= 0) g.active = false;
          break;
        }
        default: g.active = false; break;
      }
    }

    // bookkeeping for the next step
    me.yaw_rate = wrap_pi(me.yaw - prev_yaw) / dt;
    me.pitch_rate = (me.pitch - prev_pitch) / dt;
    Vec3 now(me.x, cam_h, me.z);
    me.speed = std::hypot(now.x() - prev_pos.x(), now.z() - prev_pos.z()) / dt;
    me.path += std::hypot(now.x() - prev_pos.x(), now.z() - prev_pos.z());
    prev_yaw = me.yaw, prev_pitch = me.pitch, prev_pos = now;
    (void)v_cmd; (void)backward; (void)yr; (void)pr;
    if (finish_issued_t >= 0 && me.done) break;
  }

  // ---- report -----------------------------------------------------------------------------------
  const double dur = step * dt;
  size_t suff = 0;
  double gt_final = gt_completeness(w, gp, &suff);
  if (t80 < 0 && gt_final >= 0.80) t80 = dur;
  if (t90 < 0 && gt_final >= 0.90) t90 = dur;
  if (t95 < 0 && gt_final >= 0.95) t95 = dur;
  auto r3 = [](double x) { return std::round(x * 1000) / 1000; };
  json::Object o;
  o.set("room", cfg.room).set("policy", cfg.policy).set("seed", int64_t(cfg.seed)).set("params_hash", gp.hash());
  o.set("duration_s", r3(dur)).set("path_m", r3(me.path)).set("finished", me.done);
  {
    struct Acc { size_t tot = 0, suff = 0, viewed = 0, seen = 0; };
    std::map<std::string, Acc> by;
    for (auto& sf : w.surfs)
      for (size_t k = 0; k < sf.cells.size(); ++k) {
        if (!sf.valid[k]) continue;
        auto& e = by[sf.kind];
        ++e.tot;
        e.suff += sf.cells[k].sufficient(gp);
        e.viewed += sf.cells[k].views > 0;
        e.seen += sf.cells[k].seen;
      }
    json::Object kb;
    for (auto& [k, v] : by) {
      json::Object d;
      d.set("sufficient", r3(double(v.suff) / double(v.tot))).set("viewed", r3(double(v.viewed) / double(v.tot))).set("seen", r3(double(v.seen) / double(v.tot)));
      kb.raw(k, d.dump());
    }
    o.raw("coverage_gt_by_kind", kb.dump());
  }
  o.set("coverage_gt_final", r3(gt_final)).set("cells", uint64_t(total_cells));
  o.set("t80_s", t80 < 0 ? -1.0 : r3(t80)).set("t90_s", t90 < 0 ? -1.0 : r3(t90)).set("t95_s", t95 < 0 ? -1.0 : r3(t95));
  {
    size_t seen = 0, viewed = 0, maxv = 0;
    for (auto& sf : w.surfs)
      for (size_t k = 0; k < sf.cells.size(); ++k) {
        seen += sf.cells[k].seen, viewed += sf.cells[k].views > 0, maxv = std::max<size_t>(maxv, sf.cells[k].views);
      }
    o.set("dbg_seen", uint64_t(seen)).set("dbg_viewed", uint64_t(viewed)).set("dbg_maxviews", uint64_t(maxv));
  }
  o.set("frames", n_frames).set("keyframes", n_keyframes).set("end_x", r3(me.x)).set("end_z", r3(me.z));
  o.set("tracking_loss_events", tracking_loss_events).set("blocked_events", me.blocked);
  std::vector<std::string> ser;
  for (size_t i = 0; i < series.size(); i += 10)
    ser.push_back("[" + json::num(r3(series[i].first)) + "," + json::num(r3(series[i].second)) + "]");
  o.raw("coverage_series_10s", json::array(ser.begin(), ser.end()));
  if (cfg.trace) o.raw("trace", json::array(trace.begin(), trace.end()));
  if (use_engine) {
    auto st = engine.coverage().stats();
    o.set("coverage_claimed_final", r3(st.completeness())).set("tier", std::string(1, engine.tier()));
    o.set("finish_issued_s", finish_issued_t < 0 ? -1.0 : r3(finish_issued_t));
    o.set("unsafe_targets_gt", unsafe_gt);
    if (cfg.overlay_every_s > 0) o.raw("overlay_samples", json::array(overlay_samples.begin(), overlay_samples.end())).set("overlay_max_bytes", uint64_t(overlay_max_bytes));
    o.set("instructions_received", me.received).set("instructions_followed", me.followed);
    o.raw("guide", engine.report_json());
    double mins = std::max(dur / 60.0, 1e-9);
    o.set("flap_per_min", r3(engine.planner().switches() / mins));
  }
  return o.dump();
}

}  // namespace capturekit::guide::sim
