// Android brief B1: OverlayModel snapshot + "overlay" event. Golden JSON comes from a fixed-seed sim run.
#include <gtest/gtest.h>

#include <algorithm>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <set>
#include <sstream>

#include "capturekit/guide/engine.hpp"
#include "capturekit/guide/overlay.hpp"
#include "capturekit/guide/sim.hpp"

using namespace capturekit;
using namespace capturekit::guide;
namespace fs = std::filesystem;

namespace {
size_t words(const std::string& s) {
  std::istringstream is(s);
  size_t n = 0;
  for (std::string w; is >> w;) ++n;
  return n;
}

bundle::PoseSample pose_at(int64_t t, double x, double z, double yaw, int state = 1) {
  auto T = pose_from_yaw_pitch({x, 1.4, z}, yaw, 0);
  bundle::PoseSample p;
  p.t_ns = t;
  for (int i = 0; i < 3; ++i) p.t_world_from_camera[i] = T.translation()[i];
  auto q = T.se3().unit_quaternion();
  p.q_world_from_camera_wxyz[0] = q.w(), p.q_world_from_camera_wxyz[1] = q.x();
  p.q_world_from_camera_wxyz[2] = q.y(), p.q_world_from_camera_wxyz[3] = q.z();
  p.tracking_state = static_cast<bundle::TrackingState>(state);
  return p;
}

bundle::PlaneRecord wall(const std::string& id, Vec3 c, Vec3 n, Vec3 u, double eu, double ev) {
  bundle::PlaneRecord p;
  p.id = id, p.kind = "wall";
  for (int i = 0; i < 3; ++i) p.center[i] = c[i], p.normal[i] = n[i], p.axis_u[i] = u[i];
  p.extent_u = eu, p.extent_v = ev;
  return p;
}

std::string golden_run() {
  sim::Config c;
  c.room = "box";
  c.policy = "planner";
  c.seed = 1;
  c.max_s = 60;
  c.overlay_every_s = 5;
  return sim::simulate(c);
}

// The overlay_samples array of a sim report, one JSON object per line.
std::string samples_of(const std::string& report) {
  auto a = report.find("\"overlay_samples\":[");
  auto b = report.find("],\"", a);
  std::string arr = report.substr(a + 19, b - a - 19), out;
  for (size_t i = 0; i < arr.size(); ++i) {
    out += arr[i];
    if (arr[i] == '}' && i + 1 < arr.size() && arr[i + 1] == ',') out += '\n', ++i;
  }
  return out + "\n";
}
}  // namespace

TEST(Overlay, CatalogTextsAreShortSpeakStyle) {
  auto ids = why_ids();
  ASSERT_GE(ids.size(), 10u);
  for (auto& id : ids) {
    std::string t = why_line(id);
    EXPECT_FALSE(t.empty()) << id;
    EXPECT_LE(words(t), 8u) << id << ": " << t;
  }
  EXPECT_STREQ(why_line("no.such.id"), "");
}

TEST(Overlay, EmptyEngineSweepsAndJsonIsFlatSorted) {
  GuideEngine e;
  auto m = e.overlay(123);
  EXPECT_EQ(m.mode, "sweep");
  EXPECT_EQ(m.why_id, "why.sweep");
  EXPECT_EQ(m.walls, 0);
  EXPECT_FALSE(m.wall_found || m.floor_found || m.ceiling_found);
  auto j = m.to_json();
  EXPECT_EQ(j.find('['), std::string::npos) << j;  // flat: no arrays
  EXPECT_NE(j.find("\"type\":\"overlay\""), std::string::npos);
  EXPECT_NE(j.find("\"surfaces\":\"\""), std::string::npos);
  EXPECT_EQ(j, e.overlay(123).to_json());
}

