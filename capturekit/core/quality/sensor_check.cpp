#include "capturekit/quality/sensor_check.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>

#include "capturekit/util/json.hpp"

namespace capturekit::quality {
namespace {

struct Stats {
  int n = 0;            // samples in the trailing window
  double span_s = 0;    // last t_ns - first t_ns over all kept samples (how long the sensor has delivered)
  double mean_norm = 0; // |mean vector| over the window
  double std_max = 0;   // max per-axis std over the window
};

bool finite3(const bundle::ImuSample& s) {
  return std::isfinite(s.v[0]) && std::isfinite(s.v[1]) && std::isfinite(s.v[2]);
}

Stats stats(const std::vector<bundle::ImuSample>& in, double window_s) {
  std::vector<const bundle::ImuSample*> kept;
  kept.reserve(in.size());
  for (auto& s : in)
    if (finite3(s) && (kept.empty() || s.t_ns > kept.back()->t_ns)) kept.push_back(&s);
  Stats st;
  if (kept.empty()) return st;
  const int64_t last = kept.back()->t_ns;
  st.span_s = double(last - kept.front()->t_ns) * 1e-9;
  const int64_t start = last - int64_t(std::llround(window_s * 1e9));
  auto first = std::lower_bound(kept.begin(), kept.end(), start,
                                [](const bundle::ImuSample* s, int64_t t) { return s->t_ns < t; });
  st.n = int(kept.end() - first);
  double mean[3] = {0, 0, 0};
  for (auto it = first; it != kept.end(); ++it)
    for (int a = 0; a < 3; ++a) mean[a] += (*it)->v[a];
  for (double& m : mean) m /= st.n;
  double var[3] = {0, 0, 0};
  for (auto it = first; it != kept.end(); ++it)
    for (int a = 0; a < 3; ++a) var[a] += ((*it)->v[a] - mean[a]) * ((*it)->v[a] - mean[a]);
  st.mean_norm = std::sqrt(mean[0] * mean[0] + mean[1] * mean[1] + mean[2] * mean[2]);
  for (double v : var) st.std_max = std::max(st.std_max, std::sqrt(v / st.n));
  return st;
}

const char* kContinue = "Hold the phone still, or lay it on a table, for a few seconds.";
const char* kRestart = "Too much movement. Hold the phone still; checking again.";
const char* kMovingFinish =
    "Could not hold still long enough to check the sensors. Re-check, or start the scan anyway.";
const char* kFail = "Motion sensor readings look wrong. Restart the phone and re-check; a scan now may be less accurate.";
const char* kUnreliable = "The phone reports its motion sensors as unreliable. Re-check, or start the scan anyway.";
const char* kGyroBias =
    "The motion sensor shows a small drift. Lay the phone on a table and re-check, or start the scan anyway.";
const char* kAccelOff =
    "The tilt sensor reading looks off. Lay the phone on a table and re-check, or start the scan anyway.";
const char* kNoData = "No motion sensor data arrived. You can still scan, but tracking may fail.";
const char* kPass = "Sensors look good.";

std::string join(const std::vector<const char*>& v) {
  std::string o;
  for (auto* s : v) o += (o.empty() ? "" : ",") + std::string(s);
  return o;
}

}  // namespace

std::string sensor_check_json(const std::vector<bundle::ImuSample>& gyro, const std::vector<bundle::ImuSample>& accel,
                              int gyro_accuracy, int accel_accuracy, double elapsed_s, int restarts,
                              const SensorCheckParams& p) {
  const Stats g = stats(gyro, p.window_s), a = stats(accel, p.window_s);
  const double accel_err = std::abs(a.mean_norm - p.g);
  std::string action, message, result;
  std::vector<const char*> reasons;

  const bool moving = g.n > 0 && g.span_s >= p.min_span_moving_s &&
                      (g.std_max > p.moving_gyro_std || g.mean_norm > p.moving_gyro_bias ||
                       (a.n > 0 && a.std_max > p.moving_accel_std));
  if (moving) {
    result = "moving";
    if (restarts < p.max_restarts) {
      action = "restart";
      message = kRestart;
    } else {
      action = "finish";
      message = kMovingFinish;
    }
  } else if (g.span_s >= p.window_s && a.span_s >= p.window_s && g.n >= p.min_samples && a.n >= p.min_samples) {
    action = "finish";
    bool fail = false, warn = false;
    if (g.mean_norm > p.gyro_bias_warn) {
      reasons.push_back("GYRO_BIAS_HIGH");
      (g.mean_norm > p.gyro_bias_fail ? fail : warn) = true;
    }
    if (accel_err > p.accel_err_warn) {
      reasons.push_back("ACCEL_GRAVITY_OFF");
      (accel_err > p.accel_err_fail ? fail : warn) = true;
    }
    const bool unreliable = gyro_accuracy == 0 || accel_accuracy == 0;
    if (unreliable) {
      reasons.push_back("SENSOR_UNRELIABLE");
      warn = true;
    }
    result = fail ? "fail" : warn ? "warn" : "pass";
    if (fail) message = kFail;
    else if (unreliable) message = kUnreliable;
    else if (g.mean_norm > p.gyro_bias_warn) message = kGyroBias;
    else if (accel_err > p.accel_err_warn) message = kAccelOff;
    else message = kPass;
  } else if (elapsed_s >= p.timeout_s) {
    result = "insufficient";
    action = "finish";
    message = kNoData;
    if (g.n == 0) reasons.push_back("NO_GYRO");
    if (a.n == 0) reasons.push_back("NO_ACCEL");
    if (g.n > 0 && a.n > 0) reasons.push_back("FEW_SAMPLES");
  } else {
    result = "insufficient";
    action = "continue";
    message = kContinue;
  }

  json::Object o;
  o.set("action", action)
      .set("message", message)
      .set("sensor_check.result", result)
      .set("sensor_check.reasons", join(reasons))
      .set("sensor_check.gyro_bias_rad_s", g.mean_norm)
      .set("sensor_check.gyro_std_rad_s", g.std_max)
      .set("sensor_check.accel_norm_mps2", a.mean_norm)
      .set("sensor_check.accel_std_mps2", a.std_max)
      .set("sensor_check.window_s", p.window_s)
      .set("sensor_check.n_gyro", g.n)
      .set("sensor_check.n_accel", a.n);
  return o.dump();
}

}  // namespace capturekit::quality
