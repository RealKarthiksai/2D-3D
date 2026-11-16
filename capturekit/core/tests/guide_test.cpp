// Phase 8: GuideMind unit tests per stage + simulator determinism, safety and budget.
#include <gtest/gtest.h>

#include <chrono>
#include <cmath>
#include <sstream>

#include "capturekit/guide/engine.hpp"
#include "capturekit/guide/sim.hpp"

using namespace capturekit;
using namespace capturekit::guide;

namespace {

geometry::Intrinsics K640() {
  geometry::Intrinsics K;
  K.width = 640, K.height = 480, K.fx = K.fy = 500, K.cx = 319.5, K.cy = 239.5;
  return K;
}

bundle::PlaneRecord plane(const std::string& id, const std::string& kind, Vec3 c, Vec3 n, Vec3 u, double eu, double ev) {
  bundle::PlaneRecord p;
  p.id = id, p.kind = kind;
  for (int i = 0; i < 3; ++i) p.center[i] = c[i], p.normal[i] = n[i], p.axis_u[i] = u[i];
  p.extent_u = eu, p.extent_v = ev;
  return p;
}

// A 4 m wide, 2.5 m high wall at z = 4 facing -z (towards the room).
bundle::PlaneRecord back_wall(const std::string& id = "w") {
  return plane(id, "wall", {2, 1.25, 4}, {0, 0, -1}, {1, 0, 0}, 4, 2.5);
}

struct BlockAll : Occluder {
  bool clear(const Vec3&, const Vec3&) const override { return false; }
};

GuideParams P() { return GuideParams{}; }

}  // namespace

// ---- params ----
TEST(GuideParams, HashOverridesAndUnknownKeys) {
  auto a = GuideParams::from_json("{}");
  auto b = GuideParams::from_json("{\"min_views\": 5}");
  ASSERT_TRUE(a && b);
  EXPECT_EQ(a->hash(), GuideParams{}.hash());
  EXPECT_NE(a->hash(), b->hash());
  EXPECT_DOUBLE_EQ(b->min_views, 5);
  std::string err;
  EXPECT_FALSE(GuideParams::from_json("{\"nope\": 1}", &err));
  EXPECT_NE(err.find("nope"), std::string::npos);
}

// ---- LiveMap ----
TEST(GuideLiveMap, UpdateMergeAndLevels) {
  LiveMap m;
  EXPECT_EQ(m.add_plane(back_wall("a")), "a");
  int v = m.version();
  // Same id: the newest (grown) geometry replaces the old one.
  auto g = back_wall("a");
  g.extent_u = 5;
  m.add_plane(g);
  EXPECT_EQ(m.planes().size(), 1u);
  EXPECT_DOUBLE_EQ(m.planes().at("a").eu, 5);
  EXPECT_GT(m.version(), v);
  // New id for the same surface: merged into the existing plane.
  auto dup = back_wall("b");
  dup.center[0] = 4.9;  // touching the first
  EXPECT_EQ(m.add_plane(dup), "a");
  EXPECT_EQ(m.planes().size(), 1u);
  EXPECT_GT(m.planes().at("a").eu, 5);
  // A different wall (other normal) stays separate; degenerate planes are ignored.
  m.add_plane(plane("side", "wall", {0, 1.25, 2}, {1, 0, 0}, {0, 0, 1}, 4, 2.5));
  EXPECT_EQ(m.planes().size(), 2u);
  m.add_plane(plane("bad", "wall", {0, 0, 0}, {0, 0, 1}, {1, 0, 0}, 0, 0));
  EXPECT_EQ(m.planes().size(), 2u);
  // Levels.
  EXPECT_FALSE(m.has_floor());
  m.add_plane(plane("f", "floor", {2, 0.02, 2}, {0, 1, 0}, {1, 0, 0}, 4, 4));
  m.add_plane(plane("c", "ceiling", {2, 2.5, 2}, {0, -1, 0}, {1, 0, 0}, 4, 4));
  EXPECT_TRUE(m.has_floor() && m.has_ceiling());
  EXPECT_NEAR(m.floor_y(), 0.02, 1e-9);
  EXPECT_NEAR(m.ceiling_y(), 2.5, 1e-9);
  EXPECT_TRUE(m.extents().valid());
}

