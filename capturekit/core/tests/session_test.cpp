// Phase 6: C API session state machine, bounded queues, keyframe handshake, governor pause,
// threaded ingest stress, crash recovery.
#include <gtest/gtest.h>

#include <atomic>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <random>
#include <set>
#include <string>
#include <thread>
#include <vector>

#include "capturekit/bundle/stream_reader.hpp"
#include "capturekit/capi/capturekit.h"
#include "capturekit/session/session.hpp"

namespace fs = std::filesystem;
using namespace capturekit;

namespace {
constexpr int64_t kMs = 1'000'000;

fs::path fresh(const std::string& name) {
  fs::path d = fs::path(CK_TEST_TMP) / "session" / name;
  fs::remove_all(d);
  fs::create_directories(d.parent_path());
  return d;
}

std::vector<uint8_t> texture(int w, int h, uint32_t seed) {
  std::vector<uint8_t> px(size_t(w * h), 128);
  std::mt19937 g(seed);
  for (int k = 0; k < 300; ++k) {
    int x0 = int(g() % uint32_t(w)), y0 = int(g() % uint32_t(h)), c = 30 + int(g() % 190);
    for (int y = y0; y < std::min(h, y0 + 16); ++y)
      for (int x = x0; x < std::min(w, x0 + 16); ++x) px[size_t(y * w + x)] = uint8_t(c);
  }
  return px;
}

void write_jpeg(const fs::path& p, bool complete = true) {
  fs::create_directories(p.parent_path());
  std::ofstream f(p, std::ios::binary);
  const unsigned char head[] = {0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};
  f.write(reinterpret_cast<const char*>(head), sizeof head);
  if (complete) {
    const unsigned char tail[] = {0xFF, 0xD9};
    f.write(reinterpret_cast<const char*>(tail), 2);
  }
}

std::vector<std::string> drain(ck_session* s) {
  std::vector<std::string> out;
  char buf[1 << 16];
  for (size_t n; (n = ck_poll_events(s, buf, sizeof buf)) > 0;) {
    std::string chunk(buf, n);
    size_t a = 0;
    for (size_t b; (b = chunk.find('\n', a)) != std::string::npos; a = b + 1) out.push_back(chunk.substr(a, b - a));
  }
  return out;
}

uint32_t json_u32(const std::string& line, const std::string& key) {
  auto p = line.find("\"" + key + "\":");
  return uint32_t(std::stoul(line.substr(p + key.size() + 3)));
}
std::string json_str(const std::string& line, const std::string& key) {
  auto p = line.find("\"" + key + "\":\"") + key.size() + 4;
  return line.substr(p, line.find('"', p) - p);
}

// Feeds a synthetic walk: 30 Hz poses moving 1.5 cm per frame, 200 Hz gyro, 10 Hz frames.
struct Feeder {
  ck_session* s;
  fs::path dir;
  int w = 320, h = 240;
  uint32_t frame_id = 0;
  bool ack = true;
  std::set<std::string> written;

  void step(int64_t i, bool frames = true) {
    int64_t t = i * 33 * kMs;
    ck_pose p{t, {0.015 * double(i), 0, 0}, {1, 0, 0, 0}, 1, 0};
    EXPECT_EQ(ck_ingest_pose(s, &p, CK_POSE_OPENCV), CK_OK);
    for (int k = 0; k < 6; ++k) {
      ck_imu g{t + k * 5 * kMs, {0.01, 0, 0}};
      ck_ingest_gyro(s, &g);
    }
    if (frames && i % 3 == 0) {
      auto px = texture(w, h, uint32_t(i));
      ck_frame f{t + 1, frame_id++, 8 * kMs, px.data(), w, h, w};
      ck_ingest_frame(s, &f);
    }
  }
  void handle_events() {
    for (auto& e : drain(s)) {
      if (e.find("\"keyframe_request\"") == std::string::npos) continue;
      auto file = json_str(e, "file");
      if (ack) {
        write_jpeg(dir / file);
        written.insert(file);
        EXPECT_EQ(ck_keyframe_written(s, json_u32(e, "frame_id"), 1), CK_OK);
      }
    }
  }
};

ck_session* make(const fs::path& d, const char* cfg) {
  ck_status err;
  auto* s = ck_session_create(d.c_str(), cfg, &err);
  EXPECT_NE(s, nullptr);
  EXPECT_EQ(err, CK_OK);
  return s;
}

void intr(ck_session* s, int64_t t = 0) {
  ck_intrinsics k{t, 320, 240, 250, 250, 159.5, 119.5};
  EXPECT_EQ(ck_ingest_intrinsics(s, &k), CK_OK);
}
}  // namespace

