#pragma once
// Plain records carried by the Scan Bundle channels (spec/scanbundle/SPEC.md).
// Poses are T_world_from_camera in our conventions (docs/program-spec/conventions.md).
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace capturekit::bundle {

enum class TrackingState : int { Unspecified = 0, Normal = 1, Limited = 2, NotAvailable = 3 };
enum class TrackingReason : int {
  None = 0, Initializing = 1, ExcessiveMotion = 2, InsufficientFeatures = 3,
  InsufficientLight = 4, Relocalizing = 5, BadState = 6, CameraUnavailable = 7, Other = 8
};

/// One /pose sample: T_world_from_camera as translation + unit quaternion (w,x,y,z).
struct PoseSample {
  int64_t t_ns = 0;
  double t_world_from_camera[3] = {0, 0, 0};
  double q_world_from_camera_wxyz[4] = {1, 0, 0, 0};
  TrackingState tracking_state = TrackingState::Normal;
  TrackingReason tracking_reason = TrackingReason::None;
};

struct ImuSample {
  int64_t t_ns = 0;
  double v[3] = {0, 0, 0};
};

enum class CameraModelId : int { Pinhole = 0, OpenCV = 1 };

struct IntrinsicsSample {
  int64_t t_ns = 0;
  uint32_t width = 0, height = 0;
  double fx = 0, fy = 0, cx = 0, cy = 0;
  CameraModelId model = CameraModelId::Pinhole;
  std::vector<double> distortion;
};

struct FrameRecord {
  int64_t t_ns = 0;
  uint32_t frame_id = 0;
  std::string file;  // bundle-relative, empty when not kept
  bool keyframe = false;
  int64_t exposure_ns = 0;
  double sharpness = 0, sharpness_norm = 0, blur_risk_px = 0, clip_low = 0, clip_high = 0;
  uint32_t corners = 0;
  double corner_spread = 0, quality = 0;
  std::vector<std::string> reasons;
  TrackingState tracking_state = TrackingState::Unspecified;
};

struct EventRecord {
  int64_t t_ns = 0;
  std::string source, code, message;
  std::vector<std::pair<std::string, std::string>> attrs;
};

struct PointCloudRecord {
  int64_t t_ns = 0;
  std::vector<float> xyz;
};

struct PlaneRecord {
  int64_t t_ns = 0;
  std::string id, kind;
  double center[3] = {0, 0, 0}, normal[3] = {0, 1, 0}, axis_u[3] = {1, 0, 0};
  double extent_u = 0, extent_v = 0;
};

namespace topic {
inline constexpr const char* kPose = "/pose";
inline constexpr const char* kGyro = "/imu/gyro";
inline constexpr const char* kAccel = "/imu/accel";
inline constexpr const char* kIntrinsics = "/intrinsics";
inline constexpr const char* kFrameIndex = "/frame_index";
inline constexpr const char* kEvents = "/events";
inline constexpr const char* kPoints = "/ar/points";
inline constexpr const char* kPlanes = "/ar/planes";
}  // namespace topic

}  // namespace capturekit::bundle