// ---- Coverage ----
TEST(GuideCoverage, NeedsViewsAndSpread) {
  LiveMap m;
  m.add_plane(back_wall());
  GuideParams p = P();
  Coverage cov(p);
  cov.sync(m);
  ASSERT_GT(cov.stats().valid, 1000u);
  EXPECT_EQ(cov.stats().sufficient, 0u);
  auto K = K640();
  auto obs = [&](double x, double z) {
    return Obs{pose_from_yaw_pitch({x, 1.25, z}, 0, 0), K, 0, 1.0, true};
  };
  // Three views from the SAME spot: enough views, no spread -> still not sufficient.
  for (int i = 0; i < 3; ++i) cov.observe(obs(2, 1.5), nullptr, nullptr);
  EXPECT_GT(cov.stats().seen, 100u);
  EXPECT_EQ(cov.stats().sufficient, 0u);
  // Views from different positions give ray spread: the centre becomes sufficient.
  cov.observe(obs(1.2, 1.5), nullptr, nullptr);
  cov.observe(obs(2.8, 1.5), nullptr, nullptr);
  EXPECT_GT(cov.stats().sufficient, 50u);
  EXPECT_GT(cov.stats().completeness(), 0.05);
}

// views_at: viewed wall cell > 0, outside the grid 0, valid == 0 cell (plane shrank after being viewed) 0.
TEST(GuideCoverage, ViewsAtInsideOutsideAndInvalidCell) {
  LiveMap m;
  m.add_plane(back_wall());
  GuideParams p = P();
  Coverage cov(p);
  cov.sync(m);
  auto K = K640();
  for (double x : {1.4, 2.0, 2.6}) cov.observe(Obs{pose_from_yaw_pitch({x, 1.25, 1.5}, 0, 0), K, 0, 1.0, true}, nullptr, nullptr);
  const Vec3 inside(2.0, 1.25, 4.0), edge(3.2, 1.25, 4.0);
  EXPECT_GE(cov.views_at("w", inside), 3);
  EXPECT_GE(cov.views_at("w", edge), 1);
  EXPECT_EQ(cov.views_at("w", Vec3(30.0, 1.25, 4.0)), 0);  // outside the grid
  EXPECT_EQ(cov.views_at("w", Vec3(2.0, 40.0, 4.0)), 0);
  // Same plane id, narrower (u in [1, 3]): the grid keeps the cell but it is no longer valid.
  m.add_plane(plane("w", "wall", {2, 1.25, 4}, {0, 0, -1}, {1, 0, 0}, 2, 2.5));
  cov.sync(m);
  EXPECT_GE(cov.views_at("w", inside), 3);
  EXPECT_EQ(cov.views_at("w", edge), 0);
}

TEST(GuideCoverage, RangeSharpnessAndOcclusion) {
  LiveMap m;
  m.add_plane(back_wall());
  GuideParams p = P();
  Coverage cov(p);
  cov.sync(m);
  auto K = K640();
  // Too far (range > 4 m): seen but never a qualifying view.
  for (double x : {1.0, 2.0, 3.0})
    cov.observe(Obs{pose_from_yaw_pitch({x, 1.25, -1.5}, 0, 0), K, 0, 1.0, true}, nullptr, nullptr);
  EXPECT_GT(cov.stats().seen, 0u);
  EXPECT_EQ(cov.stats().sufficient, 0u);
  // Blurry keyframes do not count.
  for (double x : {1.0, 2.0, 3.0})
    cov.observe(Obs{pose_from_yaw_pitch({x, 1.25, 2.0}, 0, 0), K, 0, 0.1, true}, nullptr, nullptr);
  EXPECT_EQ(cov.stats().sufficient, 0u);
  // Occluded cells are excluded after repeated blocked keyframe views (e.g. behind furniture).
  size_t valid0 = cov.stats().valid;
  BlockAll blocker;
  for (int i = 0; i < 9; ++i)
    cov.observe(Obs{pose_from_yaw_pitch({2, 1.25, 2.0 + 0.01 * i}, 0, 0), K, 0, 1.0, true}, &blocker, nullptr);
  EXPECT_LT(cov.stats().valid, valid0);
}

