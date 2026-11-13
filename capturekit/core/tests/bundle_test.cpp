#include <gtest/gtest.h>

#include <cmath>
#include <filesystem>
#include <fstream>
#include <random>

#include "capturekit/bundle/codec.hpp"
#include "capturekit/bundle/finalize.hpp"
#include "capturekit/bundle/stream_reader.hpp"
#include "capturekit/bundle/stream_writer.hpp"
#include "capturekit/util/json.hpp"
#include "capturekit/version.hpp"

namespace fs = std::filesystem;
using namespace capturekit::bundle;

namespace {
fs::path tmpdir(const std::string& name) {
  fs::path p = fs::path(CK_TEST_TMP) / name;
  fs::remove_all(p);
  fs::create_directories(p);
  return p;
}

StreamCounts write_sample(const fs::path& file, int n_pose = 60) {
  StreamWriter w;
  StreamWriterOptions o;
  o.chunk_size = 2048;
  o.flush_interval_ns = 100'000'000;
  EXPECT_TRUE(w.open(file.string(), o));
  IntrinsicsSample k;
  k.t_ns = 1000; k.width = 640; k.height = 480; k.fx = 500; k.fy = 501; k.cx = 319.5; k.cy = 239.5;
  k.distortion = {0.01, -0.002, 0, 0};
  EXPECT_TRUE(w.write(k));
  for (int i = 0; i < n_pose; ++i) {
    int64_t t = 1000 + int64_t(i) * 33'333'333;
    PoseSample p;
    p.t_ns = t;
    p.t_world_from_camera[0] = 0.01 * i; p.t_world_from_camera[1] = 1.5; p.t_world_from_camera[2] = -0.5;
    double a = 0.01 * i;
    p.q_world_from_camera_wxyz[0] = std::cos(a); p.q_world_from_camera_wxyz[2] = std::sin(a);
    p.tracking_state = i < 3 ? TrackingState::Limited : TrackingState::Normal;
    p.tracking_reason = i < 3 ? TrackingReason::Initializing : TrackingReason::None;
    EXPECT_TRUE(w.write(p));
    for (int j = 0; j < 6; ++j) {
      ImuSample s; s.t_ns = t + j * 5'000'000; s.v[0] = 0.1 * j; s.v[1] = -9.8; s.v[2] = 0.0;
      EXPECT_TRUE(w.write_gyro(s));
      EXPECT_TRUE(w.write_accel(s));
    }
    if (i % 6 == 0) {
      FrameRecord f; f.t_ns = t + 1; f.frame_id = uint32_t(i / 6); f.keyframe = (i % 12 == 0);
      if (f.keyframe) f.file = "keyframes/" + std::to_string(i) + ".jpg";
      f.sharpness = 123.5; f.reasons = {"OK", ""}; f.tracking_state = TrackingState::Normal;
      EXPECT_TRUE(w.write(f));
    }
  }
  EventRecord e; e.t_ns = 5000; e.source = "session"; e.code = "SESSION_STATE"; e.message = "scanning";
  e.attrs = {{"from", "calibrating"}, {"to", "scanning"}};
  EXPECT_TRUE(w.write(e));
  PointCloudRecord pc; pc.t_ns = 6000; pc.xyz = {1.f, 2.f, 3.f, -4.f, 5.5f, 6.f};
  EXPECT_TRUE(w.write(pc));
  PlaneRecord pl; pl.t_ns = 7000; pl.id = "p1"; pl.kind = "floor"; pl.axis_u[0] = 0; pl.axis_u[2] = 1; pl.extent_u = 2; pl.extent_v = 3;
  EXPECT_TRUE(w.write(pl));
  return w.close();
}
}  // namespace

TEST(Bundle, RoundTripCpp) {
  auto dir = tmpdir("rt");
  auto c = write_sample(dir / "streams.mcap");
  EXPECT_EQ(c.get(topic::kPose), 60u);
  EXPECT_EQ(c.get(topic::kGyro), 360u);
  EXPECT_EQ(c.keyframes, 5u);
  auto r = read_streams((dir / "streams.mcap").string());
  ASSERT_TRUE(r.opened);
  EXPECT_TRUE(r.finalized);
  EXPECT_EQ(r.undecodable, 0u);
  ASSERT_EQ(r.poses.size(), 60u);
  EXPECT_EQ(r.poses[0].tracking_state, TrackingState::Limited);
  EXPECT_EQ(r.poses[0].q_world_from_camera_wxyz[0], 1.0);
  EXPECT_DOUBLE_EQ(r.poses[59].t_world_from_camera[0], 0.59);
  EXPECT_DOUBLE_EQ(r.poses[59].q_world_from_camera_wxyz[2], std::sin(0.59));
  ASSERT_EQ(r.intrinsics.size(), 1u);
  EXPECT_EQ(r.intrinsics[0].distortion.size(), 4u);
  EXPECT_DOUBLE_EQ(r.intrinsics[0].fy, 501);
  ASSERT_EQ(r.frames.size(), 10u);
  EXPECT_EQ(r.frames[0].reasons.size(), 2u);
  ASSERT_EQ(r.events.size(), 1u);
  EXPECT_EQ(r.events[0].attrs[1].second, "scanning");
  ASSERT_EQ(r.points.size(), 1u);
  EXPECT_FLOAT_EQ(r.points[0].xyz[4], 5.5f);
  ASSERT_EQ(r.planes.size(), 1u);
  EXPECT_EQ(r.planes[0].axis_u[0], 0.0);  // zero survives proto3 omission
  EXPECT_EQ(r.planes[0].normal[1], 1.0);
}

