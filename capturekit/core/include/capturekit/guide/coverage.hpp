#pragma once
// Coverage engine (Phase 8, stage 2): per-plane 10 cm cell grids. A cell is SUFFICIENT with at least
// min_views qualifying keyframe views (in the distance band, under the incidence limit, sharp enough)
// whose viewing rays span at least min_spread_deg. Completeness is sufficient/valid cells.
// Honest limit: only DETECTED planes are covered here; undetected surfaces are Phase 9 (room shell).
#include <cstdint>
#include <vector>

#include "capturekit/guide/live_map.hpp"
#include "capturekit/guide/params.hpp"

namespace capturekit::guide {

/// Line-of-sight oracle (implemented by NavGrid): is the segment cam -> target free of obstacles?
struct Occluder {
  virtual ~Occluder() = default;
  virtual bool clear(const Vec3& cam, const Vec3& target) const = 0;
};

/// Per-cell statistics. Shared with the simulator so ground truth uses the same rule.
struct CellStat {
  uint8_t views = 0, occ = 0;  // occ: in-frustum keyframe views blocked by obstacles
  bool seen = false;
  float spread_deg = 0, d01_deg = 0;
  float r0[3] = {0, 0, 0}, r1[3] = {0, 0, 0};
  float nearest = 1e9f, best_sharp = 0;
  int64_t last_t = 0;

  void see(int64_t t) { seen = true, last_t = t; }
  void add_view(const Vec3& ray_to_cam, double range, double sharp, int64_t t);
  bool sufficient(const GuideParams& p) const { return views >= p.min_views && spread_deg >= p.min_spread_deg; }
  /// Never seen unobstructed despite repeated tries (e.g. floor under furniture): excluded from totals.
  bool unobservable() const { return views == 0 && occ >= 8; }
};

/// Camera model reduced to what visibility needs.
struct ViewCam {
  Vec3 pos;
  Eigen::Matrix3d R_cw;  // camera_from_world rotation
  double fx = 0, fy = 0, cx = 0, cy = 0, w = 0, h = 0;
  static ViewCam make(const geometry::PoseWC& T_wc, const geometry::Intrinsics& K);
};

struct VisResult {
  bool in_image = false;
  double range = 0, cos_inc = 0;
  Vec3 ray;  // unit vector from the cell to the camera
};
VisResult view_cell(const ViewCam& cam, const Vec3& p, const Vec3& n, double margin_px);

struct Sample {
  Vec3 p, n;
  float w = 1;
  bool seen = false;
  uint8_t views = 0;
  float spread = 0, r0[3] = {0, 0, 0}, r1[3] = {0, 0, 0};
};

struct ViewEval {
  double gain = 0;      // marginal sufficiency progress (views + ray spread), scaled by sampling stride
  double overlap = 0;   // fraction of visible cells already seen (tracking/overlap constraint)
  double too_close = 0, too_far = 0;  // fraction of visible deficient cells outside the distance band
  int visible = 0;
};

struct Obs {
  geometry::PoseWC pose;
  geometry::Intrinsics K;
  int64_t t_ns = 0;
  double sharp_norm = 1;
  bool keyframe = true;
};

struct CoverageStats {
  size_t valid = 0, seen = 0, sufficient = 0;
  double completeness() const { return valid ? double(sufficient) / double(valid) : 0.0; }
  double seen_fraction() const { return valid ? double(seen) / double(valid) : 0.0; }
};

class Coverage {
 public:
  explicit Coverage(const GuideParams& p) : p_(p) {}
  /// Adopts new/changed plane geometry (call when LiveMap::version() changed).
  void sync(const LiveMap& map);
  /// Updates cells from a keyframe observation. seen_floor receives centres of newly seen floor cells.
  void observe(const Obs& o, const Occluder* los, std::vector<Vec3>* seen_floor);

  CoverageStats stats() const;
  CoverageStats stats(const std::string& plane_id) const;
  std::vector<std::string> plane_ids() const;
  ViewEval eval_view(const geometry::PoseWC& T_wc, const geometry::Intrinsics& K, const Occluder* los,
                     bool want_overlap = true) const;
  /// Sample lists for the planner (rebuilt lazily; deterministic order).
  const std::vector<Sample>& deficient() const;
  uint64_t revision() const { return rev_; }

 private:
  struct Grid {
    std::string id;
    Vec3 o, u, v, n;
    int i0 = 0, j0 = 0, nx = 0, ny = 0;
    std::vector<CellStat> cells;
    std::vector<uint8_t> valid;
    Vec3 center(int i, int j) const;  // cell centre by local index
  };
  Grid* find(const std::string& id);
  const Grid* find(const std::string& id) const;
  void resize(Grid& g, double u0, double u1, double v0, double v1);
  void rebuild() const;

  GuideParams p_;
  std::vector<Grid> grids_;  // sorted by plane id (deterministic)
  uint64_t rev_ = 0;
  mutable uint64_t built_rev_ = ~0ull;
  mutable std::vector<Sample> deficient_, all_;
};

}  // namespace capturekit::guide
