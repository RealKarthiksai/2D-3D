#include "capturekit/session/session.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <set>

#include "capturekit/bundle/stream_reader.hpp"
#include "capturekit/util/json.hpp"

namespace fs = std::filesystem;

namespace capturekit::session {

const char* state_name(State s) {
  switch (s) {
    case State::Idle: return "IDLE";
    case State::Calibrating: return "CALIBRATING";
    case State::Scanning: return "SCANNING";
    case State::Paused: return "PAUSED";
    case State::Finalizing: return "FINALIZING";
    case State::Complete: return "COMPLETE";
    case State::Failed: return "FAILED";
  }
  return "UNKNOWN";
}

bool transition_allowed(State from, State to) {
  switch (to) {
    case State::Calibrating: return from == State::Idle || from == State::Paused;  // start / resume
    case State::Scanning: return from == State::Calibrating;                      // worker only
    case State::Paused: return from == State::Calibrating || from == State::Scanning;
    case State::Finalizing:
      return from == State::Calibrating || from == State::Scanning || from == State::Paused;
    case State::Complete: return from == State::Finalizing;
    case State::Failed: return from != State::Complete && from != State::Failed;
    case State::Idle: return false;
  }
  return false;
}

// ---- Config ----
std::optional<SessionConfig> SessionConfig::from_json(std::string_view text, std::string* err) {
  SessionConfig c;
  if (text.empty()) text = "{}";
  auto obj = json::parse_flat(text, err);
  if (!obj) return std::nullopt;
  json::Object q, g;
  auto num = [](const json::FlatValue& v) -> std::optional<double> {
    if (auto* d = std::get_if<double>(&v)) return *d;
    if (auto* b = std::get_if<bool>(&v)) return *b ? 1.0 : 0.0;
    return std::nullopt;
  };
  for (auto& [k, v] : *obj) {
    auto n = num(v);
    auto* str = std::get_if<std::string>(&v);
    if (k.rfind("quality.", 0) == 0 && n) q.set(k.substr(8), *n);
    else if (k.rfind("governor.", 0) == 0 && n) g.set(k.substr(9), *n);
    else if (k == "threaded" && n) c.threaded = *n != 0;
    else if (k == "frame_slots" && n) c.frame_slots = std::max(1, int(*n));
    else if (k == "queue_capacity" && n) c.queue_capacity = size_t(std::max(16.0, *n));
    else if (k == "calib_normal_poses" && n) c.calib_normal_poses = std::max(1, int(*n));
    else if (k == "keyframe_ack_timeout_s" && n) c.keyframe_ack_timeout_s = *n;
    else if (k == "outbound_capacity" && n) c.outbound_capacity = size_t(std::max(16.0, *n));
    else if (k == "clock_offset_ns" && n) c.meta.clock_offset_ns = int64_t(*n);
    else if (k == "display_rotation_deg" && n) c.meta.display_rotation_deg = int(*n);
    else if (str && k == "bundle_id") c.meta.bundle_id = *str;
    else if (str && k == "session_id") c.meta.session_id = *str;
    else if (str && k == "platform") c.meta.platform = *str;
    else if (str && k == "model") c.meta.model = *str;
    else if (str && k == "os") c.meta.os = *str;
    else if (str && k == "app_version") c.meta.app_version = *str;
    else if (str && k == "clock_base") c.meta.clock_base = *str;
    else {
      if (err) *err = "unknown or mistyped session config key: " + k;
      return std::nullopt;
    }
  }
  auto qc = quality::QualityConfig::from_json(q.dump(), err);
  auto gc = governor::GovernorConfig::from_json(g.dump(), err);
  if (!qc || !gc) return std::nullopt;
  c.quality = *qc;
  c.governor = *gc;
  return c;
}

// ---- Construction ----
Session::Session(const std::string& dir, const SessionConfig& cfg)
    : dir_(dir), cfg_(cfg), analyzer_(cfg.quality), selector_(cfg.quality), governor_(cfg.governor) {
  slots_.resize(size_t(cfg.frame_slots));
  for (int i = cfg.frame_slots - 1; i >= 0; --i) free_slots_.push_back(i);
  decisions_.reserve(64);
  policy_ = governor::policy_for(governor::Level::Nominal, cfg.governor);
}

std::unique_ptr<Session> Session::create(const std::string& dir, const SessionConfig& cfg_in, std::string* err) {
  std::error_code ec;
  fs::create_directories(fs::path(dir) / "keyframes", ec);
  if (ec) {
    if (err) *err = "cannot create " + dir + ": " + ec.message();
    return nullptr;
  }
  if (fs::exists(fs::path(dir) / "streams.mcap") || fs::exists(fs::path(dir) / "manifest.json")) {
    if (err) *err = "bundle directory already holds a session: " + dir;
    return nullptr;
  }
  SessionConfig cfg = cfg_in;
  std::string base = fs::path(dir).filename().string();
  if (cfg.meta.session_id.empty()) cfg.meta.session_id = base;
  if (cfg.meta.bundle_id.empty()) cfg.meta.bundle_id = base;
  json::Object settings;
  settings.set("source", "capturekit-session")
      .raw("quality_config", cfg.quality.to_json())
      .set("quality_config_hash", cfg.quality.hash())
      .set("quality_config_version", cfg.quality.version)
      .raw("governor_config", cfg.governor.to_json());
  cfg.meta.capture_settings_json = settings.dump();

  std::unique_ptr<Session> s(new Session(dir, cfg));
  if (!s->writer_.open((fs::path(dir) / "streams.mcap").string())) {
    if (err) *err = "cannot open streams.mcap in " + dir;
    return nullptr;
  }
  if (!s->write_checkpoint()) {
    if (err) *err = "cannot write session.json in " + dir;
    return nullptr;
  }
  if (cfg.threaded) s->worker_ = std::thread([p = s.get()] { p->worker_loop(); });
  return s;
}

bool Session::write_checkpoint() {
  // Flat JSON with the metadata recover() needs (temp + rename).
  const auto& m = cfg_.meta;
  json::Object o;
  o.set("bundle_id", m.bundle_id).set("session_id", m.session_id).set("platform", m.platform)
      .set("model", m.model).set("os", m.os).set("app_version", m.app_version)
      .set("clock_base", m.clock_base).set("clock_offset_ns", m.clock_offset_ns)
      .set("display_rotation_deg", m.display_rotation_deg)
      .set("capture_settings_json", m.capture_settings_json);
  auto tmp = fs::path(dir_) / "session.json.tmp";
  {
    std::ofstream f(tmp, std::ios::binary | std::ios::trunc);
    if (!f) return false;
    f << o.dump() << "\n";
    if (!f.good()) return false;
  }
  std::error_code ec;
  fs::rename(tmp, fs::path(dir_) / "session.json", ec);
  return !ec;
}

Session::~Session() {
  {
    std::lock_guard<std::mutex> lk(qmu_);
    stop_worker_ = true;
  }
  qcv_.notify_all();
  if (worker_.joinable()) worker_.join();
  writer_.flush();  // no finalize: the bundle stays recoverable (manifest is never written here)
}

// ---- State changes (API thread) ----
void Session::set_state(State to, int64_t t, const std::string& reason) {
  State from = state_.load();
  state_.store(to);
  json::Object o;
  o.set("type", "state").set("t_ns", t).set("from", state_name(from)).set("to", state_name(to))
      .set("reason", reason);
  emit(o.dump(), true);
}

Status Session::start(int64_t t) {
  State cur = state_.load();
  if (!transition_allowed(cur, State::Calibrating) || cur != State::Idle) return Status::ErrState;
  set_state(State::Calibrating, t, "start");
  return enqueue(Control{Control::Start, t}, -1, true);
}

Status Session::pause(int64_t t) {
  if (!transition_allowed(state_.load(), State::Paused)) return Status::ErrState;
  set_state(State::Paused, t, "user");
  return enqueue(Control{Control::Pause, t}, -1, true);
}

Status Session::resume(int64_t t) {
  if (state_.load() != State::Paused) return Status::ErrState;
  if (policy().must_pause) return Status::ErrPolicy;
  set_state(State::Calibrating, t, "resume");
  return enqueue(Control{Control::Resume, t}, -1, true);
}

Status Session::stop(int64_t t) {
  if (!transition_allowed(state_.load(), State::Finalizing)) return Status::ErrState;
  set_state(State::Finalizing, t, "stop");
  return enqueue(Control{Control::Stop, t}, -1, true);
}

// ---- Ingest (any thread, never blocks on processing) ----
Status Session::enqueue(Item&& it, int kind, bool force) {
  {
    std::lock_guard<std::mutex> lk(qmu_);
    if (!force && queue_.size() >= cfg_.queue_capacity) {
      if (kind >= 0) ++dropped_[kind];
      return Status::ErrFull;
    }
    queue_.push_back(std::move(it));
    if (kind >= 0) ++ingested_[kind];
  }
  if (cfg_.threaded) qcv_.notify_one();
  return Status::Ok;
}

namespace {
bool capturing(State s) { return s == State::Calibrating || s == State::Scanning || s == State::Paused; }
}  // namespace

Status Session::ingest_pose(const bundle::PoseSample& p) {
  if (!capturing(state_.load())) return Status::ErrState;
  return enqueue(p, kPose);
}
Status Session::ingest_gyro(const bundle::ImuSample& s) {
  if (!capturing(state_.load())) return Status::ErrState;
  return enqueue(Gyro{s}, kGyro);
}
Status Session::ingest_accel(const bundle::ImuSample& s) {
  if (!capturing(state_.load())) return Status::ErrState;
  return enqueue(Accel{s}, kAccel);
}
Status Session::ingest_intrinsics(const bundle::IntrinsicsSample& k) {
  if (!capturing(state_.load())) return Status::ErrState;
  if (k.width == 0 || k.height == 0 || !(k.fx > 0) || !(k.fy > 0)) return Status::ErrArg;
  return enqueue(k, kIntrinsics);
}
Status Session::ingest_points(const bundle::PointCloudRecord& p) {
  if (!capturing(state_.load())) return Status::ErrState;
  return enqueue(p, kPoints);
}
Status Session::ingest_plane(const bundle::PlaneRecord& p) {
  if (!capturing(state_.load())) return Status::ErrState;
  return enqueue(p, kPlane);
}
Status Session::ingest_platform(int64_t t, const governor::Signals& s) {
  if (!capturing(state_.load())) return Status::ErrState;
  return enqueue(Platform{t, s}, kPlatform);
}

Status Session::ingest_frame(const FrameInput& f) {
  State st = state_.load();
  if (st != State::Calibrating && st != State::Scanning) return Status::ErrState;
  if (!f.luma || f.width <= 0 || f.height <= 0 || f.stride < f.width) return Status::ErrArg;
  int slot = -1;
  {
    std::lock_guard<std::mutex> lk(qmu_);
    if (free_slots_.empty() || queue_.size() >= cfg_.queue_capacity) {
      ++dropped_[kFrame];
      return Status::ErrFull;
    }
    slot = free_slots_.back();
    free_slots_.pop_back();
  }
  auto& buf = slots_[size_t(slot)];
  const size_t need = size_t(f.width) * size_t(f.height);
  if (buf.size() != need) buf.resize(need);  // allocation only when the resolution changes
  for (int y = 0; y < f.height; ++y)
    std::copy_n(f.luma + size_t(y) * size_t(f.stride), size_t(f.width), buf.data() + size_t(y) * size_t(f.width));
  FrameItem item{f, slot};
  item.meta.luma = nullptr;
  item.meta.stride = f.width;
  return enqueue(std::move(item), kFrame, true);  // slot already reserved: never dropped here
}

Status Session::keyframe_written(uint32_t frame_id, bool ok) {
  State st = state_.load();
  if (st == State::Idle || st == State::Complete || st == State::Failed) return Status::ErrState;
  return enqueue(Ack{frame_id, ok}, -1, true);
}

// ---- Outbound ----
void Session::emit(const std::string& line, bool critical) {
  std::lock_guard<std::mutex> lk(omu_);
  if (!critical && outbound_.size() >= cfg_.outbound_capacity) {
    ++outbound_dropped_;
    return;
  }
  outbound_.push_back(line);
}

std::string Session::poll_events(size_t max_bytes) {
  std::lock_guard<std::mutex> lk(omu_);
  std::string out;
  while (!outbound_.empty() && out.size() + outbound_.front().size() + 1 <= max_bytes) {
    out += outbound_.front();
    out += '\n';
    outbound_.pop_front();
  }
  return out;
}

governor::Policy Session::policy() const {
  std::lock_guard<std::mutex> lk(pmu_);
  return policy_;
}

Counters Session::counters() const {
  Counters c;
  for (int i = 0; i < 8; ++i) c.ingested[i] = ingested_[i].load(), c.dropped[i] = dropped_[i].load();
  return c;
}

// ---- Consumer ----
void Session::worker_loop() {
  std::deque<Item> batch;
  for (;;) {
    {
      std::unique_lock<std::mutex> lk(qmu_);
      qcv_.wait(lk, [&] { return stop_worker_ || !queue_.empty(); });
      if (queue_.empty() && stop_worker_) return;
      batch.swap(queue_);
    }
    for (auto& it : batch) process(it);
    batch.clear();
  }
}

Status Session::pump() {
  if (cfg_.threaded) return Status::ErrState;
  std::deque<Item> batch;
  {
    std::lock_guard<std::mutex> lk(qmu_);
    batch.swap(queue_);
  }
  for (auto& it : batch) process(it);
  return Status::Ok;
}

void Session::write_event(int64_t t, const std::string& source, const std::string& code, const std::string& msg,
                          std::vector<std::pair<std::string, std::string>> attrs) {
  bundle::EventRecord e;
  e.t_ns = t > last_event_t_ ? t : last_event_t_ + 1;  // events are strictly increasing per channel
  last_event_t_ = e.t_ns;
  e.source = source;
  e.code = code;
  e.message = msg;
  e.attrs = std::move(attrs);
  writer_.write(e);
}

void Session::process(Item& it) {
  std::visit(
      [&](auto& v) {
        using T = std::decay_t<decltype(v)>;
        if constexpr (std::is_same_v<T, bundle::PoseSample>) {
          last_t_ = std::max(last_t_, v.t_ns);
          writer_.write(v);
          pose_ = geometry::PoseWC::from_tq(v.t_world_from_camera, v.q_world_from_camera_wxyz);
          tracking_ = int(v.tracking_state);
          normal_run_ = tracking_ == 1 ? normal_run_ + 1 : 0;
          if (state_.load() == State::Calibrating && normal_run_ >= cfg_.calib_normal_poses)
            set_state(State::Scanning, v.t_ns, "tracking_normal");
        } else if constexpr (std::is_same_v<T, Gyro>) {
          gyro_.push(v.s.t_ns, v.s.v[0], v.s.v[1], v.s.v[2]);
          writer_.write_gyro(v.s);
        } else if constexpr (std::is_same_v<T, Accel>) {
          writer_.write_accel(v.s);
        } else if constexpr (std::is_same_v<T, bundle::IntrinsicsSample>) {
          if (!first_intrinsics_) first_intrinsics_ = v;
          fx_ = v.fx;
          writer_.write(v);
        } else if constexpr (std::is_same_v<T, FrameItem>) {
          on_frame(v);
        } else if constexpr (std::is_same_v<T, bundle::PointCloudRecord>) {
          writer_.write(v);
          if (auto d = quality::median_point_depth(pose_, v.xyz.data(), v.xyz.size() / 3))
            selector_.set_scene_depth(std::clamp(*d, 0.5, 6.0));
        } else if constexpr (std::is_same_v<T, bundle::PlaneRecord>) {
          writer_.write(v);
        } else if constexpr (std::is_same_v<T, Platform>) {
          auto& s = v.s;
          if (s.thermal_level != signals_.thermal_level) {
            static const char* names[4] = {"nominal", "fair", "serious", "critical"};
            write_event(v.t_ns, "platform", "thermal", "", {{"state", names[std::clamp(s.thermal_level, 0, 3)]}});
          }
          if (s.battery_pct != signals_.battery_pct || s.charging != signals_.charging ||
              s.low_power_mode != signals_.low_power_mode)
            write_event(v.t_ns, "platform", "power", "",
                        {{"battery_pct", json::num(std::round(s.battery_pct * 10) / 10)},
                         {"charging", s.charging ? "1" : "0"},
                         {"low_power", s.low_power_mode ? "1" : "0"}});
          if (std::fabs(s.frame_time_ms_ema - signals_.frame_time_ms_ema) >= 1.0)
            write_event(v.t_ns, "platform", "perf", "",
                        {{"frame_time_ms", json::num(std::round(s.frame_time_ms_ema * 10) / 10)},
                         {"dropped_rate", json::num(signals_.dropped_frame_rate)}});
          double dr = signals_.dropped_frame_rate;
          signals_ = s;
          signals_.dropped_frame_rate = dr;
          governor_step(v.t_ns);
        } else if constexpr (std::is_same_v<T, Control>) {
          switch (v.type) {
            case Control::Start:
              write_event(v.t_ns, "session", "SESSION_STARTED", "");
              write_event(v.t_ns, "quality", "QUALITY_CONFIG", "keyframe selection config",
                          {{"version", cfg_.quality.version}, {"hash", cfg_.quality.hash()}});
              break;
            case Control::Pause: write_event(v.t_ns, "session", "SESSION_PAUSED", "user"); break;
            case Control::Resume: write_event(v.t_ns, "session", "SESSION_RESUMED", ""); break;
            case Control::GovernorPause: write_event(v.t_ns, "session", "SESSION_PAUSED", "governor"); break;
            case Control::Stop:
              decisions_.clear();
              selector_.flush(decisions_);
              on_decisions(decisions_);
              flush_ready(false);
              write_event(v.t_ns, "session", "SESSION_STOPPED", "");
              break;
          }
          last_t_ = std::max(last_t_, v.t_ns);
        } else if constexpr (std::is_same_v<T, Ack>) {
          auto it2 = t_of_frame_.find(v.frame_id);
          if (it2 == t_of_frame_.end()) return;
          auto& p = reorder_[it2->second];
          if (!p.awaiting_ack) return;
          p.awaiting_ack = false;
          if (!v.ok) {
            p.rec.keyframe = false;
            p.rec.file.clear();
            p.rec.reasons.push_back("ENCODE_FAILED");
          }
          flush_ready(false);
        }
      },
      it);
}

void Session::on_frame(FrameItem& f) {
  const int64_t t = f.meta.t_ns;
  last_t_ = std::max(last_t_, t);
  const uint64_t dropped_now = dropped_[kFrame].load();
  recent_drop_[recent_idx_++ % recent_drop_.size()] = dropped_now != reported_drops_[kFrame];
  int n_drop = 0;
  for (bool b : recent_drop_) n_drop += b;
  signals_.dropped_frame_rate = double(n_drop) / double(recent_drop_.size());

  auto release = [&] {
    std::lock_guard<std::mutex> lk(qmu_);
    free_slots_.push_back(f.slot);
  };
  if (state_.load() != State::Scanning || reorder_.count(t) || t <= last_frame_t_) {
    release();  // calibrating / paused frames are not candidates; duplicates are ignored
    report_drops(t);
    governor_step(t);
    return;
  }
  last_frame_t_ = t;
  const auto& buf = slots_[size_t(f.slot)];
  quality::FrameMetrics m;
  analyzer_.measure({buf.data(), f.meta.width, f.meta.height, f.meta.width}, m);
  release();
  m.blur_risk_px = fx_ > 0 ? quality::blur_risk_px(gyro_, t, f.meta.exposure_ns, fx_,
                                                    int64_t(cfg_.quality.default_exposure_ms * 1e6))
                           : std::nan("");
  analyzer_.finish(m, tracking_);

  Pending p;
  auto& r = p.rec;
  r.t_ns = t;
  r.frame_id = f.meta.frame_id;
  r.exposure_ns = f.meta.exposure_ns;
  r.sharpness = m.sharpness;
  r.sharpness_norm = m.sharpness_norm;
  r.blur_risk_px = std::isnan(m.blur_risk_px) ? 0 : m.blur_risk_px;
  r.clip_low = m.clip_low;
  r.clip_high = m.clip_high;
  r.corners = m.corners;
  r.corner_spread = m.corner_spread;
  r.quality = m.quality;
  r.tracking_state = bundle::TrackingState(tracking_);
  reorder_[t] = p;
  t_of_frame_[f.meta.frame_id] = t;

  {
    json::Object o;
    std::vector<std::string> rs;
    for (auto& c : quality::reason_codes(m.reasons)) rs.push_back(json::str(c));
    o.set("type", "frame_quality").set("t_ns", t).set("frame_id", int64_t(f.meta.frame_id))
        .set("quality", std::round(m.quality * 1000) / 1000).set("hard_reject", m.hard_reject)
        .raw("reasons", json::array(rs.begin(), rs.end()));
    emit(o.dump(), false);
  }

  selector_.set_rate_scale(policy().keyframe_rate_scale);
  decisions_.clear();
  selector_.push(f.meta.frame_id, t, m, pose_, decisions_);
  on_decisions(decisions_);
  expire_acks(t);
  governor_step(t);
  report_drops(t);
  flush_ready(false);
}

void Session::on_decisions(const std::vector<quality::KeyframeSelector::Decision>& ds) {
  for (auto& d : ds) {
    auto it = t_of_frame_.find(d.frame_id);
    if (it == t_of_frame_.end()) continue;
    auto& p = reorder_[it->second];
    p.decided = true;
    p.rec.keyframe = d.keyframe;
    p.rec.reasons = quality::reason_codes(d.reasons);
    if (d.keyframe) {
      char name[32];
      std::snprintf(name, sizeof name, "keyframes/%06u.jpg", next_keyframe_++);
      p.rec.file = name;
      p.awaiting_ack = true;
      json::Object o;
      o.set("type", "keyframe_request").set("t_ns", d.t_ns).set("frame_id", int64_t(d.frame_id))
          .set("file", p.rec.file);
      emit(o.dump(), true);
    }
  }
}

void Session::expire_acks(int64_t now) {
  const int64_t timeout = int64_t(cfg_.keyframe_ack_timeout_s * 1e9);
  for (auto& [t, p] : reorder_) {
    if (p.awaiting_ack && now - t > timeout) {
      p.awaiting_ack = false;
      json::Object o;
      o.set("type", "keyframe_expired").set("frame_id", int64_t(p.rec.frame_id)).set("file", p.rec.file);
      emit(o.dump(), true);
      p.rec.keyframe = false;
      p.rec.file.clear();
      p.rec.reasons.push_back("ENCODE_MISSING");
    }
  }
}

void Session::flush_ready(bool force_all) {
  while (!reorder_.empty()) {
    auto it = reorder_.begin();
    auto& p = it->second;
    if (!(p.decided && !p.awaiting_ack)) {
      if (!force_all) break;
      if (p.awaiting_ack) p.rec.reasons.push_back("ENCODE_MISSING");
      if (!p.decided) p.rec.reasons.push_back("UNDECIDED");
      p.rec.keyframe = false;
      p.rec.file.clear();
    }
    writer_.write(p.rec);
    t_of_frame_.erase(p.rec.frame_id);
    reorder_.erase(it);
  }
}

void Session::governor_step(int64_t t) {
  if (t < signals_.t_ns) return;  // governor time never goes backwards
  signals_.t_ns = t;
  transitions_.clear();
  auto pol = governor_.update(signals_, transitions_);
  {
    std::lock_guard<std::mutex> lk(pmu_);
    policy_ = pol;
  }
  for (auto& tr : transitions_) {
    write_event(tr.t_ns, "governor", "GOVERNOR_LEVEL", tr.reason,
                {{"from", governor::level_name(tr.from)}, {"to", governor::level_name(tr.to)}});
    json::Object o;
    o.set("type", "governor").set("t_ns", tr.t_ns).set("from", governor::level_name(tr.from))
        .set("to", governor::level_name(tr.to)).set("reason", tr.reason);
    emit(o.dump(), true);
  }
  State st = state_.load();
  if (pol.must_pause && (st == State::Calibrating || st == State::Scanning)) {
    set_state(State::Paused, t, "governor");
    write_event(t, "session", "SESSION_PAUSED", "governor");
  }
}

void Session::report_drops(int64_t t) {
  static const char* names[8] = {"pose", "gyro", "accel", "intrinsics", "frame", "points", "plane", "platform"};
  std::vector<std::pair<std::string, std::string>> attrs;
  for (int i = 0; i < 8; ++i) {
    uint64_t d = dropped_[i].load();
    if (d != reported_drops_[i]) {
      reported_drops_[i] = d;
      attrs.emplace_back(names[i], std::to_string(d));
    }
  }
  if (attrs.empty()) return;
  write_event(t, "session", "INGEST_DROP", "bounded queue full (cumulative counts)", attrs);
  json::Object o;
  o.set("type", "ingest_drop").set("t_ns", t);
  for (auto& [k, v] : attrs) o.set(k, int64_t(std::stoll(v)));
  emit(o.dump(), false);
}

Status Session::finalize() {
  State st = state_.load();
  if (st == State::Calibrating || st == State::Scanning || st == State::Paused) stop(last_t_);
  if (state_.load() != State::Finalizing) return Status::ErrState;
  {
    std::lock_guard<std::mutex> lk(qmu_);
    stop_worker_ = true;
  }
  qcv_.notify_all();
  if (worker_.joinable()) worker_.join();
  {  // drain anything left (non-threaded mode, or items queued after the worker exited)
    std::deque<Item> batch;
    {
      std::lock_guard<std::mutex> lk(qmu_);
      batch.swap(queue_);
    }
    for (auto& it : batch) process(it);
  }
  flush_ready(true);
  report_drops(last_t_);
  write_event(last_t_, "session", "SESSION_FINALIZED", "");
  auto counts = writer_.close();

  // Keyframe files nobody references (expired / failed requests written late) are removed.
  std::set<std::string> referenced;
  {
    auto c = bundle::read_streams((fs::path(dir_) / "streams.mcap").string());
    for (auto& f : c.frames)
      if (f.keyframe && !f.file.empty()) referenced.insert(f.file);
  }
  std::error_code ec;
  for (auto& e : fs::directory_iterator(fs::path(dir_) / "keyframes", ec)) {
    auto rel = "keyframes/" + e.path().filename().string();
    if (!referenced.count(rel)) fs::remove(e.path(), ec);
  }
  fs::remove(fs::path(dir_) / "session.json", ec);
  if (!first_intrinsics_) {
    set_state(State::Failed, last_t_, "no intrinsics");
    return Status::ErrIo;
  }
  bundle::BundleMeta meta = cfg_.meta;
  meta.completion_status = "complete";
  if (!bundle::write_camera_json(dir_, *first_intrinsics_, meta)) {
    set_state(State::Failed, last_t_, "camera.json");
    return Status::ErrIo;
  }
  auto files = bundle::list_files(dir_);
  if (!bundle::write_manifest(dir_, meta, counts, files)) {
    set_state(State::Failed, last_t_, "manifest");
    return Status::ErrIo;
  }
  set_state(State::Complete, last_t_, "finalized");
  return Status::Ok;
}

}  // namespace capturekit::session
