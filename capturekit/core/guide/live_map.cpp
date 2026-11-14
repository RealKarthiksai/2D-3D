#include "capturekit/guide/live_map.hpp"

#include <algorithm>
#include <cmath>

namespace capturekit::guide {

namespace {
Plane from_record(const bundle::PlaneRecord& r) {
  Plane p;
  p.id = r.id;
  p.kind = r.kind;
  p.c = {r.center[0], r.center[1], r.center[2]};
  p.n = Vec3(r.normal[0], r.normal[1], r.normal[2]).normalized();
  Vec3 u(r.axis_u[0], r.axis_u[1], r.axis_u[2]);
  u -= p.n * u.dot(p.n);  // make u orthogonal to n
  p.u = u.norm() > 1e-9 ? u.normalized() : p.n.unitOrthogonal();
  p.v = p.n.cross(p.u);
  p.eu = r.extent_u;
  p.ev = r.extent_v;
  p.t_first = p.t_last = r.t_ns;
  return p;
}

/// Gap between the two in-plane rectangles of a and b, measured in a's frame (<= 0 when they overlap).
double rect_gap(const Plane& a, const Plane& b) {
  Vec3 d = b.c - a.c;
  double du = d.dot(a.u), dv = d.dot(a.v);
  // b's extent projected on a's axes (axes parallel up to small rotation)
  double bu = 0.5 * (std::fabs(b.u.dot(a.u)) * b.eu + std::fabs(b.v.dot(a.u)) * b.ev);
  double bv = 0.5 * (std::fabs(b.u.dot(a.v)) * b.eu + std::fabs(b.v.dot(a.v)) * b.ev);
  double gu = std::fabs(du) - 0.5 * a.eu - bu, gv = std::fabs(dv) - 0.5 * a.ev - bv;
  return std::max(gu, gv);
}

void unite(Plane& a, const Plane& b) {
  // Union bounding rectangle in a's frame.
  Vec3 d = b.c - a.c;
  double du = d.dot(a.u), dv = d.dot(a.v);
  double bu = 0.5 * (std::fabs(b.u.dot(a.u)) * b.eu + std::fabs(b.v.dot(a.u)) * b.ev);
  double bv = 0.5 * (std::fabs(b.u.dot(a.v)) * b.eu + std::fabs(b.v.dot(a.v)) * b.ev);
  double u0 = std::min(-0.5 * a.eu, du - bu), u1 = std::max(0.5 * a.eu, du + bu);
  double v0 = std::min(-0.5 * a.ev, dv - bv), v1 = std::max(0.5 * a.ev, dv + bv);
  a.c += a.u * (0.5 * (u0 + u1)) + a.v * (0.5 * (v0 + v1));
  a.eu = u1 - u0;
  a.ev = v1 - v0;
  a.t_last = std::max(a.t_last, b.t_last);
}
}  // namespace

std::string LiveMap::add_plane(const bundle::PlaneRecord& r) {
  Plane np = from_record(r);
  if (np.eu <= 0 || np.ev <= 0 || !np.n.allFinite() || !np.c.allFinite()) return r.id;  // ignore degenerate
  auto al = alias_.find(r.id);
  std::string key = al != alias_.end() ? al->second : r.id;
  auto it = planes_.find(key);
  if (it != planes_.end()) {  // an update of a known plane: AR planes grow, the newest geometry wins
    np.id = key;
    np.t_first = it->second.t_first;
    it->second = np;
    ++version_;
    refresh_levels();
    return key;
  }
  // New id: duplicate of an existing plane? (same kind, nearly parallel, same offset, touching)
  for (auto& [id, ex] : planes_) {
    if (ex.kind != np.kind || std::fabs(ex.n.dot(np.n)) < std::cos(deg2rad(10))) continue;
    if (std::fabs((np.c - ex.c).dot(ex.n)) > 0.10) continue;
    if (rect_gap(ex, np) > 0.30) continue;
    unite(ex, np);
    alias_[r.id] = id;
    ++version_;
    refresh_levels();
    return id;
  }
  planes_[r.id] = np;
  ++version_;
  refresh_levels();
  return r.id;
}

size_t LiveMap::surface_count() const {
  size_t n = 0;
  for (auto& [_, p] : planes_) n += p.surface();
  return n;
}

void LiveMap::refresh_levels() {
  floor_ok_ = ceil_ok_ = false;
  double best_floor = 1e9, best_ceil = -1e9;
  for (auto& [_, p] : planes_) {
    if (std::fabs(p.n.y()) < 0.9 || p.eu * p.ev < 1.0) continue;  // big horizontal planes only
    if (p.kind == "floor" && p.c.y() < best_floor) best_floor = p.c.y(), floor_ok_ = true;
    if (p.kind == "ceiling" && p.c.y() > best_ceil) best_ceil = p.c.y(), ceil_ok_ = true;
  }
  floor_y_ = floor_ok_ ? best_floor : (cam_.valid() ? cam_.lo.y() - 1.4 : 0.0);
  ceil_y_ = ceil_ok_ ? best_ceil : floor_y_ + 2.5;
}

Aabb LiveMap::extents() const {
  Aabb b;
  for (auto& [_, p] : planes_) {
    if (!p.surface()) continue;
    for (int su : {-1, 1})
      for (int sv : {-1, 1}) b.add(p.c + p.u * (0.5 * su * p.eu) + p.v * (0.5 * sv * p.ev));
  }
  return b;
}

}  // namespace capturekit::guide
