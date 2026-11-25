#pragma once
// Pre-scan sensor check (ADR 0035): the user holds the phone still for ~2 s before a scan; this judges gyro drift,
// gyro/accel steadiness, gravity magnitude and the OS accuracy flag. Apps cannot recalibrate IMU hardware, so a
// check (and a fresh AR session) is what we can do. Pure function: all time comes from the samples' t_ns and the
// caller's elapsed_s, never a clock. The shells only collect samples, show `message` and follow `action`.
//
// Output: one flat JSON object (keys sorted) with "action" (continue | restart | finish), "message" (user sentence)
// and the manifest summary keys "sensor_check.result|reasons|gyro_bias_rad_s|gyro_std_rad_s|accel_norm_mps2|
// accel_std_mps2|window_s|n_gyro|n_accel". result: pass | warn | fail | moving | insufficient. Never NaN.
#include <string>
#include <vector>

#include "capturekit/bundle/types.hpp"

namespace capturekit::quality {

/// Starting guesses (AGENTS.md: tune on device data from H39).
struct SensorCheckParams {
  double window_s = 2.0;           // trailing window judged, and how long the phone must be held still
  double min_span_moving_s = 0.5;  // gyro data this long before movement can be called
  double timeout_s = 8.0;          // give up waiting for enough samples after this
  int min_samples = 50;            // per sensor in the window
  int max_restarts = 3;            // "too much movement" restarts before the check finishes as `moving`
  double moving_gyro_std = 0.15;   // rad/s, max per-axis std
  double moving_gyro_bias = 0.30;  // rad/s, |mean omega|
  double moving_accel_std = 0.8;   // m/s^2, max per-axis std
  double gyro_bias_warn = 0.035, gyro_bias_fail = 0.10;  // rad/s, |mean omega| while still
  double accel_err_warn = 0.4, accel_err_fail = 1.0;     // m/s^2, ||mean a| - g|
  double g = 9.80665;
};

/// gyro in rad/s, accel in m/s^2. accuracy: OS flag per sensor (0 unreliable, -1 unknown). elapsed_s: time since
/// this check attempt started (caller's clock); restarts: `restart` actions already taken in this attempt.
std::string sensor_check_json(const std::vector<bundle::ImuSample>& gyro, const std::vector<bundle::ImuSample>& accel,
                              int gyro_accuracy, int accel_accuracy, double elapsed_s, int restarts,
                              const SensorCheckParams& p = {});

}  // namespace capturekit::quality
