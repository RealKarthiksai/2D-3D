// Pre-scan sensor check (ADR 0035): verdicts on synthetic 200 Hz IMU windows.
#include <gtest/gtest.h>

#include <cmath>
#include <numbers>
#include <random>
#include <string>
#include <vector>

#include "capturekit/capi/capturekit.h"
#include "capturekit/quality/sensor_check.hpp"

using namespace capturekit;
using capturekit::quality::sensor_check_json;

namespace {
constexpr double kG = 9.80665;

// `seconds` of 200 Hz samples around `base`, Gaussian noise sigma per axis; `shake` adds a 3 Hz swing of that size.
std::vector<bundle::ImuSample> imu(double seconds, const double base[3], double sigma, uint32_t seed,
                                   double shake = 0) {
  std::mt19937 rng(seed);
  std::normal_distribution<double> n(0, sigma);
  std::vector<bundle::ImuSample> out;
  for (int i = 0; i < int(seconds * 200); ++i) {
    bundle::ImuSample s;
    s.t_ns = 1'000'000'000LL + int64_t(i) * 5'000'000LL;
    const double sw = shake * std::sin(2 * std::numbers::pi * 3 * i / 200.0);
    for (int a = 0; a < 3; ++a) s.v[a] = base[a] + n(rng) + (a == 0 ? sw : 0);
    out.push_back(s);
  }
  return out;
}

const double kZero[3] = {0, 0, 0};
const double kUp[3] = {0, kG, 0};

std::string field(const std::string& j, const std::string& key) {
  auto at = j.find("\"" + key + "\":");
  if (at == std::string::npos) return "<missing>";
  at += key.size() + 3;
  if (j[at] == '"') return j.substr(at + 1, j.find('"', at + 1) - at - 1);
  return j.substr(at, j.find_first_of(",}", at) - at);
}
}  // namespace

TEST(SensorCheck, StillPhonePasses) {
  auto j = sensor_check_json(imu(2.5, kZero, 0.005, 1), imu(2.5, kUp, 0.005, 2), 3, 3, 2.5, 0);
  EXPECT_EQ(field(j, "action"), "finish") << j;
  EXPECT_EQ(field(j, "sensor_check.result"), "pass") << j;
  EXPECT_EQ(field(j, "message"), "Sensors look good.");
  EXPECT_EQ(field(j, "sensor_check.reasons"), "");
  EXPECT_EQ(field(j, "sensor_check.n_gyro"), "401");
  EXPECT_EQ(j.find("null"), std::string::npos);
}

TEST(SensorCheck, GyroBiasWarnsThenFails) {
  const double warn[3] = {0.05, 0, 0}, fail[3] = {0, 0.2, 0};
  auto w = sensor_check_json(imu(2.5, warn, 0.005, 1), imu(2.5, kUp, 0.005, 2), 3, 3, 2.5, 0);
  EXPECT_EQ(field(w, "sensor_check.result"), "warn") << w;
  EXPECT_EQ(field(w, "sensor_check.reasons"), "GYRO_BIAS_HIGH");
  EXPECT_NE(field(w, "message").find("small drift"), std::string::npos);
  auto f = sensor_check_json(imu(2.5, fail, 0.005, 1), imu(2.5, kUp, 0.005, 2), 3, 3, 2.5, 0);
  EXPECT_EQ(field(f, "sensor_check.result"), "fail") << f;
  EXPECT_EQ(field(f, "action"), "finish");
  EXPECT_NE(field(f, "message").find("look wrong"), std::string::npos);
}

TEST(SensorCheck, ShakingRestartsThenFinishesAfterMaxRestarts) {
  auto g = imu(1.0, kZero, 0.005, 1, /*shake=*/1.0), a = imu(1.0, kUp, 0.005, 2);
  auto r = sensor_check_json(g, a, 3, 3, 1.0, 0);
  EXPECT_EQ(field(r, "sensor_check.result"), "moving") << r;
  EXPECT_EQ(field(r, "action"), "restart");
  EXPECT_EQ(field(sensor_check_json(g, a, 3, 3, 1.0, 2), "action"), "restart");
  auto f = sensor_check_json(g, a, 3, 3, 1.0, 3);
  EXPECT_EQ(field(f, "action"), "finish") << f;
  EXPECT_EQ(field(f, "sensor_check.result"), "moving");
  EXPECT_NE(field(f, "message").find("Could not hold still"), std::string::npos);
}

