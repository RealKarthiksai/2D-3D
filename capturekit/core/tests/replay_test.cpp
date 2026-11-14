// Phase 3: replay sinks.
#include <gtest/gtest.h>

#include "capturekit/replay/metrics_sink.hpp"

using namespace capturekit;
using namespace capturekit::replay;

namespace {
bundle::PoseSample pose(int64_t t, double x, bundle::TrackingState s = bundle::TrackingState::Normal) {
  bundle::PoseSample p;
  p.t_ns = t;
  p.t_world_from_camera[0] = x;
  p.tracking_state = s;
  return p;
}

void feed(ReplaySink& s) {
  const int64_t ms = 1'000'000;
  for (int64_t i = 0; i < 100; ++i) {
    auto st = (i >= 40 && i < 60) ? bundle::TrackingState::NotAvailable : bundle::TrackingState::Normal;
    s.on_pose(pose(i * 33 * ms, 0.01 * double(i), st));
    bundle::ImuSample g;
    g.t_ns = i * 33 * ms + 1;
    s.on_gyro(g);
  }
  bundle::ImuSample late;
  late.t_ns = 5 * ms;  // non-monotonic
  s.on_gyro(late);
  bundle::EventRecord e;
  e.t_ns = 10 * ms;
  e.source = "platform";
  e.code = "thermal";
  s.on_event(e);
  s.on_end(99 * 33 * ms);
}
}  // namespace

TEST(Replay, MetricsSinkTrackingIntervalsAndRejects) {
  MetricsSink m;
  feed(m);
  ASSERT_EQ(m.tracking_intervals().size(), 1u);
  EXPECT_EQ(m.tracking_intervals()[0].t0_ns, 40 * 33 * 1'000'000LL);
  EXPECT_EQ(m.tracking_intervals()[0].t1_ns, 60 * 33 * 1'000'000LL);
  EXPECT_EQ(m.rejected("gyro"), 1u);
  auto j = m.report_json();
  EXPECT_NE(j.find("\"platform.thermal\":1"), std::string::npos) << j;
  EXPECT_NE(j.find("\"rejected_non_monotonic\":1"), std::string::npos) << j;
}

TEST(Replay, ReportIsDeterministic) {
  auto a = make_sink("metrics", "{}");
  auto b = make_sink("metrics", "{}");
  feed(*a);
  feed(*b);
  EXPECT_EQ(a->report_json(), b->report_json());
}

TEST(Replay, OpenIntervalClosedAtEnd) {
  MetricsSink m;
  m.on_pose(pose(0, 0));
  m.on_pose(pose(10, 0, bundle::TrackingState::Limited));
  m.on_end(50);
  ASSERT_EQ(m.tracking_intervals().size(), 1u);
  EXPECT_EQ(m.tracking_intervals()[0].t1_ns, 50);
}

TEST(Replay, UnknownSinkThrows) { EXPECT_THROW(make_sink("nope", "{}"), std::invalid_argument); }
