#pragma once
// GuideEngine (Phase 8): LiveMap + Coverage + NavGrid + Planner + InstructionGenerator + GuideLog.
// Platform-independent and deterministic: all time comes from the t_ns of the inputs.
// Feed it the same streams a bundle carries (pose, intrinsics, points, planes) plus per-frame quality
// and keyframe decisions from the quality module; call update() as often as poses arrive.
#include <map>
#include <memory>
#include <string>
#include <vector>

#include "capturekit/bundle/types.hpp"
#include "capturekit/guide/coverage.hpp"
#include "capturekit/guide/guide_log.hpp"
#include "capturekit/guide/instruction.hpp"
#include "capturekit/guide/live_map.hpp"
#include "capturekit/guide/nav_grid.hpp"
#include "capturekit/guide/opening_detector.hpp"
#include "capturekit/guide/overlay.hpp"
#include "capturekit/guide/planner.hpp"
#include "capturekit/guide/point_store.hpp"

namespace capturekit::guide {

struct FrameObs {
  int64_t t_ns = 0;
  uint32_t frame_id = 0;
  bool hard_reject = false;
  bool motion_blur = false, underexposed = false;
  double sharpness_norm = 1;
};

class GuideEngine {
 public:
  explicit GuideEngine(const GuideParams& p = {});

  void on_intrinsics(const bundle::IntrinsicsSample& k);
  void on_pose(const bundle::PoseSample& p);
  void on_points(const bundle::PointCloudRecord& p);
  void on_plane(const bundle::PlaneRecord& p);
  void on_frame(const FrameObs& f);
  /// A keyframe was chosen: its view updates coverage (pose looked up at t_ns).
  void on_keyframe(uint32_t frame_id, int64_t t_ns, double sharpness_norm);
  /// Plans (at replan_hz) and generates instructions; new ones are appended to `out`.
  void update(int64_t t_ns, std::vector<Instruction>& out);

  /// Governor hook: scales the planner rate (1 = full). The session lowers it under thermal/battery pressure.
  void set_rate_scale(double s) { rate_scale_ = std::max(0.05, std::min(1.0, s)); }
  const GuideParams& params() const { return p_; }
  const LiveMap& map() const { return map_; }
  const Coverage& coverage() const { return cov_; }
  const NavGrid& nav() const { return nav_; }
  const Planner& planner() const { return planner_; }
  const GuideLog& log() const { return log_; }
  const UserState& user() const { return user_; }
  const Plan& last_plan() const { return plan_; }
  /// 'B' when planes or points were seen, else 'C' (pose-only). Depth tier 'A' is not wired yet.
  char tier() const { return map_.surface_count() > 0 || points_seen_ ? 'B' : 'C'; }
  std::string guidance_log_json() const { return log_.to_json(p_.hash()); }
  std::string report_json() const;
  /// Glanceable snapshot of what the engine understands at event time t_ns (see overlay.hpp). Pure: no state change.
  OverlayModel overlay(int64_t t_ns) const;
  /// Phase 9 (understanding on only; empty otherwise): accumulated sparse points and the doors/windows found
  /// in them, re-detected at most once per second of event time.
  const SparsePointStore& points() const { return pts_; }
  const std::vector<Opening>& openings() const { return openings_; }

 private:
  void refresh_levels();
  void assign_wall_ids();  // overlay wall numbers: 1-based, first-seen order, never reused or renumbered
  GuideParams p_;
  LiveMap map_;
  Coverage cov_;
  NavGrid nav_;
  Planner planner_;
  InstructionGenerator gen_;
  GuideLog log_;
  geometry::PoseTrack track_;
  geometry::Intrinsics K_;
  bool have_K_ = false, have_pose_ = false, points_seen_ = false;
  UserState user_;
  Plan plan_;
  int tracking_ = 1, blur_streak_ = 0;
  int64_t lost_since_ = 0, last_pose_t_ = 0, last_plan_t_ = -1, last_sample_t_ = -1, last_underexp_t_ = 0;
  bool underexp_seen_ = false;
  double cam_height_ = 1.4, rate_scale_ = 1.0;
  double too_close_ = 0;
  Vec3 last_trav_{1e9, 0, 1e9};
  int map_version_ = -1;
  std::vector<Instruction> scratch_;
  std::vector<LifeEvent> life_;
  SparsePointStore pts_;
  OpeningDetector opener_;
  std::vector<Opening> openings_;
  int64_t last_open_t_ = -1;
  std::map<std::string, int> wall_ids_;
  int next_wall_id_ = 1;
  uint32_t yaw_mask_ = 0;  // overlay v2 (ADR 0022)
};

}  // namespace capturekit::guide
