#pragma once
// Planner (Phase 8, stage 4): frontier / next-best-view. Candidate viewpoints are passable lattice
// cells x headings x pitches. Score = coverage gain / (path + turn + safety cost + 1), subject to an
// overlap constraint (the view must share content with what is already mapped, so tracking holds).
// Hysteresis: a new target must beat the current one by `hysteresis`; the current target is kept
// until reached or obsolete. Rule-based fallbacks: sweep (no map yet), walk toward unseen area, finish.
#include <vector>

#include "capturekit/guide/coverage.hpp"
#include "capturekit/guide/nav_grid.hpp"

namespace capturekit::guide {

struct UserState {
  Vec3 pos{0, 0, 0};
  double yaw = 0, pitch = 0;
  double speed = 0, yaw_rate = 0;  // m/s, rad/s (smoothed)
};

struct Target {
  bool valid = false, fallback = false;
  double x = 0, z = 0, yaw = 0, pitch = 0;
  double gain = 0, score = 0, gain0 = 0;  // gain0: gain when the target was chosen
  int64_t t_set = 0, at_since = -1;       // at_since: when the user first stood at the target pose
  int id = 0;
};

enum class Mode { Sweep, Target, Finish };

/// Facts about the map the planner cannot derive from coverage alone.
struct PlanHints {
  bool need_floor = false, need_ceiling = false;  // no such plane detected yet: ask the user to look there
};

struct Plan {
  Mode mode = Mode::Sweep;
  Target target;
  double completeness = 0;
  double path_m = 0;  // path length to the target
  bool target_changed = false;
  bool reached = false;
  bool discover = false;  // the target is a look-up/look-down to find a missing floor/ceiling plane
};

class Planner {
 public:
  explicit Planner(const GuideParams& p) : p_(p) {}
  Plan replan(int64_t t_ns, const UserState& u, const Coverage& cov, NavGrid& nav,
              const geometry::Intrinsics& K, double cam_height, const PlanHints& hints = {});
  const Target& current() const { return cur_; }
  /// The user could not carry out the current target (blocked, ignored): remember it and re-plan.
  void penalize_current();
  int switches() const { return switches_; }
  int reached_count() const { return reached_total_; }
  /// For tests: score a viewpoint with the planner's cost model.
  double score_view(const UserState& u, double x, double z, double yaw, double pitch, const Coverage& cov,
                    const NavGrid& nav, const std::vector<float>& dist, const geometry::Intrinsics& K,
                    double cam_height, double* gain_out = nullptr, double* path_out = nullptr) const;

 private:
  struct Visited {
    double x, z, yaw;
    int n;
  };
  double revisit_factor(double x, double z, double yaw) const;
  GuideParams p_;
  Target cur_;
  std::vector<Visited> visited_;
  int next_id_ = 1, switches_ = 0, reached_total_ = 0, discover_tries_ = 0;
};

}  // namespace capturekit::guide