TEST(SensorCheck, GravityOffAndUnreliableFlagWarn) {
  const double low[3] = {0, 9.0, 0};
  auto j = sensor_check_json(imu(2.5, kZero, 0.005, 1), imu(2.5, low, 0.005, 2), 3, 3, 2.5, 0);
  EXPECT_EQ(field(j, "sensor_check.result"), "warn") << j;
  EXPECT_EQ(field(j, "sensor_check.reasons"), "ACCEL_GRAVITY_OFF");
  auto u = sensor_check_json(imu(2.5, kZero, 0.005, 1), imu(2.5, kUp, 0.005, 2), 3, 0, 2.5, 0);
  EXPECT_EQ(field(u, "sensor_check.result"), "warn") << u;
  EXPECT_EQ(field(u, "sensor_check.reasons"), "SENSOR_UNRELIABLE");
  EXPECT_NE(field(u, "message").find("unreliable"), std::string::npos);
}

TEST(SensorCheck, NoSamplesWaitsThenTimesOutWithoutNaN) {
  auto c = sensor_check_json({}, {}, -1, -1, 1.0, 0);
  EXPECT_EQ(field(c, "action"), "continue") << c;
  EXPECT_EQ(field(c, "sensor_check.result"), "insufficient");
  auto t = sensor_check_json({}, {}, -1, -1, 9.0, 0);
  EXPECT_EQ(field(t, "action"), "finish") << t;
  EXPECT_EQ(field(t, "sensor_check.result"), "insufficient");
  EXPECT_EQ(field(t, "sensor_check.reasons"), "NO_GYRO,NO_ACCEL");
  EXPECT_EQ(field(t, "sensor_check.gyro_bias_rad_s"), "0");
  EXPECT_EQ(t.find("null"), std::string::npos);
}

TEST(SensorCheck, OutOfOrderSamplesAreSkippedAndOutputIsDeterministic) {
  auto g = imu(2.5, kZero, 0.005, 1), a = imu(2.5, kUp, 0.005, 2);
  auto dup = g;
  dup.insert(dup.begin() + 100, g[50]);  // stale t_ns: skipped
  auto j1 = sensor_check_json(dup, a, 3, 3, 2.5, 0);
  auto j2 = sensor_check_json(dup, a, 3, 3, 2.5, 0);
  EXPECT_EQ(j1, j2);
  EXPECT_EQ(j1, sensor_check_json(g, a, 3, 3, 2.5, 0));
}

TEST(SensorCheck, CApiMatchesTheCoreAndRejectsBadInput) {
  auto g = imu(2.5, kZero, 0.005, 1), a = imu(2.5, kUp, 0.005, 2);
  std::vector<ck_imu> cg(g.size()), ca(a.size());
  for (size_t i = 0; i < g.size(); ++i) cg[i] = {g[i].t_ns, {g[i].v[0], g[i].v[1], g[i].v[2]}};
  for (size_t i = 0; i < a.size(); ++i) ca[i] = {a[i].t_ns, {a[i].v[0], a[i].v[1], a[i].v[2]}};
  const char* in = "{\"gyro_accuracy\":3, \"accel_accuracy\":3, \"elapsed_s\":2.5, \"restarts\":0}";
  char out[2048];
  ASSERT_EQ(ck_sensor_check(cg.data(), cg.size(), ca.data(), ca.size(), in, out, sizeof out), CK_OK);
  EXPECT_EQ(std::string(out), sensor_check_json(g, a, 3, 3, 2.5, 0));
  EXPECT_EQ(ck_sensor_check(nullptr, 0, nullptr, 0, "{\"bogus\":1}", out, sizeof out), CK_ERR_ARG);
  EXPECT_EQ(ck_sensor_check(nullptr, 0, nullptr, 0, "{\"restarts\":\"1\"}", out, sizeof out), CK_ERR_ARG);
  EXPECT_EQ(ck_sensor_check(cg.data(), cg.size(), ca.data(), ca.size(), in, out, 16), CK_ERR_ARG);
  EXPECT_STREQ(out, "");
}
