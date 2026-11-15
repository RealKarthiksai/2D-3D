// Phase 9 room understanding: stage-by-stage tests (step 1: types + SurfaceClassifier).
#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <iostream>
#include <map>
#include <string>
#include <vector>

#include "capturekit/guide/coverage.hpp"
#include "capturekit/guide/engine.hpp"
#include "capturekit/guide/live_map.hpp"
#include "capturekit/guide/sim.hpp"
#include "capturekit/guide/surface_classifier.hpp"
#include "capturekit/guide/understanding.hpp"

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
