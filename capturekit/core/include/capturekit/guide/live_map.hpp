#pragma once
// LiveMap (Phase 8, stage 1): the gravity-aligned room model built from AR planes and poses.
// Planes with the same id replace each other (AR planes grow); different ids that describe the same
// surface are merged into one canonical plane. Tier B input (planes + sparse points); with no planes
// the map stays empty (tier C, pose-only) and guidance falls back to sweeping.
#include <map>
#include <string>
#include <vector>

#include "capturekit/bundle/types.hpp"
#include "capturekit/guide/types.hpp"

namespace capturekit::guide {

struct Plane {
  std::string id, kind;  // kind: floor | wall | ceiling | table | other
  Vec3 c, n, u, v;       // centre, normal, in-plane axes (v = n x u); extents are FULL widths
  double eu = 0, ev = 0;
  int64_t t_first = 0, t_last = 0;
  bool surface() const { return kind == "floor" || kind == "wall" || kind == "ceiling"; }
};

struct Aabb {
  Vec3 lo{1e9, 1e9, 1e9}, hi{-1e9, -1e9, -1e9};
  void add(const Vec3& p) { lo = lo.cwiseMin(p); hi = hi.cwiseMax(p); }
  bool valid() const { return lo.x() <= hi.x(); }
};

class LiveMap {
 public:
  /// Returns the canonical id the record was stored under (changes on merge).
  std::string add_plane(const bundle::PlaneRecord& p);
  void add_camera(const Vec3& p) { cam_.add(p); ++n_cam_; }
  int version() const { return version_; }  // bumps whenever any plane geometry changes

  const std::map<std::string, Plane>& planes() const { return planes_; }
  size_t surface_count() const;
  bool has_floor() const { return floor_ok_; }
  double floor_y() const { return floor_y_; }  // lowest big horizontal plane, else camera prior
  bool has_ceiling() const { return ceil_ok_; }
  double ceiling_y() const { return ceil_y_; }
  /// Bounding box of surface planes (walls/floor/ceiling) in world coordinates.
  Aabb extents() const;
  const Aabb& camera_bounds() const { return cam_; }

 private:
  void refresh_levels();
  std::map<std::string, Plane> planes_;
  std::map<std::string, std::string> alias_;
  Aabb cam_;
  size_t n_cam_ = 0;
  int version_ = 0;
  bool floor_ok_ = false, ceil_ok_ = false;
  double floor_y_ = 0, ceil_y_ = 0;
};

}  // namespace capturekit::guide
