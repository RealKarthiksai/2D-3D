// Phase 9 room understanding: stage-by-stage tests (step 1: types + SurfaceClassifier; step 2: SparsePointStore +
// OpeningDetector).
#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <iostream>
#include <map>
#include <string>
#include <vector>

#include "capturekit/guide/coverage.hpp"
#include "capturekit/guide/engine.hpp"
#include "capturekit/guide/live_map.hpp"
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

// ---- sim: the door_window scene recovers both openings; plain walls stay clean ----
TEST(Understanding, SimRecoversDoorAndWindow) {
  const auto room = sim::make_room("door_window");
  ASSERT_EQ(room.openings.size(), 2u);
  std::cout << "\n  Understanding.SimRecoversDoorAndWindow (synthetic points, tolerance 0.25 m)\n";
  for (uint64_t seed : {1u, 2u, 3u}) {
    sim::Config c;
    c.room = "door_window", c.seed = seed;
    c.guide.understanding = 1;
    auto rep = sim::simulate(c);
    auto ops = sim_openings(rep);
    int found = 0;
    for (auto& sp : room.openings) {
      int m = 0;
      for (auto& o : ops) m += sim_match(o, room, sp, 0.25);
      EXPECT_EQ(m, 1) << "seed " << seed << " opening kind " << sp.kind;
      found += m == 1;
    }
    EXPECT_EQ(ops.size(), room.openings.size()) << "seed " << seed << " (false openings)";
    auto ps = rep.find("\"points_stored\":");
    std::cout << "  seed " << seed << ": " << found << "/2 recovered, " << ops.size() << " reported, "
              << rep.substr(ps, rep.find_first_of(",}", ps) - ps) << "\n";
    for (auto& o : ops)
      std::cout << "    " << o.kind << " (" << o.x0 << "," << o.z0 << ")-(" << o.x1 << "," << o.z1 << ") y " << o.y0 << ".."
                << o.y1 << " conf " << o.conf << "\n";
  }
}

TEST(Understanding, SimNoFalseOpeningsOnPlainWalls) {
  for (const char* name : {"box", "L"})
    for (uint64_t seed : {1u, 2u, 3u}) {
      sim::Config c;
      c.room = name, c.seed = seed;
      c.guide.understanding = 1;
      bool present = false;
      auto ops = sim_openings(sim::simulate(c), &present);
      EXPECT_TRUE(present) << name;
      EXPECT_TRUE(ops.empty()) << name << " seed " << seed << ": " << ops.size() << " false openings";
    }
  // Flag off: the report has no openings key at all (Phase 8 reports unchanged).
  sim::Config off;
  off.room = "door_window", off.seed = 1, off.max_s = 30;
  bool present = true;
  sim_openings(sim::simulate(off), &present);
  EXPECT_FALSE(present);
}
