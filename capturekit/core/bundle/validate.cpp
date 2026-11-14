#include "capturekit/bundle/validate.hpp"

#include <algorithm>
#include <cmath>
#include <filesystem>

#include "capturekit/util/json.hpp"

namespace fs = std::filesystem;

namespace capturekit::bundle {

std::string ValidationReport::verdict() const {
  bool warn = false;
  for (auto& i : issues) {
    if (i.severity == "ERROR") return "FAIL";
    warn |= i.severity == "WARN";
  }
  return warn ? "WARN" : "PASS";
}

bool ValidationReport::has(const std::string& code) const {
  return std::any_of(issues.begin(), issues.end(), [&](auto& i) { return i.code == code; });
}

std::string ValidationReport::to_json() const {
  std::vector<std::string> v;
  for (auto& i : issues) {
    json::Object o;
    o.set("code", i.code).set("severity", i.severity).set("level", i.level).set("message", i.message)
        .set("where", i.where);
    v.push_back(o.dump());
  }
  json::Object o;
  o.set("verdict", verdict()).set("level", "semantic").raw("issues", json::array(v.begin(), v.end()));
  return o.dump();
}

namespace {
bool finite(std::initializer_list<double> v) {
  return std::all_of(v.begin(), v.end(), [](double x) { return std::isfinite(x); });
}
}  // namespace

ValidationReport validate_semantic(const std::string& dir, const StreamContents& s,
                                   const std::string& completion_status, const StreamCounts* counts) {
  ValidationReport r;
  auto add = [&](const char* code, const char* sev, std::string msg, std::string where = "") {
    r.issues.push_back({code, sev, "semantic", std::move(msg), std::move(where)});
  };

  size_t nan_poses = 0;
  int64_t nan_t = 0;
  size_t nonunit = 0;
  int64_t nonunit_t = 0;
  for (auto& p : s.poses) {
    const auto* q = p.q_world_from_camera_wxyz;
    const auto* t = p.t_world_from_camera;
    if (!finite({t[0], t[1], t[2], q[0], q[1], q[2], q[3]})) {
      if (!nan_poses++) nan_t = p.t_ns;
    } else if (std::fabs(std::sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]) - 1) > kQuatTol) {
      if (!nonunit++) nonunit_t = p.t_ns;
    }
  }
  if (nan_poses) add("NAN_VALUE", "ERROR", std::to_string(nan_poses) + " poses with NaN/Inf", "/pose t=" + std::to_string(nan_t));
  for (auto [topic, v] : {std::pair<const char*, const std::vector<ImuSample>*>{"/imu/gyro", &s.gyro},
                          {"/imu/accel", &s.accel}}) {
    size_t n = 0;
    for (auto& m : *v) n += !finite({m.v[0], m.v[1], m.v[2]});
    if (n) add("NAN_VALUE", "ERROR", std::to_string(n) + " samples with NaN/Inf", topic);
  }
  if (nonunit) add("NON_UNIT_QUATERNION", "ERROR", std::to_string(nonunit) + " non-unit quaternions", "/pose t=" + std::to_string(nonunit_t));
  for (auto& k : s.intrinsics)
    if (!finite({k.fx, k.fy, k.cx, k.cy}) || k.fx <= 0) {
      add("NAN_VALUE", "ERROR", "invalid intrinsics", "/intrinsics");
      break;
    }

  std::vector<const FrameRecord*> kfs;
  for (auto& f : s.frames)
    if (f.keyframe) kfs.push_back(&f);
  if (!kfs.empty() && (s.intrinsics.empty() || s.intrinsics.front().t_ns > kfs.front()->t_ns))
    add("MISSING_INTRINSICS", "ERROR", "no intrinsics at or before first keyframe");

  std::vector<int64_t> pt;
  for (auto& p : s.poses) pt.push_back(p.t_ns);
  size_t no_pose = 0, limited = 0, missing = 0;
  int64_t no_pose_t = 0;
  std::string missing_first;
  for (auto* f : kfs) {
    auto it = std::lower_bound(pt.begin(), pt.end(), f->t_ns);
    bool exact = it != pt.end() && *it == f->t_ns;
    bool ok = exact || (it != pt.begin() && it != pt.end() && f->t_ns - *(it - 1) <= kKeyframePoseTolNs &&
                        *it - f->t_ns <= kKeyframePoseTolNs);
    if (!ok && !no_pose++) no_pose_t = f->t_ns;
    if (f->file.empty() || !fs::is_regular_file(fs::path(dir) / f->file)) {
      if (!missing++) missing_first = f->file.empty() ? "frame_id=" + std::to_string(f->frame_id) : f->file;
    }
    if (f->tracking_state != TrackingState::Unspecified && f->tracking_state != TrackingState::Normal) ++limited;
  }
  if (no_pose) add("KEYFRAME_WITHOUT_POSE", "ERROR", std::to_string(no_pose) + " keyframes lack bracketing poses", "t=" + std::to_string(no_pose_t));
  if (missing) add("KEYFRAME_FILE_MISSING", "ERROR", std::to_string(missing) + " keyframe files missing", missing_first);
  if (limited) add("KEYFRAME_TRACKING_LIMITED", "WARN", std::to_string(limited) + " keyframes while tracking not NORMAL");

  size_t gaps = 0;
  int64_t max_gap = 0;
  for (size_t i = 1; i < pt.size(); ++i)
    if (pt[i] - pt[i - 1] > kPoseGapWarnNs) ++gaps, max_gap = std::max(max_gap, pt[i] - pt[i - 1]);
  if (gaps) add("POSE_GAP", "WARN", std::to_string(gaps) + " gaps > 500 ms (max " + std::to_string(int64_t(std::llround(double(max_gap) / 1e6))) + " ms)");
  if (kfs.size() < kMinKeyframes) add("FEW_KEYFRAMES", "WARN", "only " + std::to_string(kfs.size()) + " keyframes");
  if (s.points.empty() && s.planes.empty()) add("NO_AR_GEOMETRY", "INFO", "no /ar/points or /ar/planes");

  if (counts) {
    const std::pair<const char*, uint64_t> actual[] = {{topic::kPose, s.poses.size()}, {topic::kGyro, s.gyro.size()},
                                                       {topic::kAccel, s.accel.size()}, {topic::kIntrinsics, s.intrinsics.size()},
                                                       {topic::kFrameIndex, s.frames.size()}, {topic::kEvents, s.events.size()}};
    std::string diff;
    for (auto& [topic, n] : actual)
      if (counts->get(topic) != n) diff += std::string(topic) + ": " + std::to_string(counts->get(topic)) + " != " + std::to_string(n) + "; ";
    if (counts->keyframes != kfs.size()) diff += "keyframes: " + std::to_string(counts->keyframes) + " != " + std::to_string(kfs.size()) + "; ";
    if (!diff.empty()) add("COUNT_MISMATCH", "WARN", diff);
  }
  if (completion_status == "partial" || completion_status == "recovered")
    add("PARTIAL_BUNDLE", "WARN", "completion.status = " + completion_status);
  return r;
}

}  // namespace capturekit::bundle
