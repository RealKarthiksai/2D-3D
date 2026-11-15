#pragma once
// OverlayModel (Phase 11 / android brief B1): a small, flat snapshot of what the guidance core currently
// understands about the room, for glanceable UI. Built by GuideEngine::overlay(t_ns) from event time only
// (no clock), serialised as the non-critical session event {"type":"overlay",...} (see docs/guide.md).
// All user-facing "why" text comes from the core catalog here; shells render it verbatim.
#include <cstdint>
#include <string>
#include <vector>

namespace capturekit::guide {

struct OverlayWall {
  double x0 = 0, z0 = 0, x1 = 0, z1 = 0;  // horizontal footprint endpoints, world metres
  int seen_pct = 0;                       // 0-100, fraction of the wall's coverage cells seen
};

struct OverlayModel {
  int64_t t_ns = 0;
  double completeness = 0, seen = 0;     // 0..1
  int tracking = 1;                      // 1 normal, 2 limited, 3 not available
  std::string mode = "sweep";            // sweep | target | finish
  bool wall_found = false, floor_found = false, ceiling_found = false;
  int walls = 0;                         // wall planes in the live map
  double user_x = 0, user_z = 0, user_yaw_deg = 0;  // positive yaw = left
  bool has_target = false;
  double target_x = 0, target_z = 0;
  std::string why_id, why_text;
  std::vector<OverlayWall> wall_list;    // at most kMaxWalls, sorted by plane id

  static constexpr size_t kMaxWalls = 12;
  /// Flat JSON, sorted keys, fixed rounding. Walls are ONE delimited string "x0,z0,x1,z1,pct;..." (no nesting).
  std::string to_json() const;
};

/// Core catalog (locale "en") for the "why" line. Every text is at most 8 words.
const char* why_line(const std::string& why_id);
/// All why ids, for tests.
std::vector<std::string> why_ids();

}  // namespace capturekit::guide
