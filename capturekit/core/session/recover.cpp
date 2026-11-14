// Crash recovery: an interrupted session directory (no manifest, unfinalized streams.mcap, maybe
// half-written JPEGs) becomes a valid partial bundle with completion status "recovered".
#include <filesystem>
#include <fstream>
#include <set>

#include "capturekit/bundle/stream_reader.hpp"
#include "capturekit/session/session.hpp"
#include "capturekit/util/json.hpp"

namespace fs = std::filesystem;

namespace capturekit::session {

std::string RecoverResult::to_json() const {
  json::Object o;
  o.set("ok", ok).set("already_complete", already_complete).set("poses", poses).set("frames", frames)
      .set("keyframes", keyframes).set("dropped_frame_records", dropped_frame_records)
      .set("removed_orphans", removed_orphans).set("error", error);
  return o.dump();
}

namespace {

bool complete_jpeg(const fs::path& p) {
  std::ifstream f(p, std::ios::binary | std::ios::ate);
  if (!f) return false;
  auto n = f.tellg();
  if (n < 4) return false;
  unsigned char a[2], b[2];
  f.seekg(0);
  f.read(reinterpret_cast<char*>(a), 2);
  f.seekg(n - std::streamoff(2));
  f.read(reinterpret_cast<char*>(b), 2);
  return f.good() && a[0] == 0xFF && a[1] == 0xD8 && b[0] == 0xFF && b[1] == 0xD9;
}

void load_meta(const fs::path& dir, bundle::BundleMeta& m) {
  std::ifstream f(dir / "session.json");
  if (!f) return;
  std::string text((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
  auto obj = json::parse_flat(text);
  if (!obj) return;
  auto str = [&](const char* k, std::string& out) {
    auto it = obj->find(k);
    if (it != obj->end())
      if (auto* s = std::get_if<std::string>(&it->second)) out = *s;
  };
  str("bundle_id", m.bundle_id);
  str("session_id", m.session_id);
  str("platform", m.platform);
  str("model", m.model);
  str("os", m.os);
  str("app_version", m.app_version);
  str("clock_base", m.clock_base);
  str("capture_settings_json", m.capture_settings_json);
  auto it = obj->find("clock_offset_ns");
  if (it != obj->end())
    if (auto* d = std::get_if<double>(&it->second)) m.clock_offset_ns = int64_t(*d);
}

}  // namespace

RecoverResult recover(const std::string& dir_s) {
  RecoverResult r;
  const fs::path dir(dir_s);
  std::error_code ec;
  if (fs::exists(dir / "manifest.json")) {
    r.ok = r.already_complete = true;
    return r;
  }
  const fs::path streams = dir / "streams.mcap";
  if (!fs::exists(streams)) {
    r.error = "no streams.mcap";
    return r;
  }
  auto c = bundle::read_streams(streams.string());
  if (!c.opened) {
    r.error = "streams.mcap unreadable";
    return r;
  }
  if (c.intrinsics.empty() || c.poses.empty()) {
    r.error = "nothing recoverable (no intrinsics or poses before the interruption)";
    return r;
  }

  bundle::BundleMeta meta;
  meta.bundle_id = meta.session_id = dir.filename().string();
  load_meta(dir, meta);
  meta.completion_status = "recovered";
  meta.completion_reason = "interrupted session recovered by ck_recover";

  const fs::path tmp = dir / "streams.mcap.recovering";
  bundle::StreamWriter w;
  if (!w.open(tmp.string())) {
    r.error = "cannot write " + tmp.string();
    return r;
  }
  for (auto& k : c.intrinsics) w.write(k);
  for (auto& p : c.poses) w.write(p);
  for (auto& g : c.gyro) w.write_gyro(g);
  for (auto& a : c.accel) w.write_accel(a);
  std::set<std::string> referenced;
  for (auto f : c.frames) {
    if (f.keyframe && !f.file.empty()) {
      if (complete_jpeg(dir / f.file)) {
        referenced.insert(f.file);
        ++r.keyframes;
      } else {
        f.keyframe = false;
        f.file.clear();
        f.reasons.push_back("RECOVERY_MISSING_FILE");
        ++r.dropped_frame_records;
      }
    }
    w.write(f);
  }
  int64_t t_end = 0;
  for (auto& e : c.events) {
    w.write(e);
    t_end = std::max(t_end, e.t_ns);
  }
  for (auto& p : c.points) w.write(p);
  for (auto& p : c.planes) w.write(p);
  for (auto& p : c.poses) t_end = std::max(t_end, p.t_ns);
  bundle::EventRecord ev;
  ev.t_ns = t_end + 1;
  ev.source = "session";
  ev.code = "SESSION_RECOVERED";
  ev.message = "stream recovered after interruption";
  ev.attrs = {{"undecodable_records", std::to_string(c.undecodable)},
              {"dropped_frame_records", std::to_string(r.dropped_frame_records)}};
  w.write(ev);
  auto counts = w.close();
  fs::rename(tmp, streams, ec);
  if (ec) {
    r.error = "rename failed: " + ec.message();
    return r;
  }

  for (auto& e : fs::directory_iterator(dir / "keyframes", ec)) {
    auto rel = "keyframes/" + e.path().filename().string();
    if (!referenced.count(rel)) {
      fs::remove(e.path(), ec);
      ++r.removed_orphans;
    }
  }
  for (auto& e : fs::directory_iterator(dir, ec)) {
    auto name = e.path().filename().string();
    if (name == "session.json" || name.size() > 4 && name.substr(name.size() - 4) == ".tmp") fs::remove(e.path(), ec);
  }
  {
    auto rc = bundle::read_streams(streams.string());
    meta.validation_json = bundle::validate_semantic(dir.string(), rc, meta.completion_status, &counts).to_json();
  }
  if (!bundle::write_camera_json(dir.string(), c.intrinsics.front(), meta)) {
    r.error = "camera.json";
    return r;
  }
  if (!bundle::write_manifest(dir.string(), meta, counts, bundle::list_files(dir.string()))) {
    r.error = "manifest.json";
    return r;
  }
  r.poses = c.poses.size();
  r.frames = c.frames.size();
  r.ok = true;
  return r;
}

}  // namespace capturekit::session