TEST(Bundle, RejectsNonMonotonic) {
  auto dir = tmpdir("mono");
  StreamWriter w;
  ASSERT_TRUE(w.open((dir / "s.mcap").string()));
  PoseSample p; p.t_ns = 10;
  EXPECT_TRUE(w.write(p));
  EXPECT_FALSE(w.write(p));
  p.t_ns = 5;
  EXPECT_FALSE(w.write(p));
  auto c = w.close();
  EXPECT_EQ(c.rejected_non_monotonic, 2u);
  EXPECT_EQ(c.get(topic::kPose), 1u);
}

TEST(Bundle, UnfinalizedFileIsRecoverable) {
  auto dir = tmpdir("unfin");
  auto path = (dir / "s.mcap").string();
  {
    StreamWriter w;
    StreamWriterOptions o; o.chunk_size = 1024; o.flush_interval_ns = 50'000'000;
    ASSERT_TRUE(w.open(path, o));
    for (int i = 0; i < 100; ++i) { PoseSample p; p.t_ns = 1 + int64_t(i) * 33'000'000; ASSERT_TRUE(w.write(p)); }
    w.flush();
    // Simulate a crash: copy the file before close() writes the summary.
    fs::copy_file(path, dir / "crashed.mcap");
  }
  auto r = read_streams((dir / "crashed.mcap").string());
  EXPECT_FALSE(r.finalized);
  EXPECT_EQ(r.poses.size(), 100u);
}

TEST(Bundle, TruncationFuzzNeverCrashes) {
  auto dir = tmpdir("fuzz");
  write_sample(dir / "full.mcap", 30);
  std::ifstream in(dir / "full.mcap", std::ios::binary);
  std::string bytes((std::istreambuf_iterator<char>(in)), {});
  std::mt19937 rng(42);
  size_t prev_poses = 0;
  for (size_t cut = 0; cut <= bytes.size(); cut += std::max<size_t>(1, bytes.size() / 400)) {
    auto p = dir / "cut.mcap";
    { std::ofstream o(p, std::ios::binary | std::ios::trunc); o.write(bytes.data(), std::streamsize(cut)); }
    auto r = read_streams(p.string());
    EXPECT_LE(r.poses.size(), 30u);
    if (cut == bytes.size()) EXPECT_TRUE(r.finalized);
    prev_poses = r.poses.size();
  }
  EXPECT_EQ(prev_poses, 30u);
  // Random byte corruption must not crash either.
  for (int k = 0; k < 200; ++k) {
    std::string b = bytes;
    for (int j = 0; j < 4; ++j) b[rng() % b.size()] = char(rng());
    auto p = dir / "bad.mcap";
    { std::ofstream o(p, std::ios::binary | std::ios::trunc); o.write(b.data(), std::streamsize(b.size())); }
    (void)read_streams(p.string());
  }
}

TEST(Bundle, CodecRejectsGarbage) {
  std::byte junk[] = {std::byte(0x0a), std::byte(0xff), std::byte(0xff)};
  EXPECT_FALSE(codec::decode_pose(junk, 3).has_value());
  EXPECT_FALSE(codec::decode_event(junk, 3).has_value());
}

TEST(Bundle, Sha256Vectors) {
  EXPECT_EQ(sha256_hex_bytes("", 0), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  EXPECT_EQ(sha256_hex_bytes("abc", 3), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  std::string m(1000000, 'a');
  EXPECT_EQ(sha256_hex_bytes(m.data(), m.size()), "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0");
}

TEST(Bundle, ManifestAndCamera) {
  auto dir = tmpdir("manifest");
  auto c = write_sample(dir / "streams.mcap");
  fs::create_directories(dir / "keyframes");
  { std::ofstream(dir / "keyframes" / "000000.jpg") << "jpeg"; }
  BundleMeta m; m.bundle_id = "b1"; m.session_id = "s1"; m.model = "test \"quoted\"";
  IntrinsicsSample k; k.width = 640; k.height = 480; k.fx = k.fy = 500; k.cx = 319.5; k.cy = 239.5;
  ASSERT_TRUE(write_camera_json(dir.string(), k, m));
  auto files = list_files(dir.string());
  ASSERT_EQ(files.size(), 3u);
  EXPECT_EQ(files[0].path, "camera.json");
  EXPECT_EQ(files[1].role, "keyframe");
  EXPECT_EQ(files[1].sha256, sha256_hex_bytes("jpeg", 4));
  ASSERT_TRUE(write_manifest(dir.string(), m, c, files));
  EXPECT_TRUE(fs::exists(dir / "manifest.json"));
  EXPECT_FALSE(fs::exists(dir / "manifest.json.tmp"));
}

TEST(Json, FlatParse) {
  auto o = capturekit::json::parse_flat(R"({"a": 1.5, "b": "x\"y", "c": true, "d": -2e-3})");
  ASSERT_TRUE(o);
  EXPECT_DOUBLE_EQ(std::get<double>(o->at("a")), 1.5);
  EXPECT_EQ(std::get<std::string>(o->at("b")), "x\"y");
  EXPECT_TRUE(std::get<bool>(o->at("c")));
  EXPECT_DOUBLE_EQ(std::get<double>(o->at("d")), -0.002);
  std::string err;
  EXPECT_FALSE(capturekit::json::parse_flat(R"({"a": {"b": 1}})", &err));
  EXPECT_FALSE(err.empty());
  EXPECT_FALSE(capturekit::json::parse_flat(R"({"a": 1} x)"));
}

TEST(Version, Core) { EXPECT_STREQ(capturekit::core_version(), "0.0.1"); }