TEST(SessionStateMachine, TransitionTable) {
  using session::State;
  using session::transition_allowed;
  EXPECT_TRUE(transition_allowed(State::Idle, State::Calibrating));
  EXPECT_TRUE(transition_allowed(State::Calibrating, State::Scanning));
  EXPECT_TRUE(transition_allowed(State::Scanning, State::Paused));
  EXPECT_TRUE(transition_allowed(State::Paused, State::Calibrating));
  EXPECT_TRUE(transition_allowed(State::Scanning, State::Finalizing));
  EXPECT_TRUE(transition_allowed(State::Finalizing, State::Complete));
  EXPECT_FALSE(transition_allowed(State::Idle, State::Scanning));
  EXPECT_FALSE(transition_allowed(State::Idle, State::Finalizing));
  EXPECT_FALSE(transition_allowed(State::Complete, State::Calibrating));
  EXPECT_FALSE(transition_allowed(State::Finalizing, State::Paused));
  EXPECT_FALSE(transition_allowed(State::Complete, State::Failed));
}

TEST(SessionStateMachine, IllegalApiCalls) {
  auto d = fresh("illegal");
  auto* s = make(d, "{\"threaded\":0}");
  ck_pose p{0, {0, 0, 0}, {1, 0, 0, 0}, 1, 0};
  EXPECT_EQ(ck_ingest_pose(s, &p, CK_POSE_OPENCV), CK_ERR_STATE);  // before start
  EXPECT_EQ(ck_session_pause(s, 0), CK_ERR_STATE);
  EXPECT_EQ(ck_session_resume(s, 0), CK_ERR_STATE);
  EXPECT_EQ(ck_session_finalize(s), CK_ERR_STATE);
  EXPECT_EQ(ck_session_start(s, 0), CK_OK);
  EXPECT_EQ(ck_session_start(s, 1), CK_ERR_STATE);
  EXPECT_EQ(ck_session_resume(s, 1), CK_ERR_STATE);  // not paused
  EXPECT_EQ(ck_session_pause(s, 2), CK_OK);
  EXPECT_EQ(ck_session_state(s), CK_STATE_PAUSED);
  EXPECT_EQ(ck_session_resume(s, 3), CK_OK);
  EXPECT_EQ(ck_session_state(s), CK_STATE_CALIBRATING);
  ck_intrinsics bad{0, 0, 0, 0, 0, 0, 0};
  EXPECT_EQ(ck_ingest_intrinsics(s, &bad), CK_ERR_ARG);
  EXPECT_EQ(ck_ingest_pose(s, &p, 7), CK_ERR_ARG);
  EXPECT_EQ(ck_session_pump(s), CK_OK);
  ck_session_destroy(s);
  // A directory that already holds a session is refused (protects data).
  ck_status err;
  EXPECT_EQ(ck_session_create(d.c_str(), "{}", &err), nullptr);
  EXPECT_EQ(err, CK_ERR_IO);
  EXPECT_EQ(ck_session_create(fresh("badcfg").c_str(), "{\"nope\":1}", &err), nullptr);
  EXPECT_EQ(err, CK_ERR_ARG);
}

