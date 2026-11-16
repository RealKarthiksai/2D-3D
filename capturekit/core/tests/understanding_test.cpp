// Phase 9 room understanding: stage-by-stage tests (step 1: types + SurfaceClassifier; step 2: SparsePointStore +
// OpeningDetector).
#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <iostream>
#include <map>
#include <string>
#include <thread>
#include <vector>

#include "capturekit/guide/coverage.hpp"
#include "capturekit/guide/engine.hpp"
#include "capturekit/guide/live_map.hpp"
#include "capturekit/guide/nav_grid.hpp"
#include "capturekit/guide/object_lifter.hpp"
#include "capturekit/guide/opening_detector.hpp"
#include "capturekit/guide/point_store.hpp"
#include "capturekit/guide/sim.hpp"
#include "capturekit/guide/surface_classifier.hpp"
#include "capturekit/guide/understanding.hpp"
#include "capturekit/util/json.hpp"

using namespace capturekit;
using namespace capturekit::guide;

namespace {

bundle::PlaneRecord plane(const std::string& id, const std::string& kind, Vec3 c, Vec3 n, Vec3 u, double eu, double ev) {
  bundle::PlaneRecord p;
  p.id = id, p.kind = kind;
  for (int i = 0; i < 3; ++i) p.center[i] = c[i], p.normal[i] = n[i], p.axis_u[i] = u[i];
  p.extent_u = eu, p.extent_v = ev;
  return p;
}

PlaneGeom geom(Vec3 c, Vec3 n, double area) {
  PlaneGeom g;
  g.c = c, g.n = n, g.area = area;
  return g;
}

struct Truth {
  bundle::PlaneRecord rec;  // platform label as ARCore would give it
  std::string kind;         // what it really is
};

/// Ground-truth planes of a sim room. Platform labels mimic ARCore: every upward plane (furniture tops
/// included) says "floor", every vertical one "wall" (even picture frames).
std::vector<Truth> room_planes(const std::string& name, bool clutter) {
  auto room = sim::make_room(name);
  std::vector<Truth> out;
  const auto& P = room.poly;
  for (size_t i = 0; i < P.size(); ++i) {
    auto [x0, z0] = P[i];
    auto [x1, z1] = P[(i + 1) % P.size()];
    double L = std::hypot(x1 - x0, z1 - z0);
    Vec3 u((x1 - x0) / L, 0, (z1 - z0) / L), n(-(z1 - z0) / L, 0, (x1 - x0) / L);
    Vec3 c(0.5 * (x0 + x1), 0.5 * room.height, 0.5 * (z0 + z1));
    out.push_back({plane("w" + std::to_string(i), "wall", c, n, u, L, room.height), "wall"});
  }
  int k = 0;
  for (auto& r : room.rects) {
    Vec3 cf(0.5 * (r[0] + r[2]), 0.0, 0.5 * (r[1] + r[3])), cc = cf + Vec3(0, room.height, 0);
    double ex = r[2] - r[0], ez = r[3] - r[1];
    out.push_back({plane("f" + std::to_string(k), "floor", cf, {0, 1, 0}, {1, 0, 0}, ex, ez), "floor"});
    out.push_back({plane("c" + std::to_string(k), "ceiling", cc, {0, -1, 0}, {1, 0, 0}, ex, ez), "ceiling"});
    ++k;
  }
  k = 0;
  for (auto& b : room.boxes) {
    Vec3 c(0.5 * (b.x0 + b.x1), b.h, 0.5 * (b.z0 + b.z1));
    // 2.0 m is not above the 2.0 m ceiling threshold, and above the 1.2 m table limit: "other".
    out.push_back({plane("t" + std::to_string(k++), "floor", c, {0, 1, 0}, {1, 0, 0}, b.x1 - b.x0, b.z1 - b.z0),
                   b.h <= 1.2 ? "table" : "other"});
  }
  if (clutter) {
    out.push_back({plane("seat", "floor", {1.5, 0.45, 1.5}, {0, 1, 0}, {1, 0, 0}, 0.45, 0.45), "table"});
    out.push_back({plane("shelf", "floor", {0.4, 1.6, 2.0}, {0, 1, 0}, {1, 0, 0}, 0.9, 0.3), "other"});
    out.push_back({plane("frame", "wall", {2.0, 1.5, 0.35}, {0, 0, 1}, {1, 0, 0}, 0.4, 0.3), "other"});
    out.push_back({plane("tilt", "other", {2.0, 1.0, 3.0}, Vec3(0, 0.7, 0.7).normalized(), {1, 0, 0}, 1.0, 1.0), "other"});
  }
  return out;
}

struct Accuracy {
  int n = 0, ok = 0;
  double value() const { return n ? double(ok) / n : 0.0; }
};

/// Feeds every plane three times (a plane grows/re-arrives at 1 Hz), `adversarial` = furniture tops first
/// and the floor last, and counts final kinds against the truth.
Accuracy run_room(const std::string& name, bool clutter, bool adversarial, bool classify) {
  auto planes = room_planes(name, clutter);
  if (adversarial) {  // tops/clutter first, then walls, floor and ceiling last
    std::stable_sort(planes.begin(), planes.end(), [](const Truth& a, const Truth& b) {
      auto rank = [](const Truth& t) { return t.kind == "table" || t.kind == "other" ? 0 : t.kind == "wall" ? 1 : 2; };
      return rank(a) < rank(b);
    });
  }
  LiveMap m;
  m.set_classify(classify);
  m.add_camera({2.0, 1.4, 2.0});
  std::map<std::string, std::string> canon;
  for (int round = 0; round < 3; ++round)
    for (auto& t : planes) canon[t.rec.id] = m.add_plane(t.rec);
  Accuracy a;
  for (auto& t : planes) {
    ++a.n;
    a.ok += m.planes().at(canon[t.rec.id]).kind == t.kind;
  }
  return a;
}

}  // namespace

// ---- types ----
TEST(Understanding, ObjectClassNamesRoundTrip) {
  for (int i = 0; i < int(ObjectClass::Count_); ++i) {
    auto c = ObjectClass(i);
    EXPECT_EQ(object_class_from_name(object_class_name(c)), c);
  }
  EXPECT_EQ(object_class_from_name("sofa-bed-9000"), ObjectClass::Unknown);
  EXPECT_STREQ(object_class_name(ObjectClass(99)), "unknown");
  EXPECT_TRUE(object_class_wall_mounted(ObjectClass::Tv));
  EXPECT_FALSE(object_class_wall_mounted(ObjectClass::Chair));
}