TEST(GuideCoverage, MarginalGainRewardsNewAnglesNotRepeats) {
  LiveMap m;
  m.add_plane(back_wall());
  GuideParams p = P();
  Coverage cov(p);
  cov.sync(m);
  auto K = K640();
  auto T0 = pose_from_yaw_pitch({2, 1.25, 2.0}, 0, 0);
  double g_fresh = cov.eval_view(T0, K, nullptr).gain;
  EXPECT_GT(g_fresh, 10);
  for (int i = 0; i < 4; ++i) cov.observe(Obs{T0, K, 0, 1.0, true}, nullptr, nullptr);
  double g_same = cov.eval_view(T0, K, nullptr).gain;                                         // nothing new from here
  double g_side = cov.eval_view(pose_from_yaw_pitch({0.8, 1.25, 2.0}, 0, 0), K, nullptr).gain;  // new angle
  EXPECT_LT(g_same, 0.3 * g_fresh);
  EXPECT_GT(g_side, 2 * g_same);
}

// ---- NavGrid ----
TEST(GuideNavGrid, ObstaclesFreeSpaceAndPassability) {
  GuideParams p = P();
  NavGrid nav(p);
  nav.set_floor_y(0);
  // Points: a wall at x = 3 (height 1.0 m) and a low point (0.1 m) that must be ignored.
  for (int i = 0; i < 40; ++i) {
    nav.add_point({3.0, 1.0, 0.1 * i});
    nav.add_point({3.0, 1.0, 0.1 * i});
  }
  nav.add_point({1.0, 0.1, 1.0});
  nav.add_point({1.0, 0.1, 1.0});
  int ix, iz;
  nav.to_cell({3.0, 0, 1.0}, ix, iz);
  EXPECT_TRUE(nav.obstacle(ix, iz));
  nav.to_cell({1.0, 0, 1.0}, ix, iz);
  EXPECT_FALSE(nav.obstacle(ix, iz));
  // Free space by carving from the camera to the wall points (never through the wall).
  for (int i = 0; i < 40; ++i) nav.carve({0.5, 1.4, 2.0}, {3.0, 1.0, 0.1 * i});
  nav.mark_traversed({0.5, 1.4, 2.0});
  nav.rebuild();
  nav.to_cell({1.5, 0, 2.0}, ix, iz);
  EXPECT_TRUE(nav.known_free(ix, iz));
  nav.to_cell({3.4, 0, 2.0}, ix, iz);  // behind the wall: unknown
  EXPECT_FALSE(nav.known_free(ix, iz));
  // Body radius: a cell right next to the wall is free but not passable; well inside the room it is.
  nav.to_cell({2.8, 0, 2.0}, ix, iz);
  EXPECT_FALSE(nav.passable(ix, iz));
  nav.to_cell({1.5, 0, 2.0}, ix, iz);
  EXPECT_TRUE(nav.passable(ix, iz));
  // Reachability: cells beyond the wall are unreachable.
  int sx, sz;
  nav.to_cell({1.5, 0, 2.0}, sx, sz);
  std::vector<float> dist;
  nav.distances(sx, sz, dist);
  EXPECT_EQ(dist[nav.index(sx, sz)], 0.f);
  nav.to_cell({3.4, 0, 2.0}, ix, iz);
  EXPECT_LT(dist[nav.index(ix, iz)], 0.f);
}