TEST(Overlay, TrackingAndWallBearingsAreRelativeToTheUser) {
  auto feed = [](GuideEngine& e, double yaw, int state, int64_t t0) {
    bundle::IntrinsicsSample k;
    k.width = 640, k.height = 480, k.fx = k.fy = 500, k.cx = 319.5, k.cy = 239.5;
    e.on_intrinsics(k);
    e.on_plane(wall("w", {2, 1.25, 4}, {0, 0, -1}, {1, 0, 0}, 4, 2.5));
    bundle::PointCloudRecord pc;  // sparse points on the wall: their sight rays free the floor in front of the user
    pc.t_ns = t0;
    for (double x = 0.5; x <= 3.5; x += 0.25)
      for (double y = 0.8; y <= 1.6; y += 0.2) pc.xyz.insert(pc.xyz.end(), {float(x), float(y), 4.f});
    for (int k = 0; k < 6; ++k) {
      e.on_pose(pose_at(t0 + k * 100'000'000, 2, 1, yaw, state));
      if (k == 0) e.on_points(pc);
    }
    std::vector<Instruction> out;
    e.update(t0 + 500'000'000, out);
  };
  GuideEngine lost;
  feed(lost, 0, 3, 1'000'000'000);
  EXPECT_EQ(lost.overlay(1'000'000'000).why_id, "why.tracking_lost");
  GuideEngine weak;
  feed(weak, 0, 2, 1'000'000'000);
  EXPECT_EQ(weak.overlay(1'000'000'000).why_id, "why.limited");

  // The unscanned wall is at +z. Facing +z it is ahead; yaw is positive to the left (+x), so at -90 deg it is on the left.
  GuideEngine a, l, r;
  feed(a, 0, 1, 1'000'000'000);
  feed(l, -kPi / 2, 1, 1'000'000'000);
  feed(r, kPi / 2, 1, 1'000'000'000);
  auto ma = a.overlay(2'000'000'000);
  ASSERT_EQ(ma.mode, "target") << ma.to_json();
  EXPECT_EQ(ma.walls, 1);
  EXPECT_TRUE(ma.wall_found);
  ASSERT_EQ(ma.wall_list.size(), 1u);
  EXPECT_EQ(ma.wall_list[0].seen_pct, 0);
  EXPECT_NEAR(ma.wall_list[0].x0, 0, 1e-6);
  EXPECT_NEAR(ma.wall_list[0].x1, 4, 1e-6);
  EXPECT_NEAR(ma.wall_list[0].z0, 4, 1e-6);
  EXPECT_EQ(ma.why_id, "why.wall_ahead");
  EXPECT_EQ(l.overlay(2'000'000'000).why_id, "why.wall_left");
  EXPECT_EQ(r.overlay(2'000'000'000).why_id, "why.wall_right");
}

TEST(Overlay, GoldenFromFixedSeedSimRun) {
  auto got = samples_of(golden_run());
  fs::path g = fs::path(CK_TEST_GOLDEN_DIR) / "overlay_box_seed1.jsonl";
  const char* upd = std::getenv("CK_UPDATE_GOLDEN");
  if (upd && *upd) {
    fs::create_directories(g.parent_path());
    std::ofstream(g, std::ios::binary) << got;
  }
  std::ifstream f(g, std::ios::binary);
  ASSERT_TRUE(f.good()) << "missing golden " << g << " (run with CK_UPDATE_GOLDEN=1)";
  std::stringstream ss;
  ss << f.rdbuf();
  std::string want = ss.str();
  want.erase(std::remove(want.begin(), want.end(), '\r'), want.end());  // git autocrlf on Windows
  EXPECT_EQ(got, want);
}

TEST(Overlay, SimRunIsByteIdenticalTwiceAndEventsStaySmall) {
  auto a = golden_run(), b = golden_run();
  EXPECT_EQ(a, b);
  sim::Config c;
  c.room = "furniture";
  c.policy = "planner";
  c.seed = 3;
  c.max_s = 45;
  c.overlay_every_s = 5;
  auto r = sim::simulate(c);
  auto p = r.find("\"overlay_max_bytes\":");
  ASSERT_NE(p, std::string::npos);
  size_t mx = std::stoul(r.substr(p + 20));
  EXPECT_GT(mx, 100u);
  EXPECT_LT(mx, 4096u) << "overlay event must stay under 4 KB";
  // Without the option the report carries no overlay fields (existing reports are unchanged).
  c.room = "box";
  c.max_s = 15;
  c.overlay_every_s = 0;
  EXPECT_EQ(sim::simulate(c).find("overlay_"), std::string::npos);
}