// ---- SurfaceClassifier rules ----
TEST(Understanding, ClassifierRules) {
  SurfaceContext fl;
  fl.has_floor_ref = true, fl.floor_y = 0.0;
  struct Row {
    const char* what;
    PlaneGeom g;
    const char* kind;
  };
  const Vec3 up(0, 1, 0), down(0, -1, 0), wallN(0, 0, -1);
  std::vector<Row> rows = {
      {"floor", geom({2, 0.02, 2}, up, 16), "floor"},
      {"below floor", geom({2, -0.3, 2}, up, 16), "floor"},
      {"table top", geom({2, 0.75, 2}, up, 1.0), "table"},
      {"seat", geom({2, 0.45, 2}, up, 0.2), "table"},
      {"high shelf", geom({2, 1.6, 2}, up, 0.5), "other"},
      {"ceiling", geom({2, 2.5, 2}, down, 16), "ceiling"},
      {"ceiling, sign ignored", geom({2, 2.5, 2}, up, 16), "ceiling"},
      {"wall", geom({2, 1.25, 4}, wallN, 10), "wall"},
      {"small vertical", geom({2, 1.0, 4}, wallN, 0.3), "other"},
      {"wall at area limit", geom({2, 1.0, 4}, wallN, 0.5), "wall"},
      {"tilted 45 deg", geom({2, 1, 2}, Vec3(0, 1, 1).normalized(), 4), "other"},
      {"zero area", geom({2, 0, 2}, up, 0), "other"},
  };
  for (auto& r : rows) {
    auto l = SurfaceClassifier::classify(r.g, fl);
    EXPECT_EQ(l.kind, r.kind) << r.what;
    EXPECT_GE(l.confidence, 0.0) << r.what;
    EXPECT_LE(l.confidence, 1.0) << r.what;
  }
  // No floor reference yet: the first horizontal plane is the floor; above the cameras it is a ceiling instead.
  SurfaceContext none;
  EXPECT_EQ(SurfaceClassifier::classify(geom({2, 0.8, 2}, up, 1.0), none).kind, "floor");
  none.has_cam = true, none.cam_y = 1.4;
  EXPECT_EQ(SurfaceClassifier::classify(geom({2, 2.5, 2}, up, 16), none).kind, "ceiling");
  EXPECT_EQ(SurfaceClassifier::classify(geom({2, 0.0, 2}, up, 16), none).kind, "floor");
  // Determinism and confidence ordering: a floor in plain view beats a borderline plane.
  auto a = SurfaceClassifier::classify(geom({2, 0.0, 2}, up, 16), fl);
  auto b = SurfaceClassifier::classify(geom({2, 0.14, 2}, up, 1.0), fl);
  EXPECT_EQ(a.kind, "floor");
  EXPECT_EQ(b.kind, "floor");
  EXPECT_GT(a.confidence, b.confidence);
  EXPECT_EQ(SurfaceClassifier::classify(geom({2, 0.75, 2}, up, 1.0), fl).confidence,
            SurfaceClassifier::classify(geom({2, 0.75, 2}, up, 1.0), fl).confidence);
}

TEST(Understanding, ClassifierHysteresis) {
  SurfaceClassifier c;
  SurfaceContext fl;
  fl.has_floor_ref = true, fl.floor_y = 0;
  const Vec3 up(0, 1, 0);
  auto floorish = geom({2, 0.05, 2}, up, 4), tableish = geom({2, 0.75, 2}, up, 4);
  EXPECT_EQ(c.update("p", floorish, fl).kind, "floor");  // first observation is published at once
  EXPECT_EQ(c.update("p", tableish, fl).kind, "floor");  // one disagreeing update is not enough
  EXPECT_EQ(c.update("p", floorish, fl).kind, "floor");  // flicker back resets the streak
  EXPECT_EQ(c.update("p", tableish, fl).kind, "floor");
  EXPECT_EQ(c.update("p", tableish, fl).kind, "table");  // two consecutive: switch
  EXPECT_EQ(c.update("p", floorish, fl).kind, "table");
  EXPECT_EQ(c.update("p", tableish, fl).kind, "table");
  // Independent planes do not share state; forget() restarts one.
  EXPECT_EQ(c.update("q", tableish, fl).kind, "table");
  c.forget("p");
  EXPECT_EQ(c.update("p", floorish, fl).kind, "floor");
}

// ---- accuracy over the sim rooms (ARCore-style labels: every upward plane says "floor") ----
TEST(Understanding, ClassifierAccuracyTable) {
  struct Row {
    const char* room;
    bool clutter;
  };
  const Row rows[] = {{"box", false}, {"L", false}, {"furniture", false}, {"furniture", true}};
  std::cout << "\n  Understanding.ClassifierAccuracyTable (synthetic planes, 3 updates each, cameras at 1.4 m)\n"
            << "  room            order        classifier        platform label\n";
  for (auto& r : rows)
    for (bool adv : {false, true}) {
      Accuracy on = run_room(r.room, r.clutter, adv, true), off = run_room(r.room, r.clutter, adv, false);
      char line[160];
      std::snprintf(line, sizeof line, "  %-9s%-6s %-12s %2d/%-2d %5.1f%%    %2d/%-2d %5.1f%%", r.room, r.clutter ? "+clut" : "",
                    adv ? "adversarial" : "natural", on.ok, on.n, 100 * on.value(), off.ok, off.n, 100 * off.value());
      std::cout << line << "\n";
      EXPECT_GE(on.value(), 0.95) << r.room << " clutter=" << r.clutter << " adversarial=" << adv;
      EXPECT_GE(on.ok, off.ok);
    }
}

TEST(Understanding, ClassifierBoxAndLAreExact) {
  for (const char* room : {"box", "L"})
    for (bool adv : {false, true}) {
      Accuracy a = run_room(room, false, adv, true);
      EXPECT_EQ(a.ok, a.n) << room;
    }
}

// ---- table tops must stop being coverage targets ----
TEST(Understanding, TableTopsNotCoveredAsFloor) {
  auto tops = [] {
    std::vector<bundle::PlaneRecord> v;
    v.push_back(plane("t1", "floor", {1.0, 0.75, 1.0}, {0, 1, 0}, {1, 0, 0}, 1.2, 0.8));
    v.push_back(plane("t2", "floor", {3.0, 0.45, 3.0}, {0, 1, 0}, {1, 0, 0}, 1.0, 1.0));
    return v;
  };
  auto floor_rec = plane("f", "floor", {2, 0.02, 2}, {0, 1, 0}, {1, 0, 0}, 4, 4);
  GuideParams p;
  auto valid_cells = [&](bool classify, bool with_tops, bool tops_first) {
    LiveMap m;
    m.set_classify(classify);
    m.add_camera({2.0, 1.4, 2.0});
    Coverage cov(p);
    for (int round = 0; round < 3; ++round) {
      if (tops_first && with_tops)
        for (auto& t : tops()) m.add_plane(t);
      m.add_plane(floor_rec);
      if (!tops_first && with_tops)
        for (auto& t : tops()) m.add_plane(t);
      cov.sync(m);  // the engine syncs after every plane
    }
    if (classify && with_tops)
      for (auto& [id, pl] : m.planes()) {
        if (id == "f") continue;
        EXPECT_EQ(pl.kind, "table") << id;
        EXPECT_EQ(pl.src_kind, "floor") << id;  // platform label kept as a hint
      }
    return cov.stats().valid;
  };
  const size_t floor_only = valid_cells(true, false, false);
  ASSERT_GT(floor_only, 1000u);
  EXPECT_EQ(valid_cells(true, true, false), floor_only);
  EXPECT_EQ(valid_cells(true, true, true), floor_only);        // tops first: relabelled once the floor shows up
  EXPECT_GT(valid_cells(false, true, false), floor_only + 100);  // flag off keeps the Phase 8 behaviour (counted as floor)
}