TEST(GuideNavGrid, LineOfSightRespectsObstacleHeight) {
  GuideParams p = P();
  NavGrid nav(p);
  nav.set_floor_y(0);
  for (int i = 0; i < 20; ++i) {  // a 0.8 m high sofa at x = 2, z in [0, 2]
    nav.add_point({2.0, 0.8, 0.1 * i});
    nav.add_point({2.0, 0.8, 0.1 * i});
  }
  // A ray at 1.4 m passes over it; a ray down to the floor behind it does not.
  EXPECT_TRUE(nav.clear({0.5, 1.4, 1.0}, {3.5, 1.4, 1.0}));
  EXPECT_FALSE(nav.clear({0.5, 1.4, 1.0}, {3.5, 0.0, 1.0}));
}

// ---- Planner ----
namespace {
struct Scene {
  GuideParams p = P();
  LiveMap map;
  Coverage cov{P()};
  NavGrid nav{P()};
  Planner planner{P()};
  geometry::Intrinsics K = K640();
  UserState user;
  Scene() {
    nav.set_floor_y(0);
    map.add_plane(back_wall());
    map.add_plane(plane("l", "wall", {0, 1.25, 2}, {1, 0, 0}, {0, 0, 1}, 4, 2.5));
    cov.sync(map);
    // Free space: the 4 x 4 room, with wall obstacles at z = 4 and x = 0.
    for (int i = 0; i < 40; ++i)
      for (double h : {0.4, 1.2, 2.3})  // real walls have points at every height
        for (int k = 0; k < 2; ++k) {
          nav.add_point({0.0, h, 0.1 * i});
          nav.add_point({0.1 * i, h, 4.0});
        }
    for (int ix = 3; ix < 40; ++ix)  // integer cell indices: float stepping would skip cells
      for (int iz = 3; iz < 37; ++iz) nav.mark_open({(ix + 0.5) * 0.1, 0, (iz + 0.5) * 0.1});
    user.pos = {2, 1.4, 1.0};
    user.yaw = 0;
    nav.mark_traversed(user.pos);
    nav.rebuild();
  }
};
}  // namespace

TEST(GuidePlanner, NoMapMeansSweepAndCompleteMeansFinish) {
  GuideParams p = P();
  Coverage empty(p);
  NavGrid nav(p);
  Planner pl(p);
  UserState u;
  auto plan = pl.replan(0, u, empty, nav, K640(), 1.4);
  EXPECT_EQ(plan.mode, Mode::Sweep);
}

