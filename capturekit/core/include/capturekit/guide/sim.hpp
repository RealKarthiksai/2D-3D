#pragma once
// Guidance simulator (Phase 8): deterministic synthetic rooms + a human-walker model + sensor model.
// Answers "does the planner get a person to good coverage faster than a scripted walk?" without
// phones. Ground truth coverage uses the SAME sufficiency rule as the engine, with true occlusion.
// All randomness comes from guide::Rng (seeded), so reports are byte-identical for equal inputs.
#include <array>
#include <string>
#include <utility>
#include <vector>

#include "capturekit/guide/params.hpp"
#include "capturekit/guide/types.hpp"

namespace capturekit::guide::sim {

struct Box {
  double x0, z0, x1, z1, h;
};

struct Room {
  std::string name;
  std::vector<std::pair<double, double>> poly;                    // CCW footprint in (x, z)
  std::vector<std::array<double, 4>> rects;                       // disjoint rectangles whose union is the footprint
  double height = 2.5;
  std::vector<Box> boxes;                                         // furniture blocks
  double start_x = 1, start_z = 1, start_yaw = 0;
};

/// "box", "L", "furniture".
Room make_room(const std::string& name);

struct Human {
  double speed = 0.9;            // m/s
  double compliance = 0.85;      // probability an instruction is followed
  double delay_min = 1.0, delay_max = 3.0;  // reaction delay (s)
  double yaw_noise_deg = 6;      // error when turning toward a bearing
  double turn_dps = 70, pitch_dps = 40;
};

struct Config {
  std::string room = "box";
  std::string policy = "planner";  // planner | scripted | wander
  uint64_t seed = 0;
  double max_s = 300;
  bool trace = false;  // add a coarse state trace to the report (debugging)
  Human human;
  GuideParams guide;
};

/// Runs one simulated scan and returns the report JSON (sorted keys, fixed rounding).
std::string simulate(const Config& cfg);

}  // namespace capturekit::guide::sim