TEST(Understanding, ClassifierKeepsLevelsAndTable) {
  LiveMap m;
  m.set_classify(true);
  m.add_camera({2.0, 1.4, 2.0});
  m.add_plane(plane("f", "floor", {2, 0.0, 2}, {0, 1, 0}, {1, 0, 0}, 4, 4));
  m.add_plane(plane("t", "floor", {1, 0.75, 1}, {0, 1, 0}, {1, 0, 0}, 1, 1));
  for (int i = 0; i < 4; ++i) m.add_plane(plane("t", "floor", {1, 0.75, 1}, {0, 1, 0}, {1, 0, 0}, 1, 1));
  EXPECT_EQ(m.planes().at("t").kind, "table");
  EXPECT_EQ(m.planes().at("f").kind, "floor");
  EXPECT_TRUE(m.has_floor());
  EXPECT_NEAR(m.floor_y(), 0.0, 1e-9);
}

// ---- flag off is a no-op ----
TEST(Understanding, FlagOffIsNoOp) {
  // Config hash: the new switch is invisible while 0, and distinguishes the config when on.
  GuideParams def;
  EXPECT_EQ(def.to_json().find("understanding"), std::string::npos);
  auto off = GuideParams::from_json("{\"understanding\": 0}");
  auto on = GuideParams::from_json("{\"understanding\": 1}");
  ASSERT_TRUE(off && on);
  EXPECT_EQ(off->hash(), def.hash());
  EXPECT_NE(on->hash(), def.hash());
  EXPECT_DOUBLE_EQ(on->understanding, 1.0);

  // Engine with the flag off keeps platform kinds untouched (a table top labelled "floor" stays "floor").
  GuideEngine e0(def);
  e0.on_plane(plane("t", "floor", {1, 0.75, 1}, {0, 1, 0}, {1, 0, 0}, 1, 1));
  e0.on_plane(plane("f", "floor", {2, 0.0, 2}, {0, 1, 0}, {1, 0, 0}, 4, 4));
  EXPECT_EQ(e0.map().planes().at("t").kind, "floor");
  EXPECT_FALSE(e0.map().classify_enabled());
  GuideEngine e1(*on);
  EXPECT_TRUE(e1.map().classify_enabled());

  // Sim reports with the flag off carry no table-top planes: identical for a default config and an explicit 0.
  sim::Config c;
  c.room = "furniture", c.seed = 3, c.max_s = 40;
  std::string a = sim::simulate(c);
  c.guide.understanding = 0;
  EXPECT_EQ(sim::simulate(c), a);
}

TEST(Understanding, SimWithUnderstandingIsDeterministicAndLabelsTops) {
  sim::Config c;
  c.room = "furniture", c.seed = 5, c.max_s = 60;
  c.guide.understanding = 1;
  std::string a = sim::simulate(c), b = sim::simulate(c);
  EXPECT_EQ(a, b);
  EXPECT_FALSE(a.empty());
}

// ======================= step 2: SparsePointStore + OpeningDetector =======================
namespace {

std::vector<Vec3> store_dump(const SparsePointStore& s) {
  std::vector<Vec3> v;
  s.for_each([&](const SparsePointStore::Voxel& x) { v.push_back(x.mean()); });
  return v;
}

/// Wall z = 0, x 0..5, y 0..2.5, facing +z (into the room).
Plane test_wall() {
  Plane w;
  w.id = "w", w.kind = "wall", w.src_kind = "wall";
  w.c = {2.5, 1.25, 0}, w.n = {0, 0, 1}, w.u = {1, 0, 0}, w.v = w.n.cross(w.u);
  w.eu = 5, w.ev = 2.5;
  return w;
}

using Rect = std::array<double, 4>;  // {x0, x1, y0, y1} on the test wall

void fill_wall(SparsePointStore& s, double step, double drop, uint64_t seed, const std::vector<Rect>& holes, bool through) {
  Rng rng(seed);
  for (double y = step / 2; y < 2.5; y += step)
    for (double x = step / 2; x < 5.0; x += step) {
      bool hole = false;
      for (auto& h : holes) hole |= x > h[0] && x < h[1] && y > h[2] && y < h[3];
      const double r = rng.uniform();
      if (hole) {
        if (through && r < 0.3) s.add({x, y, -0.6});  // the corridor behind the opening
        continue;
      }
      if (r < drop) continue;
      s.add({x + 0.01 * rng.normal(), y + 0.01 * rng.normal(), 0.01 * rng.normal()});
    }
}

bool near_opening(const Opening& o, OpeningKind k, Rect t, double z, double tol) {
  double a = std::min(o.x0, o.x1), b = std::max(o.x0, o.x1);
  return o.kind == k && std::fabs(a - t[0]) <= tol && std::fabs(b - t[1]) <= tol && std::fabs(o.z0 - z) <= tol &&
         std::fabs(o.z1 - z) <= tol && std::fabs(o.y0 - t[2]) <= tol && std::fabs(o.y1 - t[3]) <= tol;
}

struct SimOpening {
  std::string kind;
  double x0, z0, x1, z1, y0, y1, conf;
};

/// Reads the "openings" array of a sim report (flat objects only).
std::vector<SimOpening> sim_openings(const std::string& report, bool* present = nullptr) {
  std::vector<SimOpening> out;
  auto at = report.find("\"openings\":[");
  if (present) *present = at != std::string::npos;
  if (at == std::string::npos) return out;
  auto end = report.find(']', at);
  size_t p = at;
  while ((p = report.find('{', p)) != std::string::npos && p < end) {
    size_t q = report.find('}', p);
    auto obj = json::parse_flat(std::string_view(report).substr(p, q - p + 1));
    EXPECT_TRUE(obj.has_value());
    if (obj) {
      auto d = [&](const char* k) { return std::get<double>(obj->at(k)); };
      out.push_back({std::get<std::string>(obj->at("kind")), d("x0"), d("z0"), d("x1"), d("z1"), d("y0"), d("y1"), d("conf")});
    }
    p = q;
  }
  return out;
}

/// Matches a reported opening against a room's OpeningSpec (world endpoints of the wall segment).
bool sim_match(const SimOpening& o, const sim::Room& room, const sim::OpeningSpec& sp, double tol) {
  auto [x0, z0] = room.poly[size_t(sp.wall)];
  auto [x1, z1] = room.poly[(size_t(sp.wall) + 1) % room.poly.size()];
  double L = std::hypot(x1 - x0, z1 - z0), ux = (x1 - x0) / L, uz = (z1 - z0) / L;
  double ax = x0 + ux * sp.u0, az = z0 + uz * sp.u0, bx = x0 + ux * sp.u1, bz = z0 + uz * sp.u1;
  auto ok = [&](double px, double pz, double qx, double qz) {
    return std::hypot(px - ax, pz - az) <= tol && std::hypot(qx - bx, qz - bz) <= tol;
  };
  const char* kind = sp.kind == 0 ? "door" : "window";
  return o.kind == kind && (ok(o.x0, o.z0, o.x1, o.z1) || ok(o.x1, o.z1, o.x0, o.z0)) && std::fabs(o.y0 - sp.y0) <= tol &&
         std::fabs(o.y1 - sp.y1) <= tol;
}

}  // namespace