TEST(SessionEndToEnd, SyncScanFinalizesValidBundle) {
  auto d = fresh("e2e");
  auto* s = make(d, "{\"threaded\":0, \"platform\":\"test\"}");
  Feeder f{s, d};
  ASSERT_EQ(ck_session_start(s, 0), CK_OK);
  intr(s);
  for (int64_t i = 0; i < 300; ++i) {
    f.step(i);
    ck_session_pump(s);
    f.handle_events();
  }
  EXPECT_EQ(ck_session_state(s), CK_STATE_SCANNING);
  ASSERT_EQ(ck_session_stop(s, 300 * 33 * kMs), CK_OK);
  ck_session_pump(s);
  f.handle_events();  // final keyframe request from the open window
  ck_session_pump(s);
  ASSERT_EQ(ck_session_finalize(s), CK_OK);
  EXPECT_EQ(ck_session_state(s), CK_STATE_COMPLETE);
  ck_session_destroy(s);

  EXPECT_TRUE(fs::exists(d / "manifest.json"));
  EXPECT_TRUE(fs::exists(d / "camera.json"));
  EXPECT_FALSE(fs::exists(d / "session.json"));
  auto c = bundle::read_streams((d / "streams.mcap").string());
  EXPECT_TRUE(c.finalized);
  EXPECT_EQ(c.poses.size(), 300u);
  ASSERT_GT(c.frames.size(), 50u);
  int64_t prev = -1;
  size_t kf = 0;
  for (auto& fr : c.frames) {
    EXPECT_GT(fr.t_ns, prev);  // reorder buffer keeps /frame_index strictly increasing
    prev = fr.t_ns;
    if (fr.keyframe) {
      ++kf;
      EXPECT_TRUE(fs::exists(d / fr.file)) << fr.file;
    }
    EXPECT_GT(fr.sharpness, 0);
  }
  EXPECT_GT(kf, 5u);
  EXPECT_EQ(kf, f.written.size());
  std::set<std::string> codes;
  for (auto& e : c.events) codes.insert(e.code);
  for (auto* code : {"SESSION_STARTED", "QUALITY_CONFIG", "SESSION_STOPPED", "SESSION_FINALIZED"})
    EXPECT_TRUE(codes.count(code)) << code;
}

TEST(SessionQueues, OverflowIsDroppedCountedAndLogged) {
  auto d = fresh("overflow");
  auto* s = make(d, "{\"threaded\":0, \"queue_capacity\":16, \"frame_slots\":2}");
  ck_session_start(s, 0);
  int full = 0;
  for (int i = 0; i < 100; ++i) {
    ck_imu g{i * 5 * kMs, {0, 0, 0}};
    full += ck_ingest_gyro(s, &g) == CK_ERR_FULL;
  }
  EXPECT_EQ(full, 100 - 16 + 1);  // start's control item uses one queue entry
  ck_session_pump(s);
  auto px = texture(64, 48, 1);
  int slot_full = 0;
  for (uint32_t i = 0; i < 5; ++i) {
    ck_frame fr{int64_t(1000 + i) * kMs, i, 0, px.data(), 64, 48, 64};
    slot_full += ck_ingest_frame(s, &fr) == CK_ERR_FULL;
  }
  EXPECT_EQ(slot_full, 3);
  ck_session_pump(s);
  bool logged = false;
  for (auto& e : drain(s)) logged |= e.find("\"ingest_drop\"") != std::string::npos;
  EXPECT_TRUE(logged);
  intr(s);
  ck_session_finalize(s);
  ck_session_destroy(s);
  auto c = bundle::read_streams((d / "streams.mcap").string());
  bool drop_event = false;
  for (auto& e : c.events) drop_event |= e.code == "INGEST_DROP";
  EXPECT_TRUE(drop_event);
}