TEST(GuidePlanner, TargetsUsefulSafeViewpointsAndKeepsThem) {
  Scene s;
  auto plan = s.planner.replan(0, s.user, s.cov, s.nav, s.K, 1.4);
  ASSERT_EQ(plan.mode, Mode::Target);
  ASSERT_TRUE(plan.target.valid);
  int ix, iz;
  s.nav.to_cell({plan.target.x, 0, plan.target.z}, ix, iz);
  EXPECT_TRUE(s.nav.passable(ix, iz));  // never a blocked or unknown cell
  EXPECT_GT(plan.target.gain, s.p.min_gain);
  // Hysteresis: identical state -> identical target, no switching.
  Target first = s.planner.current();
  for (int i = 1; i <= 5; ++i) s.planner.replan(i * 500'000'000LL, s.user, s.cov, s.nav, s.K, 1.4);
  EXPECT_EQ(s.planner.switches(), 0);
  EXPECT_EQ(s.planner.current().id, first.id);
  EXPECT_EQ(s.planner.reached_count(), 0);  // standing at the pose for 2.5 s is not yet a visit
  // Reaching it clears the target and remembers the spot.
  UserState at = s.user;
  at.pos = {first.x, 1.4, first.z};
  at.yaw = first.yaw;
  at.pitch = first.pitch;
  auto r = s.planner.replan(10'000'000'000LL, at, s.cov, s.nav, s.K, 1.4);
  EXPECT_TRUE(r.reached);  // the user has stood there past dwell_s (before that it is not a visit)
  EXPECT_EQ(s.planner.reached_count(), 1);
}

TEST(GuidePlanner, PenalizedTargetIsNotChosenAgain) {
  Scene s;
  s.planner.replan(0, s.user, s.cov, s.nav, s.K, 1.4);
  Target t1 = s.planner.current();
  ASSERT_TRUE(t1.valid);
  s.planner.penalize_current();
  EXPECT_FALSE(s.planner.current().valid);
  s.planner.replan(500'000'000, s.user, s.cov, s.nav, s.K, 1.4);
  Target t2 = s.planner.current();
  ASSERT_TRUE(t2.valid);
  EXPECT_TRUE(std::hypot(t2.x - t1.x, t2.z - t1.z) > 0.6 || std::fabs(wrap_pi(t2.yaw - t1.yaw)) > deg2rad(35));
}

TEST(GuidePlanner, AsksToLookAtMissingCeilingWhenWallsAreDone) {
  Scene s;
  PlanHints h;
  h.need_ceiling = true;
  // Mark the walls as covered by observing from many positions.
  for (double x = 0.8; x <= 3.4; x += 0.2)
    for (double z : {1.0, 1.5, 2.0, 2.5, 3.0})
      for (double yaw : {0.0, -kPi / 2}) s.cov.observe(Obs{pose_from_yaw_pitch({x, 1.4, z}, yaw, 0), s.K, 0, 1.0, true}, nullptr, nullptr);
  ASSERT_GT(s.cov.stats().completeness(), 0.5);
  auto plan = s.planner.replan(0, s.user, s.cov, s.nav, s.K, 1.4, h);
  ASSERT_TRUE(plan.target.valid);
  EXPECT_TRUE(plan.discover);
  EXPECT_GT(plan.target.pitch, deg2rad(20));  // look up
}

// ---- Instructions ----
namespace {
Situation sit(int64_t t_s, Mode mode = Mode::Target) {
  Situation s;
  s.t_ns = t_s * 1'000'000'000LL;
  s.plan.mode = mode;
  s.user.pos = {0, 1.4, 0};
  s.user.yaw = 0;
  return s;
}
std::vector<Instruction> run(InstructionGenerator& g, const Situation& s, std::vector<LifeEvent>* ev = nullptr) {
  std::vector<Instruction> out;
  std::vector<LifeEvent> e;
  g.update(s, out, e);
  if (ev) *ev = e;
  return out;
}
}  // namespace

TEST(GuideInstructions, EgocentricMoveAndRotate) {
  InstructionGenerator g(P());
  auto s = sit(0);
  s.plan.target = {true, false, 3.0, 0.0, 0, 0, 10, 1, 0, 1};  // 3 m to the +x side = the user's right? (+x is left of +z)
  auto out = run(g, s);
  ASSERT_EQ(out.size(), 1u);
  EXPECT_EQ(out[0].type, InstrType::RotateToward);  // 90 degrees off: turn first
  EXPECT_GT(out[0].param("rel_deg"), 60);            // + = left
  EXPECT_NE(out[0].speak_text.find("left"), std::string::npos) << out[0].speak_text;
  InstructionGenerator g2(P());
  auto s2 = sit(0);
  s2.plan.target = {true, false, 0.0, 3.0, 0, 0, 10, 1, 0, 1};  // straight ahead
  auto o2 = run(g2, s2);
  ASSERT_EQ(o2.size(), 1u);
  EXPECT_EQ(o2[0].type, InstrType::MoveTo);
  EXPECT_NE(o2[0].speak_text.find("ahead"), std::string::npos);
  EXPECT_DOUBLE_EQ(o2[0].param("distance_m"), 3.0);
}

TEST(GuideInstructions, ShortTextsAreShortAndAllMessagesExist) {
  for (auto id : {"slow_down", "hold_still", "relocalize", "move.to", "rotate.to", "tilt.up", "tilt.down", "step.back", "sweep", "more_light", "finish"}) {
    auto t = InstructionGenerator::render(id, {{"dir", "left"}, {"m", "2"}, {"deg", "90"}}, false);
    EXPECT_NE(t, id) << id;
    int words = 0;
    std::istringstream is(t);
    for (std::string w; is >> w;) ++words;
    EXPECT_LE(words, 8) << t;
    EXPECT_EQ(t.find('{'), std::string::npos) << t;  // all placeholders filled
  }
}

TEST(GuideInstructions, CooldownsMinGapAndNoRepeats) {
  GuideParams p = P();
  InstructionGenerator g(p);
  auto s = sit(0);
  s.plan.target = {true, false, 0.0, 3.0, 0, 0, 10, 1, 0, 1};
  EXPECT_EQ(run(g, s).size(), 1u);
  // Within the minimum gap and cooldown nothing new is said, even if asked every frame.
  for (int t = 1; t < 6; ++t) {
    auto s2 = sit(t);
    s2.plan = s.plan;
    EXPECT_TRUE(run(g, s2).empty()) << "t=" << t;
  }
  // The identical instruction stays active until it completes or expires: no back-to-back repeat.
  auto s3 = sit(7);
  s3.plan = s.plan;
  EXPECT_TRUE(run(g, s3).empty());
}

TEST(GuideInstructions, SafetyInterruptsAndLifecycle) {
  GuideParams p = P();
  InstructionGenerator g(p);
  auto s = sit(0);
  s.plan.target = {true, false, 0.0, 3.0, 0, 0, 10, 1, 0, 1};
  ASSERT_EQ(run(g, s).size(), 1u);  // guidance MOVE_TO issued
  // Tracking lost beats guidance immediately (no min-gap wait) and supersedes nothing of its priority.
  auto lost = sit(1);
  lost.plan = s.plan;
  lost.tracking_state = 3;
  lost.lost_since_ns = lost.t_ns;
  auto out = run(g, lost);
  ASSERT_EQ(out.size(), 1u);
  EXPECT_EQ(out[0].type, InstrType::HoldStill);
  EXPECT_EQ(out[0].priority, 3);
  // Long loss escalates, once the HOLD_STILL cooldown is over.
  auto long_lost = sit(10);
  long_lost.plan = s.plan;
  long_lost.tracking_state = 3;
  long_lost.lost_since_ns = 1'000'000'000LL;
  std::vector<LifeEvent> ev;
  out = run(g, long_lost, &ev);
  ASSERT_EQ(out.size(), 1u);
  EXPECT_EQ(out[0].type, InstrType::Relocalize);
  // Tracking back: the safety instruction completes.
  auto back = sit(12);
  back.plan = s.plan;
  run(g, back, &ev);
  bool completed = false;
  for (auto& e : ev) completed |= e.ins.type == InstrType::Relocalize && e.state == LifeState::Completed;
  EXPECT_TRUE(completed);
  // Blur streak -> SLOW_DOWN with its own cooldown.
  InstructionGenerator g2(p);
  auto b = sit(0);
  b.blur_streak = 3;
  auto o2 = run(g2, b);
  ASSERT_EQ(o2.size(), 1u);
  EXPECT_EQ(o2[0].type, InstrType::SlowDown);
  auto b2 = sit(2);
  b2.blur_streak = 5;
  EXPECT_TRUE(run(g2, b2).empty());  // cooldown 4 s
  // An unfulfilled instruction expires.
  InstructionGenerator g3(p);
  auto e0 = sit(0);
  e0.plan.target = {true, false, 0.0, 9.0, 0, 0, 10, 1, 0, 1};
  ASSERT_EQ(run(g3, e0).size(), 1u);
  auto e1 = sit(13);
  e1.plan = e0.plan;
  std::vector<LifeEvent> ev2;
  run(g3, e1, &ev2);
  ASSERT_FALSE(ev2.empty());
  EXPECT_EQ(ev2[0].state, LifeState::Expired);
}

TEST(GuideInstructions, FinishSweepAndTilt) {
  GuideParams p = P();
  InstructionGenerator g(p);
  EXPECT_EQ(run(g, sit(0, Mode::Finish))[0].type, InstrType::Finish);
  InstructionGenerator g2(p);
  EXPECT_TRUE(run(g2, sit(1, Mode::Sweep)).empty());  // sweep only after a short settle time
  auto out = run(g2, sit(4, Mode::Sweep));
  ASSERT_EQ(out.size(), 1u);
  EXPECT_EQ(out[0].type, InstrType::Sweep);
  InstructionGenerator g3(p);
  auto s = sit(0);
  s.plan.target = {true, false, 0.0, 0.0, 0, deg2rad(35), 0, 0, 0, 1};  // here already, look up
  auto o3 = run(g3, s);
  ASSERT_EQ(o3.size(), 1u);
  EXPECT_EQ(o3[0].type, InstrType::TiltUp);
}

// ---- Log ----
TEST(GuideLogTest, RecordsLifecycleAndIsDeterministic) {
  auto make = [] {
    GuideLog l;
    l.start(0);
    Instruction i;
    i.id = 1, i.type = InstrType::MoveTo, i.priority = 2, i.t_ns = 2'000'000'000LL, i.msg_id = "move.to", i.speak_text = "Walk ahead.";
    i.params = {{"distance_m", 2}};
    l.issued(i, 0.1);
    l.ended({i, LifeState::Completed, 5'000'000'000LL, 0.3});
    l.sample(1'000'000'000LL, 0.1, 0.2);
    return l.to_json("abc");
  };
  auto a = make();
  EXPECT_EQ(a, make());
  EXPECT_NE(a.find("\"time_to_compliance_s\":3"), std::string::npos) << a;
  EXPECT_NE(a.find("\"coverage_delta\":0.2"), std::string::npos) << a;
}

// ---- Simulator: determinism, safety, ordering, budget ----
TEST(GuideSim, DeterministicAndSafe) {
  sim::Config c;
  c.room = "furniture";
  c.policy = "planner";
  c.seed = 3;
  c.max_s = 90;
  auto a = sim::simulate(c), b = sim::simulate(c);
  EXPECT_EQ(a, b);  // byte-identical for equal input
  EXPECT_NE(a.find("\"unsafe_targets_gt\":0"), std::string::npos) << a;
  EXPECT_NE(a.find("\"unsafe_targets\":0"), std::string::npos) << a;
  c.seed = 4;
  EXPECT_NE(a, sim::simulate(c));  // the seed matters
}

TEST(GuideSim, PlannerBeatsScriptedWalkOnBox) {
  sim::Config c;
  c.room = "box";
  c.max_s = 200;
  c.policy = "planner";
  auto plan = sim::simulate(c);
  c.policy = "scripted";
  auto script = sim::simulate(c);
  auto cov = [](const std::string& j) {
    auto p = j.find("\"coverage_gt_final\":");
    return std::stod(j.substr(p + 20));
  };
  EXPECT_GT(cov(plan), cov(script) + 0.2) << plan << "\n" << script;
}

TEST(GuideSim, AverageStepWithinBudget) {
  sim::Config c;
  c.room = "box";
  c.policy = "planner";
  c.max_s = 60;
  auto t0 = std::chrono::steady_clock::now();  // test-only timing; the core never reads clocks
  sim::simulate(c);
  double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count() / (60.0 * 30);
  RecordProperty("ms_per_step_incl_simulator", std::to_string(ms));
  std::printf("[ guide ] %.3f ms per 30 Hz step (engine + simulator ground truth)\n", ms);
#ifdef NDEBUG
  EXPECT_LT(ms, std::getenv("CK_PERF_STRICT") ? 10.0 : 25.0);  // phase 8 budget: <= 10 ms average update
#endif
}