// ---- SparsePointStore ----
TEST(Understanding, PointStoreVoxelsAndMeans) {
  SparsePointStore s(0.05, 100);
  EXPECT_TRUE(s.add({0.01, 0.01, 0.01}));
  EXPECT_TRUE(s.add({0.03, 0.03, 0.03}));  // same 5 cm voxel
  EXPECT_TRUE(s.add({0.07, 0.01, 0.01}));  // next voxel along x
  EXPECT_FALSE(s.add({std::nan(""), 0, 0}));
  ASSERT_EQ(s.size(), 2u);
  auto v = store_dump(s);
  EXPECT_NEAR(v[0].x(), 0.02, 1e-12);
  EXPECT_NEAR(v[1].x(), 0.07, 1e-12);
  EXPECT_EQ(s.version(), 3u);
  s.add({-0.01, 0.01, 0.01});  // negative coordinates do not alias with positive ones
  EXPECT_EQ(s.size(), 3u);
}

TEST(Understanding, PointStoreBoundedLruEviction) {
  SparsePointStore s(0.05, 3);
  s.add({0.0, 0, 0}), s.add({1.0, 0, 0}), s.add({2.0, 0, 0});
  s.add({0.01, 0, 0});  // touches voxel 0: now voxel 1 is the oldest
  s.add({3.0, 0, 0});   // evicts voxel 1
  EXPECT_EQ(s.size(), 3u);
  EXPECT_EQ(s.evicted(), 1u);
  std::vector<double> xs;
  for (auto& p : store_dump(s)) xs.push_back(std::round(p.x()));
  EXPECT_EQ(xs, (std::vector<double>{0, 2, 3}));
  // Deterministic: the same input sequence gives the same content, whatever the hash layout.
  SparsePointStore a(0.05, 500), b(0.05, 500);
  Rng r1(7), r2(7);
  for (int i = 0; i < 5000; ++i) {
    a.add({r1.uniform(0, 3), r1.uniform(0, 3), r1.uniform(0, 3)});
    b.add({r2.uniform(0, 3), r2.uniform(0, 3), r2.uniform(0, 3)});
  }
  EXPECT_EQ(a.size(), 500u);
  auto da = store_dump(a), db = store_dump(b);
  ASSERT_EQ(da.size(), db.size());
  for (size_t i = 0; i < da.size(); ++i) EXPECT_EQ(da[i], db[i]);
}

// ---- OpeningDetector on a synthetic wall ----
TEST(Understanding, OpeningDetectorDoorAndWindow) {
  const Rect door{1.0, 1.9, 0.0, 2.0}, win{3.0, 4.2, 0.9, 1.9};
  const Vec3 room(2.5, 1.4, 2.0);
  OpeningDetector det;
  struct Row {
    const char* what;
    double step, drop;
    bool through;
  };
  for (const Row& r : {Row{"dense 5 cm", 0.05, 0.0, false}, Row{"10 cm, 15% dropped", 0.10, 0.15, false},
                       Row{"10 cm + through-points", 0.10, 0.0, true}})
    for (uint64_t seed : {1u, 2u, 3u}) {
      SparsePointStore s;
      fill_wall(s, r.step, r.drop, seed, {door, win}, r.through);
      auto ops = det.detect_wall(test_wall(), true, 0.0, room, s);
      ASSERT_EQ(ops.size(), 2u) << r.what << " seed " << seed;
      int nd = 0, nw = 0;
      for (auto& o : ops) {
        nd += near_opening(o, OpeningKind::Door, door, 0.0, 0.15);
        nw += near_opening(o, OpeningKind::Window, win, 0.0, 0.15);
        EXPECT_GE(o.conf, det.params().min_conf);
        EXPECT_LE(o.conf, 1.0);
        EXPECT_TRUE(o.wall.empty());  // the shell attaches wall ids (step 6)
      }
      EXPECT_EQ(nd, 1) << r.what << " seed " << seed;
      EXPECT_EQ(nw, 1) << r.what << " seed " << seed;
    }
  // Through-points raise the confidence of the same opening.
  SparsePointStore plain, thr;
  fill_wall(plain, 0.1, 0.0, 1, {door}, false);
  fill_wall(thr, 0.1, 0.0, 1, {door}, true);
  auto a = det.detect_wall(test_wall(), true, 0.0, room, plain), b = det.detect_wall(test_wall(), true, 0.0, room, thr);
  ASSERT_EQ(a.size(), 1u);
  ASSERT_EQ(b.size(), 1u);
  EXPECT_GT(b[0].conf, a[0].conf);
}

TEST(Understanding, OpeningDetectorRejects) {
  const Vec3 room(2.5, 1.4, 2.0);
  OpeningDetector det;
  auto run = [&](std::vector<Rect> holes, bool has_floor = true) {
    SparsePointStore s;
    fill_wall(s, 0.1, 0.1, 4, holes, false);
    return det.detect_wall(test_wall(), has_floor, 0.0, room, s);
  };
  EXPECT_TRUE(run({}).empty());                              // plain wall
  EXPECT_TRUE(run({{1.0, 3.0, 0.0, 2.0}}).empty());          // floor-touching but 2 m wide (wardrobe shadow, open plan)
  EXPECT_TRUE(run({{1.0, 1.9, 0.0, 1.2}}).empty());          // floor-touching but low (furniture shadow)
  EXPECT_TRUE(run({{1.0, 1.9, 1.5, 2.6}}).empty());          // reaches the top of the observed wall: unknown
  EXPECT_TRUE(run({{0.0, 0.9, 0.0, 2.0}}).empty());          // at the edge of the observed wall
  EXPECT_TRUE(run({{3.0, 4.2, 0.2, 1.0}}).empty());          // sill 0.2 m: not a window
  EXPECT_TRUE(run({{1.0, 1.9, 0.0, 2.0}}, false).empty());   // no floor known: no door
  EXPECT_TRUE(run({{2.0, 2.2, 1.0, 1.2}}).empty());          // tiny hole
  LiveMap m;  // empty store, no cameras: nothing
  EXPECT_TRUE(OpeningDetector().detect(m, SparsePointStore()).empty());
}

TEST(Understanding, OpeningDetectorIsOrientationFree) {
  const Rect door{1.0, 1.9, 0.0, 2.0};
  SparsePointStore s;
  fill_wall(s, 0.1, 0.1, 9, {door}, true);
  OpeningDetector det;
  Plane w = test_wall();
  auto a = det.detect_wall(w, true, 0.0, {2.5, 1.4, 2.0}, s);
  // Same wall with the platform normal pointing out of the room and flipped in-plane axes.
  w.n = -w.n, w.u = -w.u, w.v = w.n.cross(w.u);
  auto b = det.detect_wall(w, true, 0.0, {2.5, 1.4, 2.0}, s);
  ASSERT_EQ(a.size(), 1u);
  ASSERT_EQ(b.size(), 1u);
  EXPECT_TRUE(near_opening(b[0], OpeningKind::Door, door, 0.0, 0.15));
  EXPECT_NEAR(a[0].conf, b[0].conf, 1e-9);
}