TEST(SessionGovernor, CriticalThermalPausesAndBlocksResume) {
  auto d = fresh("governor");
  auto* s = make(d, "{\"threaded\":0}");
  Feeder f{s, d};
  ck_session_start(s, 0);
  intr(s);
  for (int64_t i = 0; i < 30; ++i) f.step(i, false);
  ck_session_pump(s);
  EXPECT_EQ(ck_session_state(s), CK_STATE_SCANNING);
  ck_platform hot{3, 0, 80, 0, 20};
  ck_ingest_platform(s, 31 * 33 * kMs, &hot);
  ck_session_pump(s);
  EXPECT_EQ(ck_session_state(s), CK_STATE_PAUSED);
  ck_policy pol;
  ck_policy_get(s, &pol);
  EXPECT_TRUE(pol.must_pause);
  EXPECT_EQ(ck_session_resume(s, 32 * 33 * kMs), CK_ERR_POLICY);
  bool gov = false;
  for (auto& e : drain(s)) gov |= e.find("\"governor\"") != std::string::npos;
  EXPECT_TRUE(gov);
  EXPECT_EQ(ck_session_finalize(s), CK_OK);
  ck_session_destroy(s);
}

TEST(SessionKeyframes, UnackedRequestsExpireAndLeaveNoOrphans) {
  auto d = fresh("expire");
  auto* s = make(d, "{\"threaded\":0, \"keyframe_ack_timeout_s\":0.5}");
  Feeder f{s, d};
  f.ack = false;
  ck_session_start(s, 0);
  intr(s);
  for (int64_t i = 0; i < 200; ++i) {
    f.step(i);
    ck_session_pump(s);
  }
  bool expired = false;
  for (auto& e : drain(s)) expired |= e.find("\"keyframe_expired\"") != std::string::npos;
  EXPECT_TRUE(expired);
  write_jpeg(d / "keyframes" / "000000.jpg");  // shell wrote it too late: must not be listed
  ASSERT_EQ(ck_session_finalize(s), CK_OK);
  ck_session_destroy(s);
  auto c = bundle::read_streams((d / "streams.mcap").string());
  size_t missing = 0;
  for (auto& fr : c.frames) {
    EXPECT_FALSE(fr.keyframe);
    for (auto& r : fr.reasons) missing += r == "ENCODE_MISSING";
  }
  EXPECT_GT(missing, 0u);
  EXPECT_FALSE(fs::exists(d / "keyframes" / "000000.jpg"));
}

TEST(SessionThreaded, ConcurrentIngestStress) {
  auto d = fresh("stress");
  auto* s = make(d, "{\"threaded\":1, \"queue_capacity\":512}");
  ck_session_start(s, 0);
  intr(s);
  const int N = 3000;
  std::atomic<int> ok[4] = {}, full[4] = {};
  auto count = [&](int k, ck_status st) {
    if (st == CK_OK) ++ok[k];
    else if (st == CK_ERR_FULL) ++full[k];
    else ADD_FAILURE() << "unexpected status " << st;
  };
  std::thread tp([&] {
    for (int i = 0; i < N; ++i) {
      ck_pose p{i * 33 * kMs, {0.01 * i, 0, 0}, {1, 0, 0, 0}, 1, 0};
      count(0, ck_ingest_pose(s, &p, CK_POSE_OPENCV));
    }
  });
  std::thread tg([&] {
    for (int i = 0; i < N; ++i) {
      ck_imu g{i * 5 * kMs, {0.01, 0, 0}};
      count(1, ck_ingest_gyro(s, &g));
    }
  });
  std::thread ta([&] {
    for (int i = 0; i < N; ++i) {
      ck_imu a{i * 5 * kMs + 1, {0, 9.8, 0}};
      count(2, ck_ingest_accel(s, &a));
    }
  });
  std::thread tf([&] {
    auto px = texture(160, 120, 3);
    for (int i = 0; i < N / 10; ++i) {
      ck_frame fr{i * 330 * kMs + 2, uint32_t(i), 0, px.data(), 160, 120, 160};
      count(3, ck_ingest_frame(s, &fr));
    }
  });
  std::thread tpoll([&] {
    char buf[4096];
    for (int i = 0; i < 2000; ++i) ck_poll_events(s, buf, sizeof buf);
  });
  for (auto* t : {&tp, &tg, &ta, &tf, &tpoll}) t->join();
  ASSERT_EQ(ck_session_stop(s, N * 33 * kMs), CK_OK);
  ASSERT_EQ(ck_session_finalize(s), CK_OK);
  ck_session_destroy(s);
  EXPECT_EQ(ok[0] + full[0], N);
  EXPECT_EQ(ok[1] + full[1], N);
  auto c = bundle::read_streams((d / "streams.mcap").string());
  EXPECT_EQ(c.poses.size(), size_t(ok[0].load()));
  EXPECT_EQ(c.gyro.size(), size_t(ok[1].load()));
  EXPECT_EQ(c.accel.size(), size_t(ok[2].load()));
  EXPECT_TRUE(c.finalized);
}

