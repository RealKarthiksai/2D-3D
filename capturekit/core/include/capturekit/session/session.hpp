#pragma once
// Capture session (Phase 6): the engine behind the C API (capi/capturekit.h).
// State machine: Idle -> Calibrating -> Scanning <-> Paused -> Finalizing -> Complete | Failed.
// Ingest calls only copy into bounded queues; one consumer (worker thread or pump()) owns the
// stream writer, quality analyzer, keyframe selector and governor.
#include <array>
#include <atomic>
#include <condition_variable>
#include <deque>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <variant>
#include <vector>

#include "capturekit/bundle/finalize.hpp"
#include "capturekit/bundle/stream_writer.hpp"
#include "capturekit/governor/governor.hpp"
#include "capturekit/quality/quality.hpp"

namespace capturekit::session {

enum class State : int { Idle = 0, Calibrating, Scanning, Paused, Finalizing, Complete, Failed };
enum class Status : int { Ok = 0, ErrState = 1, ErrArg = 2, ErrIo = 3, ErrPolicy = 4, ErrFull = 5 };
const char* state_name(State s);

/// Legal user-driven transitions (worker-driven ones: Calibrating->Scanning, governor pause).
bool transition_allowed(State from, State to);

struct SessionConfig {
  bool threaded = true;
  int frame_slots = 4;
  size_t queue_capacity = 8192;
  int calib_normal_poses = 15;
  double keyframe_ack_timeout_s = 2.0;
  size_t outbound_capacity = 1024;
  bundle::BundleMeta meta;
  quality::QualityConfig quality;
  governor::GovernorConfig governor;
  static std::optional<SessionConfig> from_json(std::string_view flat_json, std::string* err);
};

struct FrameInput {
  int64_t t_ns = 0;
  uint32_t frame_id = 0;
  int64_t exposure_ns = 0;
  const uint8_t* luma = nullptr;
  int width = 0, height = 0, stride = 0;
};

struct Counters {
  uint64_t ingested[8] = {}, dropped[8] = {};  // index by Kind
};

class Session {
 public:
  enum Kind : int { kPose = 0, kGyro, kAccel, kIntrinsics, kFrame, kPoints, kPlane, kPlatform };

  static std::unique_ptr<Session> create(const std::string& dir, const SessionConfig& cfg, std::string* err);
  ~Session();  // stops the worker; without finalize() the bundle stays recoverable

  Status start(int64_t t_ns);
  Status pause(int64_t t_ns);
  Status resume(int64_t t_ns);
  Status stop(int64_t t_ns);
  Status finalize();
  State state() const { return state_.load(); }
  Status pump();

  Status ingest_pose(const bundle::PoseSample& p);
  Status ingest_gyro(const bundle::ImuSample& s);
  Status ingest_accel(const bundle::ImuSample& s);
  Status ingest_intrinsics(const bundle::IntrinsicsSample& k);
  Status ingest_frame(const FrameInput& f);
  Status ingest_points(const bundle::PointCloudRecord& p);
  Status ingest_plane(const bundle::PlaneRecord& p);
  Status ingest_platform(int64_t t_ns, const governor::Signals& s);
  Status keyframe_written(uint32_t frame_id, bool ok);

  /// Pops whole JSON lines up to max_bytes (at least one if any is pending and fits).
  std::string poll_events(size_t max_bytes);
  governor::Policy policy() const;
  Counters counters() const;
  const std::string& dir() const { return dir_; }

 private:
  Session(const std::string& dir, const SessionConfig& cfg);
  struct Control {
    enum Type { Start, Pause, Resume, Stop, GovernorPause } type;
    int64_t t_ns;
  };
  struct FrameItem {
    FrameInput meta;
    int slot;
  };
  struct Ack {
    uint32_t frame_id;
    bool ok;
  };
  struct Platform {
    int64_t t_ns;
    governor::Signals s;
  };
  struct Gyro {
    bundle::ImuSample s;
  };
  struct Accel {
    bundle::ImuSample s;
  };
  using Item = std::variant<bundle::PoseSample, Gyro, Accel, bundle::IntrinsicsSample, FrameItem,
                            bundle::PointCloudRecord, bundle::PlaneRecord, Platform, Control, Ack>;
  struct Pending {
    bundle::FrameRecord rec;
    bool decided = false, awaiting_ack = false;
  };

  Status enqueue(Item&& it, int kind, bool force = false);
  void worker_loop();
  void process(Item& it);
  void on_frame(FrameItem& f);
  void on_decisions(const std::vector<quality::KeyframeSelector::Decision>& ds);
  void flush_ready(bool force_all);
  void expire_acks(int64_t now_t);
  void governor_step(int64_t t);
  void write_event(int64_t t, const std::string& source, const std::string& code, const std::string& msg,
                   std::vector<std::pair<std::string, std::string>> attrs = {});
  void emit(const std::string& json_line, bool critical);
  void set_state(State to, int64_t t, const std::string& reason);
  void report_drops(int64_t t);
  bool write_checkpoint();

  std::string dir_;
  SessionConfig cfg_;
  std::atomic<State> state_{State::Idle};

  // Ingest side
  mutable std::mutex qmu_;
  std::condition_variable qcv_;
  std::deque<Item> queue_;
  bool stop_worker_ = false;
  std::vector<std::vector<uint8_t>> slots_;
  std::vector<int> free_slots_;
  std::atomic<uint64_t> ingested_[8] = {}, dropped_[8] = {};
  uint64_t reported_drops_[8] = {};
  std::thread worker_;

  // Outbound events
  mutable std::mutex omu_;
  std::deque<std::string> outbound_;
  uint64_t outbound_dropped_ = 0;

  // Consumer side (worker only)
  bundle::StreamWriter writer_;
  quality::FrameAnalyzer analyzer_;
  quality::KeyframeSelector selector_;
  quality::ImuRing gyro_;
  governor::Governor governor_;
  governor::Signals signals_;
  mutable std::mutex pmu_;
  governor::Policy policy_;
  std::optional<bundle::IntrinsicsSample> first_intrinsics_;
  double fx_ = 0;
  geometry::PoseWC pose_;
  int tracking_ = 0, normal_run_ = 0;
  int64_t last_t_ = 0, last_event_t_ = INT64_MIN, last_frame_t_ = 0;
  std::map<int64_t, Pending> reorder_;
  std::map<uint32_t, int64_t> t_of_frame_;
  uint32_t next_keyframe_ = 0;
  std::array<bool, 30> recent_drop_{};
  size_t recent_idx_ = 0;
  std::vector<quality::KeyframeSelector::Decision> decisions_;
  std::vector<governor::Transition> transitions_;
};

/// Recovery report from recover().
struct RecoverResult {
  bool ok = false;
  bool already_complete = false;
  uint64_t poses = 0, frames = 0, keyframes = 0, dropped_frame_records = 0, removed_orphans = 0;
  std::string error;
  std::string to_json() const;
};
/// Turns an interrupted bundle into a valid partial bundle (completion "recovered").
RecoverResult recover(const std::string& dir);

}  // namespace capturekit::session