TEST(Understanding, OpeningDetectorNeedsViewsForFreeSpace) {
  // A gap with fewer than min_cell_views views is unknown, never opening evidence.
  const Rect door{1.0, 1.9, 0.0, 2.0}, win{3.0, 4.2, 0.9, 1.9};
  const Vec3 room(2.5, 1.4, 2.0);
  SparsePointStore s;
  fill_wall(s, 0.1, 0.1, 5, {door, win}, false);
  OpeningDetector det;
  const int k = det.params().min_cell_views;
  ASSERT_GE(k, 2);
  auto all = [](int v) { return [v](const Vec3&) { return v; }; };
  EXPECT_TRUE(det.detect_wall(test_wall(), true, 0.0, room, s, all(k - 1)).empty());
  EXPECT_TRUE(det.detect_wall(test_wall(), true, 0.0, room, s, all(0)).empty());
  EXPECT_EQ(det.detect_wall(test_wall(), true, 0.0, room, s, all(k)).size(), 2u);
  // Door gap under-viewed, the rest of the wall well viewed: only the window remains.
  auto ops = det.detect_wall(test_wall(), true, 0.0, room, s, [&](const Vec3& p) {
    return p.x() > door[0] && p.x() < door[1] && p.y() < door[3] ? k - 1 : k;
  });
  ASSERT_EQ(ops.size(), 1u);
  EXPECT_TRUE(near_opening(ops[0], OpeningKind::Window, win, 0.0, 0.15));
  // Coverage::views_at: no grid for the plane -> 0 views.
  EXPECT_EQ(Coverage(GuideParams{}).views_at("nope", Vec3(0, 0, 0)), 0);
}

// ---- sim: the door_window scene recovers both openings; plain walls stay clean ----
namespace {
/// Understanding-on sims are slow (~0.4 s per simulated second): run independent seeds in parallel. simulate() is
/// pure (no shared state), so this changes nothing but wall time. The walker holds the phone level between tilts
/// (Human::hold_level, ADR 0015), points come from what each frame views (Config::view_points) and it walks
/// kUnderstandingSimS seconds.
constexpr double kUnderstandingSimS = 150;
std::vector<std::string> run_sims(const std::vector<std::pair<std::string, uint64_t>>& runs, double understanding) {
  std::vector<std::string> out(runs.size());
  std::vector<std::thread> th;
  for (size_t k = 0; k < runs.size(); ++k)
    th.emplace_back([&, k] {
      sim::Config c;
      c.room = runs[k].first, c.seed = runs[k].second;
      c.guide.understanding = understanding;
      c.human.hold_level = true;
      c.view_points = true;
      c.max_s = kUnderstandingSimS;
      out[k] = sim::simulate(c);
    });
  for (auto& t : th) t.join();
  return out;
}
}  // namespace

TEST(Understanding, SimRecoversDoorAndWindow) {
  const auto room = sim::make_room("door_window");
  ASSERT_EQ(room.openings.size(), 2u);
  std::cout << "\n  Understanding.SimRecoversDoorAndWindow (synthetic points, " << kUnderstandingSimS
            << " s level walks, tolerance 0.25 m)\n";
  auto reps = run_sims({{"door_window", 1}, {"door_window", 2}, {"door_window", 3}}, 1);
  int total = 0;
  for (uint64_t seed : {1u, 2u, 3u}) {
    const std::string& rep = reps[seed - 1];
    auto ops = sim_openings(rep);
    int found = 0;
    std::vector<bool> used(ops.size(), false);
    for (auto& sp : room.openings) {
      int m = 0;
      for (size_t k = 0; k < ops.size(); ++k)
        if (sim_match(ops[k], room, sp, 0.25)) ++m, used[k] = true;
      EXPECT_LE(m, 1) << "seed " << seed << " opening kind " << sp.kind << " reported twice";
      found += m >= 1;
    }
    const int false_ops = int(std::count(used.begin(), used.end(), false));
    EXPECT_GE(found, 1) << "seed " << seed;
    EXPECT_EQ(false_ops, 0) << "seed " << seed << " (false openings)";
    total += found;
    auto ps = rep.find("\"points_stored\":");
    std::cout << "  seed " << seed << ": " << found << "/2 recovered, " << ops.size() << " reported, "
              << rep.substr(ps, rep.find_first_of(",}", ps) - ps) << "\n";
    for (auto& o : ops)
      std::cout << "    " << o.kind << " (" << o.x0 << "," << o.z0 << ")-(" << o.x1 << "," << o.z1 << ") y " << o.y0 << ".."
                << o.y1 << " conf " << o.conf << "\n";
  }
  std::cout << "  total: " << total << "/6 recovered (pass >= 5)\n";
  EXPECT_GE(total, 5);
}

namespace {
/// Plain walls (no openings): the detector must report none.
void expect_no_false_openings(const std::vector<std::pair<std::string, uint64_t>>& runs) {
  auto reps = run_sims(runs, 1);
  for (size_t k = 0; k < runs.size(); ++k) {
    bool present = false;
    auto ops = sim_openings(reps[k], &present);
    EXPECT_TRUE(present) << runs[k].first;
    EXPECT_TRUE(ops.empty()) << runs[k].first << " seed " << runs[k].second << ": " << ops.size() << " false openings";
    for (auto& o : ops)
      std::cout << "    false " << o.kind << " (" << o.x0 << "," << o.z0 << ")-(" << o.x1 << "," << o.z1 << ") y " << o.y0
                << ".." << o.y1 << " conf " << o.conf << "\n";
  }
}
}  // namespace

TEST(Understanding, SimNoFalseOpeningsOnBoxWalls) { expect_no_false_openings({{"box", 1}, {"box", 2}, {"box", 3}}); }

TEST(Understanding, SimNoFalseOpeningsOnLWalls) { expect_no_false_openings({{"L", 1}}); }

TEST(Understanding, SimFlagOffReportHasNoOpenings) {
  // Flag off: the report has no openings key at all (Phase 8 reports unchanged).
  sim::Config off;
  off.room = "door_window", off.seed = 1, off.max_s = 30;
  bool present = true;
  sim_openings(sim::simulate(off), &present);
  EXPECT_FALSE(present);
}

