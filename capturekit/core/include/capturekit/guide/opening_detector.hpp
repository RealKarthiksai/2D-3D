#pragma once
// OpeningDetector (Phase 9, stage 2): doors and windows from geometry only. Per wall plane a 10 cm raster
// (along the wall x height above the floor) counts sparse points ON the plane (solid) and points seen
// THROUGH it (behind the wall, on the far side from the cameras). Single empty cells are closed when most
// neighbours are solid (sparse sampling); the remaining empty regions that are bounded by solid cells are
// candidates:
//   door   = touches the floor, 0.7-1.2 m wide, >= 1.9 m high
//   window = bounded on all sides, sill 0.6-1.2 m above the floor
// Each candidate carries a confidence from its solid surround, its rectangularity and through-points.
// Pure and deterministic. Thresholds are synthetic starting guesses (Gate 0, docs/STATUS.md section 6).
#include <vector>

#include "capturekit/guide/live_map.hpp"
#include "capturekit/guide/point_store.hpp"
#include "capturekit/guide/understanding.hpp"

namespace capturekit::guide {

struct OpeningParams {
  double cell = 0.10;            // raster cell (m)
  double on_plane_tol = 0.06;    // |distance to plane| for a solid hit
  double through_min = 0.15, through_max = 1.5;  // behind-the-wall band for through-points
  double floor_snap = 0.3;       // wall bottom within this of floor_y: the raster starts at the floor
  int close_min_neighbours = 5;  // an empty cell with >= this many solid 8-neighbours counts as solid
  double door_min_w = 0.7, door_max_w = 1.2, door_min_h = 1.9;
  double win_sill_min = 0.6, win_sill_max = 1.2, win_min_w = 0.4, win_max_w = 2.5, win_min_h = 0.4;
  double min_rect = 0.7;         // component cells / bounding-box cells
  double min_ring_fill = 0.6;    // solid fraction of the one-cell ring around the candidate
  double min_conf = 0.5;
  double min_wall_w = 1.0, min_wall_h = 1.5;  // smaller wall planes are not rastered
};

class OpeningDetector {
 public:
  explicit OpeningDetector(const OpeningParams& p = {}) : p_(p) {}
  const OpeningParams& params() const { return p_; }

  /// Openings of one wall plane. The platform normal faces the room; it is flipped only when `room_side` (the
  /// camera centre) lies clearly (> 0.5 m) behind it. Points beyond the wall count as "through".
  std::vector<Opening> detect_wall(const Plane& wall, bool has_floor, double floor_y, const Vec3& room_side,
                                   const SparsePointStore& pts) const;
  /// All wall planes of the map (kind == "wall"), in plane-id order. Opening::wall stays empty (the shell
  /// attaches stable ids later).
  std::vector<Opening> detect(const LiveMap& map, const SparsePointStore& pts) const;

 private:
  OpeningParams p_;
};

}  // namespace capturekit::guide