TEST(SessionRecovery, KilledSessionBecomesRecoveredBundle) {
  auto d = fresh("recover");
  auto* s = make(d, "{\"threaded\":0, \"model\":\"TestPhone\"}");
  Feeder f{s, d};
  ck_session_start(s, 0);
  intr(s);
  for (int64_t i = 0; i < 240; ++i) {
    f.step(i);
    ck_session_pump(s);
    f.handle_events();
  }
  ASSERT_GT(f.written.size(), 2u);
  // Simulate a crash: a half-written JPEG that was acked, a temp file, then no finalize.
  auto victim = *f.written.begin();
  write_jpeg(d / victim, /*complete=*/false);
  write_jpeg(d / "keyframes" / "999999.jpg.tmp");
  ck_session_destroy(s);
  ASSERT_FALSE(fs::exists(d / "manifest.json"));

  char rep[1024];
  ASSERT_EQ(ck_recover(d.c_str(), rep, sizeof rep), CK_OK) << rep;
  std::string r(rep);
  EXPECT_NE(r.find("\"ok\":true"), std::string::npos) << r;
  EXPECT_NE(r.find("\"dropped_frame_records\":1"), std::string::npos) << r;
  EXPECT_TRUE(fs::exists(d / "manifest.json"));
  EXPECT_FALSE(fs::exists(d / "session.json"));
  EXPECT_FALSE(fs::exists(d / "keyframes" / "999999.jpg.tmp"));
  EXPECT_FALSE(fs::exists(d / victim));
  std::ifstream m(d / "manifest.json");
  std::string manifest((std::istreambuf_iterator<char>(m)), std::istreambuf_iterator<char>());
  EXPECT_NE(manifest.find("recovered"), std::string::npos);
  EXPECT_NE(manifest.find("TestPhone"), std::string::npos);
  auto c = bundle::read_streams((d / "streams.mcap").string());
  EXPECT_TRUE(c.finalized);
  bool rec_event = false;
  for (auto& e : c.events) rec_event |= e.code == "SESSION_RECOVERED";
  EXPECT_TRUE(rec_event);
  // Idempotent.
  EXPECT_EQ(ck_recover(d.c_str(), rep, sizeof rep), CK_OK);
  EXPECT_NE(std::string(rep).find("\"already_complete\":true"), std::string::npos);
}

TEST(SessionRecovery, TruncatedStreamsNeverCrash) {
  auto src = fresh("trunc_src");
  auto* s = make(src, "{\"threaded\":0}");
  Feeder f{s, src};
  ck_session_start(s, 0);
  intr(s);
  for (int64_t i = 0; i < 150; ++i) {
    f.step(i);
    ck_session_pump(s);
    f.handle_events();
  }
  ck_session_destroy(s);
  const auto size = fs::file_size(src / "streams.mcap");
  int recovered = 0;
  for (double frac : {0.02, 0.1, 0.33, 0.5, 0.77, 0.99}) {
    auto d = fresh("trunc_" + std::to_string(int(frac * 100)));
    fs::copy(src, d, fs::copy_options::recursive);
    fs::resize_file(d / "streams.mcap", uintmax_t(double(size) * frac));
    char rep[1024];
    auto st = ck_recover(d.c_str(), rep, sizeof rep);
    if (st == CK_OK) {
      ++recovered;
      EXPECT_TRUE(bundle::read_streams((d / "streams.mcap").string()).finalized);
    } else {
      EXPECT_NE(std::string(rep).find("\"error\""), std::string::npos);
    }
  }
  EXPECT_GE(recovered, 4);
}