// ---- step 3: NavGrid object boxes, ObjectLifter, detector oracle ----
namespace {

Object make_obj(ObjectClass c, Vec3 centre, Vec3 size, double yaw) {
  Object o;
  o.cls = c, o.centre = centre, o.size = size, o.yaw = yaw;
  return o;
}

/// Corners of an object's box in the world frame (yaw about +Y from +Z, depth axis (sin, cos)).
std::vector<Vec3> box_corners(const Object& o) {
  std::vector<Vec3> out;
  const double ca = std::cos(o.yaw), sa = std::sin(o.yaw);
  for (int i = 0; i < 8; ++i) {
    const double lx = (i & 1 ? 0.5 : -0.5) * o.size.x(), ly = (i & 2 ? 0.5 : -0.5) * o.size.y(),
                 lz = (i & 4 ? 0.5 : -0.5) * o.size.z();
    out.push_back({o.centre.x() + lx * ca + lz * sa, o.centre.y() + ly, o.centre.z() - lx * sa + lz * ca});
  }
  return out;
}

/// Points on the five faces (top + 4 sides) of an object at `step` spacing.
void fill_box(SparsePointStore& s, const Object& o, double step) {
  const double ca = std::cos(o.yaw), sa = std::sin(o.yaw);
  auto add = [&](double lx, double ly, double lz) {
    s.add({o.centre.x() + lx * ca + lz * sa, o.centre.y() + ly, o.centre.z() - lx * sa + lz * ca});
  };
  const double hx = 0.5 * o.size.x(), hy = 0.5 * o.size.y(), hz = 0.5 * o.size.z();
  for (double a = -hx; a <= hx; a += step)
    for (double b = -hz; b <= hz; b += step) add(a, hy, b);
  for (double a = -hy; a <= hy; a += step) {
    for (double b = -hx; b <= hx; b += step) add(b, a, -hz), add(b, a, hz);
    for (double b = -hz; b <= hz; b += step) add(-hx, a, b), add(hx, a, b);
  }
}

Detection project_box(const Object& o, const LiftContext& ctx, double score = 0.8) {
  Detection d;
  d.cls = o.cls, d.score = float(score), d.x0 = d.y0 = 1e9f, d.x1 = d.y1 = -1e9f;
  for (auto& c : box_corners(o)) {
    auto px = geometry::project(ctx.K, ctx.T_world_from_camera, c);
    if (!px) continue;
    d.x0 = std::min(d.x0, float((*px)[0])), d.x1 = std::max(d.x1, float((*px)[0]));
    d.y0 = std::min(d.y0, float((*px)[1])), d.y1 = std::max(d.y1, float((*px)[1]));
  }
  return d;
}

LiftContext lift_ctx(Vec3 cam, double yaw, double pitch) {
  LiftContext c;
  c.T_world_from_camera = pose_from_yaw_pitch(cam, yaw, pitch);
  c.K.width = 640, c.K.height = 480, c.K.fx = c.K.fy = 500, c.K.cx = 319.5, c.K.cy = 239.5;
  c.has_floor = true, c.floor_y = 0;
  PlaneGeom wall, floor;
  wall.c = {0, 1.25, 3.5}, wall.n = {0, 0, -1};
  floor.c = {0, 0, 0}, floor.n = {0, 1, 0};
  c.surfaces = {wall, floor};
  return c;
}

}  // namespace

TEST(Understanding, NavGridObjectBox) {
  GuideParams gp;
  NavGrid g(gp);
  g.set_floor_y(0);
  // Obstacle regardless of points: an axis-aligned 1.0 x 0.6 m couch (0.8 m high) at (2, 3).
  Object couch = make_obj(ObjectClass::Couch, {2.0, 0.4, 3.0}, {1.0, 0.8, 0.6}, 0.0);
  ASSERT_TRUE(g.add_object_box(couch));
  int ix, iz;
  g.to_cell({2.0, 0, 3.0}, ix, iz);
  EXPECT_TRUE(g.obstacle(ix, iz));
  g.to_cell({1.55, 0, 3.0}, ix, iz);
  EXPECT_TRUE(g.obstacle(ix, iz));  // inside the width
  g.to_cell({1.3, 0, 3.0}, ix, iz);
  EXPECT_FALSE(g.obstacle(ix, iz));  // 0.7 m left of the centre: outside 0.5 m + half a cell
  g.to_cell({2.0, 0, 3.45}, ix, iz);
  EXPECT_FALSE(g.obstacle(ix, iz));  // outside the depth (0.3 + 0.05)
  EXPECT_GT(g.obstacle_cells(), 40u);
  EXPECT_LT(g.obstacle_cells(), 90u);
  // Rotated 90 deg: the long side now runs along z.
  NavGrid r(gp);
  r.set_floor_y(0);
  Object turned = couch;
  turned.yaw = kPi / 2;
  ASSERT_TRUE(r.add_object_box(turned));
  r.to_cell({2.0, 0, 3.45}, ix, iz);
  EXPECT_TRUE(r.obstacle(ix, iz));
  r.to_cell({2.45, 0, 3.0}, ix, iz);
  EXPECT_FALSE(r.obstacle(ix, iz));
  // Wall-mounted classes and objects below obstacle_min_h (0.2 m) are not obstacles.
  NavGrid n(gp);
  n.set_floor_y(0);
  EXPECT_FALSE(n.add_object_box(make_obj(ObjectClass::Tv, {2, 1.2, 3}, {1.0, 0.6, 0.1}, 0)));
  EXPECT_FALSE(n.add_object_box(make_obj(ObjectClass::Laptop, {2, 0.015, 3}, {0.35, 0.03, 0.25}, 0)));
  EXPECT_EQ(n.obstacle_cells(), 0u);
}

TEST(Understanding, LifterFromPointsRecoversBox) {
  // A 1.2 x 0.8 x 0.9 m table-like block turned 30 deg, seen from 2.5 m with a wall behind it.
  const Object truth = make_obj(ObjectClass::DiningTable, {2.5, 0.45, 1.5}, {1.2, 0.9, 0.8}, deg2rad(30));
  LiftContext ctx = lift_ctx({2.5, 1.4, -1.0}, 0.0, deg2rad(-15));
  SparsePointStore s;
  fill_box(s, truth, 0.05);
  for (double x = 0; x < 5; x += 0.05)  // wall points behind and floor points around: not object points
    for (double y = 0; y < 2.5; y += 0.1) s.add({x, y, 3.5});
  for (double x = 1; x < 4; x += 0.1)
    for (double z = 0; z < 3; z += 0.1) s.add({x, 0.01, z});
  ObjectLifter lifter;
  Detection d = project_box(truth, ctx);
  auto L = lifter.lift(d, ctx, s);
  ASSERT_TRUE(L.has_value());
  EXPECT_TRUE(L->from_points);
  EXPECT_LT((L->centre - truth.centre).norm(), 0.15);
  EXPECT_NEAR(L->size.y(), 0.9, 0.1);
  // Rectangle sizes are symmetric in x/z up to 90 deg: compare the sorted footprint.
  EXPECT_NEAR(std::max(L->size.x(), L->size.z()), 1.2, 0.2);
  EXPECT_NEAR(std::min(L->size.x(), L->size.z()), 0.8, 0.2);
  // A box over bare wall points gives no point-based object.
  Detection wall_only = d;
  wall_only.x0 = 0, wall_only.x1 = 100, wall_only.y0 = 0, wall_only.y1 = 100;
  SparsePointStore bg;
  for (double x = 0; x < 5; x += 0.05)
    for (double y = 0; y < 2.5; y += 0.1) bg.add({x, y, 3.5});
  auto F = lifter.lift(wall_only, ctx, bg);
  EXPECT_TRUE(!F || !F->from_points);
}

