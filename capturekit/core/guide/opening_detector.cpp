#include "capturekit/guide/opening_detector.hpp"

#include <algorithm>
#include <cmath>

namespace capturekit::guide {

namespace {
double clamp01(double x) { return std::max(0.0, std::min(1.0, x)); }
}  // namespace

std::vector<Opening> OpeningDetector::detect_wall(const Plane& wall, bool has_floor, double floor_y, const Vec3& room_side,
                                                  const SparsePointStore& pts) const {
  std::vector<Opening> out;
  const OpeningParams& P = p_;
  // Horizontal wall normal facing the room (toward the cameras) and the along-wall axis.
  Vec3 n(wall.n.x(), 0, wall.n.z());
  if (!(n.norm() > 0.5) || !wall.c.allFinite()) return out;
  n.normalize();
  if ((room_side - wall.c).dot(n) < -0.5) n = -n;  // trust the platform normal unless the cameras are clearly behind it
  const Vec3 hz = n.cross(Vec3::UnitY()).normalized();
  const double half_w = 0.5 * (wall.eu * std::fabs(wall.u.dot(hz)) + wall.ev * std::fabs(wall.v.dot(hz)));
  const double half_h = 0.5 * (wall.eu * std::fabs(wall.u.y()) + wall.ev * std::fabs(wall.v.y()));
  if (2 * half_w < P.min_wall_w || 2 * half_h < P.min_wall_h) return out;
  const double y_top = wall.c.y() + half_h;
  double y_lo = wall.c.y() - half_h;
  const bool at_floor = has_floor && y_lo < floor_y + P.floor_snap;
  if (at_floor) y_lo = floor_y;
  const double ref_floor = has_floor ? floor_y : y_lo;
  const int nu = int(std::lround(2 * half_w / P.cell)), nv = int(std::lround((y_top - y_lo) / P.cell));
  if (nu < 5 || nv < 5) return out;
  const auto idx = [nu](int i, int j) { return size_t(j) * size_t(nu) + size_t(i); };

  std::vector<uint32_t> solid(size_t(nu) * size_t(nv), 0), through(solid.size(), 0);
  pts.for_each([&](const SparsePointStore::Voxel& vx) {
    const Vec3 p = vx.mean();
    const Vec3 d = p - wall.c;
    const double dn = d.dot(n);
    const int i = int(std::floor((d.dot(hz) + half_w) / P.cell)), j = int(std::floor((p.y() - y_lo) / P.cell));
    if (i < 0 || i >= nu || j < 0 || j >= nv) return;
    if (std::fabs(dn) <= P.on_plane_tol) ++solid[idx(i, j)];
    else if (dn < -P.through_min && dn > -P.through_max) ++through[idx(i, j)];
  });

  // Close isolated holes left by sparse sampling (computed on the raw grid, one pass).
  std::vector<uint8_t> full(solid.size(), 0);
  for (int j = 0; j < nv; ++j)
    for (int i = 0; i < nu; ++i) {
      if (solid[idx(i, j)]) { full[idx(i, j)] = 1; continue; }
      int k = 0;
      for (int dj = -1; dj <= 1; ++dj)
        for (int di = -1; di <= 1; ++di) {
          int a = i + di, b = j + dj;
          if ((di || dj) && a >= 0 && a < nu && b >= 0 && b < nv && solid[idx(a, b)]) ++k;
        }
      full[idx(i, j)] = k >= P.close_min_neighbours;
    }

  // 4-connected empty components, scanned in row-major order (deterministic).
  std::vector<int> comp(solid.size(), -1);
  std::vector<std::pair<int, int>> stack, cells;
  int ncomp = 0;
  for (int j0 = 0; j0 < nv; ++j0)
    for (int i0 = 0; i0 < nu; ++i0) {
      if (full[idx(i0, j0)] || comp[idx(i0, j0)] >= 0) continue;
      const int c = ncomp++;
      cells.clear();
      stack.assign(1, {i0, j0});
      comp[idx(i0, j0)] = c;
      int imin = i0, imax = i0, jmin = j0, jmax = j0;
      uint32_t thr = 0;
      while (!stack.empty()) {
        auto [i, j] = stack.back();
        stack.pop_back();
        cells.push_back({i, j});
        imin = std::min(imin, i), imax = std::max(imax, i), jmin = std::min(jmin, j), jmax = std::max(jmax, j);
        thr += through[idx(i, j)];
        const int nb[4][2] = {{i - 1, j}, {i + 1, j}, {i, j - 1}, {i, j + 1}};
        for (auto& q : nb) {
          int a = q[0], b = q[1];
          if (a < 0 || a >= nu || b < 0 || b >= nv || full[idx(a, b)] || comp[idx(a, b)] >= 0) continue;
          comp[idx(a, b)] = c;
          stack.push_back({a, b});
        }
      }
      // Unbounded regions (touching the side or top of the observed wall) are unknown, not openings.
      if (imin == 0 || imax == nu - 1 || jmax == nv - 1) continue;
      const bool floor_touch = jmin == 0;
      if (floor_touch && !at_floor) continue;
      const double w = (imax - imin + 1) * P.cell, h = (jmax - jmin + 1) * P.cell;
      const double bottom = y_lo + jmin * P.cell - ref_floor, top = y_lo + (jmax + 1) * P.cell - ref_floor;
      const double rect = double(cells.size()) / double((imax - imin + 1) * (jmax - jmin + 1));
      if (rect < P.min_rect) continue;
      OpeningKind kind;
      if (floor_touch) {
        if (w < P.door_min_w || w > P.door_max_w || h < P.door_min_h) continue;
        kind = OpeningKind::Door;
      } else {
        if (bottom < P.win_sill_min || bottom > P.win_sill_max || w < P.win_min_w || w > P.win_max_w || h < P.win_min_h)
          continue;
        kind = OpeningKind::Window;
      }
      // Solid surround: one-cell ring (left, right, top; plus bottom for windows) on the raw grid.
      int ring = 0, ring_solid = 0;
      auto probe = [&](int a, int b) {
        if (a < 0 || a >= nu || b < 0 || b >= nv) return;
        ++ring;
        ring_solid += solid[idx(a, b)] > 0;
      };
      for (int i = imin; i <= imax; ++i) {
        probe(i, jmax + 1);
        if (!floor_touch) probe(i, jmin - 1);
      }
      for (int j = jmin; j <= jmax; ++j) probe(imin - 1, j), probe(imax + 1, j);
      const double ring_fill = ring ? double(ring_solid) / ring : 0.0;
      if (ring_fill < P.min_ring_fill) continue;
      const double thr_score = clamp01(double(thr) / 3.0);
      const double conf = clamp01(0.5 * ring_fill + 0.3 * rect + 0.2 * thr_score);
      if (conf < P.min_conf) continue;
      const double s0 = -half_w + imin * P.cell, s1 = -half_w + (imax + 1) * P.cell;
      const Vec3 a = wall.c + hz * s0, b = wall.c + hz * s1;
      Opening o;
      o.kind = kind;
      o.x0 = a.x(), o.z0 = a.z(), o.x1 = b.x(), o.z1 = b.z();
      o.y0 = bottom, o.y1 = top;
      o.conf = conf;
      out.push_back(o);
    }
  return out;
}

std::vector<Opening> OpeningDetector::detect(const LiveMap& map, const SparsePointStore& pts) const {
  std::vector<Opening> out;
  const Aabb& cb = map.camera_bounds();
  if (!cb.valid() || pts.size() == 0) return out;
  const Vec3 room_side = 0.5 * (cb.lo + cb.hi);
  for (auto& [id, pl] : map.planes()) {
    if (pl.kind != "wall") continue;
    auto v = detect_wall(pl, map.has_floor(), map.floor_y(), room_side, pts);
    out.insert(out.end(), v.begin(), v.end());
  }
  return out;
}

}  // namespace capturekit::guide
