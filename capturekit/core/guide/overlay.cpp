#include "capturekit/guide/overlay.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <map>

#include "capturekit/util/json.hpp"

namespace capturekit::guide {

namespace {
// Message catalog (locale "en") for the overlay "why" line; ids are stable translation keys.
const std::map<std::string, const char*>& catalog() {
  static const std::map<std::string, const char*> c = {
      {"why.ceiling", "Ceiling not seen yet"},
      {"why.finish", "Scan complete"},
      {"why.floor", "Floor not seen yet"},
      {"why.keep", "Keep scanning slowly"},
      {"why.limited", "Tracking weak: move slowly"},
      {"why.sweep", "Looking for walls: sweep slowly"},
      {"why.too_dark", "Too dark here"},
      {"why.too_fast", "Moving too fast"},
      {"why.tracking_lost", "Tracking lost"},
      {"why.wall_ahead", "Wall ahead not scanned yet"},
      {"why.wall_behind", "Wall behind you not scanned yet"},
      {"why.wall_left", "Left wall not scanned yet"},
      {"why.wall_right", "Right wall not scanned yet"},
  };
  return c;
}
double r2(double v) { return std::round(v * 100.0) / 100.0 + 0.0; }  // + 0.0 turns -0 into +0
double r4(double v) { return std::round(v * 1e4) / 1e4 + 0.0; }
}  // namespace

const char* why_line(const std::string& id) {
  auto it = catalog().find(id);
  return it == catalog().end() ? "" : it->second;
}

std::vector<std::string> why_ids() {
  std::vector<std::string> v;
  for (auto& [k, _] : catalog()) v.push_back(k);
  return v;
}

std::string OverlayModel::to_json() const {
  std::string ws;
  for (auto& w : wall_list) {
    if (!std::isfinite(w.x0 + w.z0 + w.x1 + w.z1)) continue;  // defence in depth: JSON has no NaN/inf
    char b[112];
    std::snprintf(b, sizeof b, "%.2f,%.2f,%.2f,%.2f,%d,%d", r2(w.x0), r2(w.z0), r2(w.x1), r2(w.z1),
                  std::clamp(w.seen_pct, 0, 100), std::max(w.id, 0));
    if (!ws.empty()) ws += ";";
    ws += b;
  }
  json::Object o;
  o.set("type", "overlay").set("t_ns", t_ns).set("completeness", r4(completeness)).set("seen", r4(seen))
      .set("tracking", tracking).set("mode", mode).set("wall_found", wall_found).set("floor_found", floor_found)
      .set("ceiling_found", ceiling_found).set("walls", walls).set("user_x", r2(user_x)).set("user_z", r2(user_z))
      .set("user_yaw_deg", std::round(user_yaw_deg) + 0.0).set("has_target", has_target)
      .set("target_x", r2(target_x)).set("target_z", r2(target_z)).set("why_id", why_id).set("why_text", why_text)
      .set("surfaces", ws).set("yaw_mask", int64_t(yaw_mask & ((1u << kYawSectors) - 1)));
  return o.dump();
}

}  // namespace capturekit::guide