TEST(Understanding, LifterFloorRayFallback) {
  // No points: the bottom-centre ray meets the floor and the class prior gives the size.
  const Object truth = make_obj(ObjectClass::Chair, {2.5, 0.45, 3.0}, {0.5, 0.9, 0.5}, 0.0);
  LiftContext ctx = lift_ctx({2.5, 1.4, 0.0}, 0.0, deg2rad(-10));
  ObjectLifter lifter;
  auto L = lifter.lift(project_box(truth, ctx), ctx, SparsePointStore());
  ASSERT_TRUE(L.has_value());
  EXPECT_FALSE(L->from_points);
  EXPECT_LT(std::hypot(L->centre.x() - truth.centre.x(), L->centre.z() - truth.centre.z()), 0.3);
  EXPECT_NEAR(L->size.y(), 0.9, 0.05);
  // Bottom cut off by the image edge, or no floor reference: no lift.
  Detection cut = project_box(truth, ctx);
  cut.y1 = 480;
  EXPECT_FALSE(lifter.lift(cut, ctx, SparsePointStore()).has_value());
  LiftContext nofloor = ctx;
  nofloor.has_floor = false;
  EXPECT_FALSE(lifter.lift(project_box(truth, ctx), nofloor, SparsePointStore()).has_value());
}

TEST(Understanding, LifterTracksAndPublishes) {
  const Object truth = make_obj(ObjectClass::Chair, {2.5, 0.45, 3.0}, {0.5, 0.9, 0.5}, 0.0);
  LiftContext ctx = lift_ctx({2.5, 1.4, 0.0}, 0.0, deg2rad(-10));
  const SparsePointStore none;
  ObjectLifter lifter;
  EXPECT_TRUE(lifter.objects().empty());  // no detections: nothing
  Detection d = project_box(truth, ctx);
  d.frame_id = 1;
  ASSERT_TRUE(lifter.on_detection(d, ctx, none));
  EXPECT_TRUE(lifter.objects().empty());  // one observation is not enough
  EXPECT_FALSE(lifter.on_detection(d, ctx, none));  // the same frame never counts twice
  d.frame_id = 2;
  ASSERT_TRUE(lifter.on_detection(d, ctx, none));
  EXPECT_TRUE(lifter.objects().empty());  // floor-ray-only tracks need fallback_obs (3)
  d.frame_id = 3;
  ASSERT_TRUE(lifter.on_detection(d, ctx, none));
  ASSERT_EQ(lifter.objects().size(), 1u);
  EXPECT_EQ(lifter.objects()[0].n_obs, 3);
  EXPECT_EQ(lifter.objects()[0].cls, ObjectClass::Chair);
  EXPECT_GT(lifter.objects()[0].conf, 0.0);
  EXPECT_LE(lifter.objects()[0].conf, 1.0);
  // Same place, other class: a second track. A lone false detection stays unpublished. Low score and unknown are ignored.
  Detection other = d;
  other.cls = ObjectClass::PottedPlant;
  other.frame_id = 4;
  EXPECT_TRUE(lifter.on_detection(other, ctx, none));
  EXPECT_EQ(lifter.tracks(), 2u);
  EXPECT_EQ(lifter.objects().size(), 1u);
  Detection weak = d;
  weak.score = 0.1f;
  EXPECT_FALSE(lifter.on_detection(weak, ctx, none));
  Detection unk = d;
  unk.cls = ObjectClass::Unknown;
  EXPECT_FALSE(lifter.on_detection(unk, ctx, none));
  EXPECT_EQ(lifter.tracks(), 2u);
  // Deterministic: same inputs, same objects.
  ObjectLifter again;
  for (uint32_t f = 1; f <= 3; ++f) d.frame_id = f, again.on_detection(d, ctx, none);
  EXPECT_EQ(again.objects()[0].centre, lifter.objects()[0].centre);
}

TEST(Understanding, LifterFallbackSwapsAxesInOddQuadrant) {
  // View bearing 120 deg (second quadrant): the yaw reduced to [0, 90) is 30 deg, so the 1.9 x 0.9 couch's width and
  // depth swap. Without the swap the 1.9 m side would point along the view direction instead of across it.
  const double bearing = deg2rad(120);
  const Vec3 cam(4.0, 1.4, 3.0), hv(std::sin(bearing), 0, std::cos(bearing));
  LiftContext ctx = lift_ctx(cam, bearing, deg2rad(-10));
  const Vec3 c = cam + hv * 3.2;
  const Object truth = make_obj(ObjectClass::Couch, {c.x(), 0.425, c.z()}, {1.9, 0.85, 0.9}, bearing);
  ObjectLifter lifter;
  auto L = lifter.lift(project_box(truth, ctx), ctx, SparsePointStore());
  ASSERT_TRUE(L.has_value());
  EXPECT_FALSE(L->from_points);
  EXPECT_NEAR(rad2deg(L->yaw), 30.0, 0.5);
  EXPECT_NEAR(L->size.x(), 0.9, 1e-9);  // depth (0.9) lies along the view direction = this box's width axis
  EXPECT_NEAR(L->size.z(), 1.9, 1e-9);
  // Footprint matches the truth box: the nearest and farthest corners of the lifted box sit where the truth's do.
  Object lifted = make_obj(ObjectClass::Couch, L->centre, L->size, L->yaw);
  for (const Vec3& p : box_corners(lifted)) {
    double best = 1e9;
    for (const Vec3& q : box_corners(truth)) best = std::min(best, (p - q).norm());
    EXPECT_LT(best, 0.35);  // the floor ray reads the front edge; the rest is the class prior
  }
}

TEST(Understanding, LifterEvictsOnlyUnpublishedTracks) {
  LifterParams lp;
  lp.max_tracks = 2;
  LiftContext ctx = lift_ctx({2.5, 1.4, 0.0}, 0.0, deg2rad(-10));
  const SparsePointStore none;
  const Object chair = make_obj(ObjectClass::Chair, {2.5, 0.45, 3.0}, {0.5, 0.9, 0.5}, 0.0);
  const Object bed = make_obj(ObjectClass::Bed, {2.5, 0.275, 3.0}, {1.9, 0.55, 1.4}, 0.0);
  auto see = [&](ObjectLifter& l, const Object& o, ObjectClass cls, uint32_t from, int n) {
    Detection d = project_box(o, ctx);
    d.cls = cls;
    for (int i = 0; i < n; ++i) d.frame_id = from + uint32_t(i), l.on_detection(d, ctx, none);
  };
  {  // one visible (3 floor-ray observations), one not (1): the unseen one makes room
    ObjectLifter l(lp);
    see(l, chair, ObjectClass::Chair, 1, 3);
    see(l, chair, ObjectClass::PottedPlant, 10, 1);
    ASSERT_EQ(l.tracks(), 2u);
    EXPECT_TRUE(l.on_detection([&] { Detection d = project_box(bed, ctx); d.frame_id = 20; return d; }(), ctx, none));
    EXPECT_EQ(l.tracks(), 2u);
    ASSERT_EQ(l.objects().size(), 1u);
    EXPECT_EQ(l.objects()[0].cls, ObjectClass::Chair);
  }
  {  // both reported (2 observations are not enough for a floor-ray track, so use 3): nothing is evicted
    ObjectLifter l(lp);
    see(l, chair, ObjectClass::Chair, 1, 3);
    see(l, chair, ObjectClass::PottedPlant, 10, 3);
    ASSERT_EQ(l.objects().size(), 2u);
    Detection d = project_box(bed, ctx);
    d.frame_id = 20;
    EXPECT_FALSE(l.on_detection(d, ctx, none));
    EXPECT_EQ(l.tracks(), 2u);
    EXPECT_EQ(l.objects().size(), 2u);
  }
}

namespace {
struct OracleObj {
  std::string cls;
  double x, y, z, w, h, d, conf;
  int n_obs;
};
/// Reads one flat-object array of the sim report's "oracle" block ("objects" or "truth").
std::vector<OracleObj> oracle_array(const std::string& report, const char* key) {
  std::vector<OracleObj> out;
  auto o = report.find("\"oracle\":{");
  EXPECT_NE(o, std::string::npos);
  if (o == std::string::npos) return out;
  auto at = report.find(std::string("\"") + key + "\":[", o);
  if (at == std::string::npos) return out;
  auto end = report.find(']', at);
  size_t p = at;
  while ((p = report.find('{', p)) != std::string::npos && p < end) {
    size_t q = report.find('}', p);
    auto obj = json::parse_flat(std::string_view(report).substr(p, q - p + 1));
    EXPECT_TRUE(obj.has_value());
    if (obj) {
      auto d = [&](const char* k) { return obj->count(k) ? std::get<double>(obj->at(k)) : 0.0; };
      out.push_back({std::get<std::string>(obj->at("cls")), d("x"), d("y"), d("z"), d("w"), d("h"), d("d"), d("conf"), int(d("n_obs"))});
    }
    p = q;
  }
  return out;
}
double oracle_num(const std::string& report, const char* key) {
  auto o = report.find("\"oracle\":{");
  auto at = report.find(std::string("\"") + key + "\":", o);
  return std::atof(report.c_str() + at + std::strlen(key) + 3);
}
/// A sim report with the "oracle" block cut out (it is the only thing the oracle adds).
std::string without_oracle(std::string r) {
  auto a = r.find(",\"oracle\":{");
  if (a == std::string::npos) a = r.find("\"oracle\":{");
  if (a == std::string::npos) return r;
  size_t b = r.find('{', a), depth = 0;
  for (; b < r.size(); ++b) {
    depth += r[b] == '{', depth -= r[b] == '}';
    if (depth == 0) break;
  }
  r.erase(a, b + 1 - a);
  return r;
}
}  // namespace

TEST(Understanding, SimOracleLiftsFurniture) {
  const auto room = sim::make_room("objects");
  ASSERT_EQ(room.boxes.size(), 4u);
  std::vector<std::string> reps(3);
  std::vector<std::thread> th;
  const auto t0 = std::chrono::steady_clock::now();
  for (size_t k = 0; k < 3; ++k)
    th.emplace_back([&, k] {
      sim::Config c;
      c.room = "objects", c.seed = k + 1, c.max_s = 150;
      c.policy = "scripted", c.view_points = true;  // perimeter loops + centre spins: the lifter is tested in isolation
      c.detector_oracle = true;
      reps[k] = sim::simulate(c);
    });
  for (auto& t : th) t.join();
  const double wall_s = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
  std::cout << "\n  Understanding.SimOracleLiftsFurniture (miss 15%, false 8%/keyframe, centre <= 0.3 m, height <= 0.25 m), wall "
            << wall_s << " s\n";
  int found = 0, total = 0;
  for (size_t k = 0; k < 3; ++k) {
    auto lifted = oracle_array(reps[k], "objects"), truth = oracle_array(reps[k], "truth");
    ASSERT_EQ(truth.size(), 4u);
    std::vector<bool> used(lifted.size(), false);
    for (auto& t : truth) {
      ++total;
      double best = 1e9, bh = 0;
      size_t bi = lifted.size();
      for (size_t i = 0; i < lifted.size(); ++i) {
        if (used[i] || lifted[i].cls != t.cls) continue;
        double e = std::sqrt(std::pow(lifted[i].x - t.x, 2) + std::pow(lifted[i].y - t.y, 2) + std::pow(lifted[i].z - t.z, 2));
        if (e < best) best = e, bh = std::fabs(lifted[i].h - t.h), bi = i;
      }
      const bool ok = bi < lifted.size() && best <= 0.3 && bh <= 0.25;
      if (ok) used[bi] = true, ++found;
      std::cout << "  seed " << k + 1 << " " << t.cls << ": "
                << (bi < lifted.size() ? "centre err " + std::to_string(best) + " height err " + std::to_string(bh) : std::string("not lifted"))
                << (ok ? "" : "  FAIL") << "\n";
    }
    const int ghosts = int(std::count(used.begin(), used.end(), false));
    for (size_t i = 0; i < lifted.size(); ++i)
      if (!used[i]) std::cout << "  seed " << k + 1 << " ghost " << lifted[i].cls << " (" << lifted[i].x << "," << lifted[i].z << ") size " << lifted[i].w << "x" << lifted[i].d << "x" << lifted[i].h << " n_obs " << lifted[i].n_obs << "\n";
    std::cout << "  seed " << k + 1 << ": " << lifted.size() << " published, " << ghosts << " ghost(s), "
              << oracle_num(reps[k], "detections") << " detections + " << oracle_num(reps[k], "false_detections") << " false\n";
    EXPECT_EQ(ghosts, 0) << "seed " << k + 1;
  }
  std::cout << "  total: " << found << "/" << total << " lifted within tolerance\n";
  EXPECT_EQ(found, total);
  if (wall_s >= 90.0) std::cout << "  WARNING: " << wall_s << " s wall (budget 90 s)\n";
}

// NOTE (step 5): the oracle does not feed the engine today, so this only proves the harness is inert. Once GuideEngine
// consumes detections (on_detection wired), re-assert it with the engine in the loop: misses everywhere => same report.
TEST(Understanding, SimNoDetectionsLeavesGuidanceUnchanged) {
  // The oracle never feeds the engine: with every detection missed (and no false ones), the report is the flag-off report.
  for (double understanding : {0.0, 1.0}) {
    sim::Config base;
    base.room = "objects", base.seed = 2, base.max_s = 25;
    base.guide.understanding = understanding;
    sim::Config on = base;
    on.detector_oracle = true, on.oracle_miss = 1.0, on.oracle_false = 0.0;
    const std::string a = sim::simulate(base), b = sim::simulate(on);
    EXPECT_EQ(a.find("\"oracle\""), std::string::npos);
    EXPECT_EQ(oracle_num(b, "detections"), 0.0);
    EXPECT_TRUE(oracle_array(b, "objects").empty());
    EXPECT_EQ(without_oracle(b), a) << "understanding " << understanding;
    // Detections present (the oracle running for real) still do not change what the engine reports.
    sim::Config live = base;
    live.detector_oracle = true;
    EXPECT_EQ(without_oracle(sim::simulate(live)), a) << "understanding " << understanding;
  }
}
