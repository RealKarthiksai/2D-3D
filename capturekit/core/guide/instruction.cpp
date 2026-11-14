#include "capturekit/guide/instruction.hpp"

#include <algorithm>
#include <cmath>

namespace capturekit::guide {

const char* type_name(InstrType t) {
  static const char* n[] = {"SLOW_DOWN", "HOLD_STILL", "RELOCALIZE", "MOVE_TO", "ROTATE_TOWARD", "TILT_UP",
                            "TILT_DOWN", "STEP_BACK", "SWEEP", "MORE_LIGHT", "FINISH"};
  return n[int(t)];
}
int type_priority(InstrType t) {
  switch (t) {
    case InstrType::SlowDown: case InstrType::HoldStill: case InstrType::Relocalize: return 3;
    case InstrType::MoreLight: return 1;
    default: return 2;
  }
}
const char* life_name(LifeState s) {
  static const char* n[] = {"ACTIVE", "COMPLETED", "EXPIRED", "SUPERSEDED"};
  return n[int(s)];
}

namespace {
struct Tpl { const char* shortt; const char* longt; };
// Message catalog (locale "en"). Placeholders {dir} {m} {deg}. IDs are stable keys for translation.
const std::map<std::string, Tpl>& catalog() {
  static const std::map<std::string, Tpl> c = {
      {"slow_down", {"Slow down.", "You are moving too fast. Slow down so the photos stay sharp."}},
      {"hold_still", {"Hold still.", "Tracking was lost. Hold the phone still and point it at the area you just scanned."}},
      {"relocalize", {"Go back to where you scanned.", "Tracking is still lost. Walk back to an area you already scanned and move slowly."}},
      {"move.to", {"Walk {dir}, {m} meters.", "Walk {dir} about {m} meters to reach the next area to scan."}},
      {"rotate.to", {"Turn {dir} toward the unscanned area.", "Turn {dir}, about {deg} degrees, to face the area that still needs scanning."}},
      {"tilt.up", {"Tilt up to scan the ceiling.", "Tilt the phone up about {deg} degrees to scan the upper wall and ceiling."}},
      {"tilt.down", {"Tilt down to scan the floor.", "Tilt the phone down about {deg} degrees to scan the lower wall and floor."}},
      {"step.back", {"Step back to see the whole wall.", "You are too close. Step back about {m} meters so the wall fits in view."}},
      {"sweep", {"Turn slowly left to scan the room.", "Stand still and turn slowly all the way around so the phone can see the room."}},
      {"more_light", {"It is too dark. Turn on a light.", "The image is too dark. Turn on a light or open a curtain."}},
      {"finish", {"Scan complete. Say done or tap finish.", "The room is covered. Say done or tap Finish to save the scan."}},
  };
  return c;
}
std::string dir_word(double rel_deg) {
  double a = std::fabs(rel_deg);
  if (a < 20) return "ahead";
  if (a > 150) return "around";
  std::string side = rel_deg > 0 ? "left" : "right";
  if (a < 45) return "slightly " + side;
  return side;
}
std::string num(double v) {
  char b[16];
  std::snprintf(b, sizeof b, v == std::floor(v) ? "%.0f" : "%.1f", v);
  return b;
}
}  // namespace

std::string InstructionGenerator::render(const std::string& id, const std::map<std::string, std::string>& vars, bool longer) {
  auto it = catalog().find(id);
  if (it == catalog().end()) return id;
  std::string s = longer ? it->second.longt : it->second.shortt;
  for (auto& [k, v] : vars)
    for (size_t p; (p = s.find("{" + k + "}")) != std::string::npos;) s.replace(p, k.size() + 2, v);
  return s;
}

bool InstructionGenerator::cooled(InstrType t, int64_t now) const {
  auto it = last_issue_.find(int(t));
  if (it == last_issue_.end()) return true;
  double cd = 0;
  switch (t) {
    case InstrType::SlowDown: cd = p_.cd_slow_down; break;
    case InstrType::HoldStill: case InstrType::Relocalize: cd = p_.cd_hold_still; break;
    case InstrType::MoveTo: cd = p_.cd_move; break;
    case InstrType::RotateToward: cd = p_.cd_rotate; break;
    case InstrType::TiltUp: case InstrType::TiltDown: cd = p_.cd_tilt; break;
    case InstrType::StepBack: cd = p_.cd_step_back; break;
    case InstrType::Sweep: cd = p_.cd_sweep; break;
    case InstrType::MoreLight: cd = p_.cd_light; break;
    case InstrType::Finish: cd = p_.cd_finish; break;
  }
  return double(now - it->second) / 1e9 >= cd;
}

int InstructionGenerator::bucket(const Instruction& i) {
  switch (i.type) {
    case InstrType::MoveTo: return int(std::lround(i.param("distance_m") / 0.5)) * 100 + int(std::lround(i.param("rel_bearing_deg") / 30));
    case InstrType::RotateToward: return int(std::lround(i.param("rel_deg") / 30));
    default: return 0;
  }
}

Instruction InstructionGenerator::make(InstrType t, const Situation& s, std::vector<std::pair<std::string, double>> params) {
  Instruction i;
  i.id = next_id_++;
  i.type = t;
  i.params = std::move(params);
  i.priority = type_priority(t);
  i.t_ns = s.t_ns;
  std::map<std::string, std::string> v;
  switch (t) {
    case InstrType::SlowDown: i.msg_id = "slow_down"; i.haptic = "double"; break;
    case InstrType::HoldStill: i.msg_id = "hold_still"; i.haptic = "double"; break;
    case InstrType::Relocalize: i.msg_id = "relocalize"; i.haptic = "double"; break;
    case InstrType::MoveTo: {
      double b = i.param("rel_bearing_deg");
      i.msg_id = "move.to";
      v = {{"dir", dir_word(b)}, {"m", num(i.param("distance_m"))}};
      i.haptic = "tap";
      i.overlay_hint = "arrow:" + num(std::round(b)) + ":" + num(i.param("distance_m"));
      break;
    }
    case InstrType::RotateToward: {
      double d = i.param("rel_deg");
      i.msg_id = "rotate.to";
      v = {{"dir", dir_word(d)}, {"deg", num(std::round(std::fabs(d) / 5) * 5)}};
      i.haptic = "tap";
      i.overlay_hint = "arrow:" + num(std::round(d)) + ":0";
      break;
    }
    case InstrType::TiltUp: i.msg_id = "tilt.up"; v = {{"deg", num(std::round(i.param("deg")))}}; i.haptic = "tap"; i.overlay_hint = "tilt:+"; break;
    case InstrType::TiltDown: i.msg_id = "tilt.down"; v = {{"deg", num(std::round(i.param("deg")))}}; i.haptic = "tap"; i.overlay_hint = "tilt:-"; break;
    case InstrType::StepBack: i.msg_id = "step.back"; v = {{"m", num(i.param("meters", 0.5))}}; i.haptic = "tap"; break;
    case InstrType::Sweep: i.msg_id = "sweep"; i.haptic = "tap"; i.overlay_hint = "sweep:left"; break;
    case InstrType::MoreLight: i.msg_id = "more_light"; i.haptic = "none"; break;
    case InstrType::Finish: i.msg_id = "finish"; i.haptic = "success"; break;
  }
  i.speak_text = render(i.msg_id, v, false);
  i.long_text = render(i.msg_id, v, true);
  return i;
}

bool InstructionGenerator::complete(Active& a, const Situation& s) const {
  const auto& u = s.user;
  switch (a.ins.type) {
    case InstrType::RotateToward: return std::fabs(wrap_pi(a.yaw0 + deg2rad(a.ins.param("rel_deg")) - u.yaw)) < deg2rad(20);
    case InstrType::MoveTo: {
      double moved = std::hypot(u.pos.x() - a.pos0.x(), u.pos.z() - a.pos0.z());
      bool at = s.plan.target.valid && std::hypot(s.plan.target.x - u.pos.x(), s.plan.target.z - u.pos.z()) < p_.reach_m;
      return moved >= 0.8 * a.ins.param("distance_m") || at;
    }
    case InstrType::TiltUp: return u.pitch >= a.pitch0 + deg2rad(a.ins.param("deg")) - deg2rad(10) || u.pitch > deg2rad(20);
    case InstrType::TiltDown: return u.pitch <= a.pitch0 - deg2rad(a.ins.param("deg")) + deg2rad(10) || u.pitch < -deg2rad(20);
    case InstrType::StepBack: return s.too_close < 0.3 || std::hypot(u.pos.x() - a.pos0.x(), u.pos.z() - a.pos0.z()) >= 0.4;
    case InstrType::Sweep: return std::fabs(a.cum_yaw) >= deg2rad(300);
    case InstrType::SlowDown: return u.speed < 0.8 && std::fabs(u.yaw_rate) < 1.0 && s.blur_streak == 0;
    case InstrType::HoldStill: case InstrType::Relocalize: return s.tracking_state == 1;
    case InstrType::MoreLight: return !s.underexposed;
    case InstrType::Finish: return false;
  }
  return false;
}

void InstructionGenerator::finish_active(Active& a, LifeState st, const Situation& s, std::vector<LifeEvent>& ev) {
  ev.push_back({a.ins, st, s.t_ns, s.completeness});
}

void InstructionGenerator::update(const Situation& s, std::vector<Instruction>& out, std::vector<LifeEvent>& events) {
  if (start_ns_ < 0) start_ns_ = s.t_ns;
  // 1. Lifecycle of active instructions.
  for (auto& a : active_) {
    a.cum_yaw += wrap_pi(s.user.yaw - a.last_yaw);
    a.last_yaw = s.user.yaw;
  }
  for (size_t k = 0; k < active_.size();) {
    Active& a = active_[k];
    LifeState st = LifeState::Active;
    if (complete(a, s)) st = LifeState::Completed;
    else if (double(s.t_ns - a.ins.t_ns) / 1e9 > p_.expire_s) st = LifeState::Expired;
    if (st != LifeState::Active) {
      finish_active(a, st, s, events);
      active_.erase(active_.begin() + long(k));
    } else {
      ++k;
    }
  }
  auto has_active = [&](int prio) {
    return std::any_of(active_.begin(), active_.end(), [&](auto& a) { return a.ins.priority == prio; });
  };
  auto issue = [&](Instruction ins) {
    // A new instruction supersedes an active one of the same priority class.
    for (size_t k = 0; k < active_.size();)
      if (active_[k].ins.priority == ins.priority) {
        finish_active(active_[k], LifeState::Superseded, s, events);
        active_.erase(active_.begin() + long(k));
      } else {
        ++k;
      }
    Active a;
    a.ins = ins;
    a.yaw0 = a.last_yaw = s.user.yaw;
    a.pitch0 = s.user.pitch;
    a.pos0 = s.user.pos;
    active_.push_back(a);
    last_issue_[int(ins.type)] = s.t_ns;
    if (ins.priority <= 2) last_guidance_ns_ = s.t_ns;
    out.push_back(std::move(ins));
  };
  auto same_active = [&](const Instruction& cand) {
    for (auto& a : active_)
      if (a.ins.type == cand.type && bucket(a.ins) == bucket(cand)) return true;
    return false;
  };

  // 2. Urgent interrupts (bypass the global gap, respect their own cooldown).
  if (s.tracking_state == 3) {
    bool long_lost = double(s.t_ns - s.lost_since_ns) / 1e9 >= p_.lost_relocalize_s;
    InstrType t = long_lost ? InstrType::Relocalize : InstrType::HoldStill;
    // A long loss escalates HOLD_STILL -> RELOCALIZE even while HOLD_STILL is still active.
    const bool escalate = t == InstrType::Relocalize && std::any_of(active_.begin(), active_.end(), [](auto& a) { return a.ins.type == InstrType::HoldStill; });
    if (cooled(t, s.t_ns) && (!has_active(3) || escalate)) issue(make(t, s, {}));
    return;  // nothing else while tracking is lost
  }
  // Do not scold a turn the user is making because we asked for it (unless it is wild).
  const bool turning_on_request = std::any_of(active_.begin(), active_.end(), [](auto& a) {
    return a.ins.type == InstrType::RotateToward || a.ins.type == InstrType::Sweep || a.ins.type == InstrType::MoveTo;
  }) && std::fabs(s.user.yaw_rate) < 2.0;
  if (s.blur_streak >= int(p_.blur_frames) && !turning_on_request && cooled(InstrType::SlowDown, s.t_ns) && !has_active(3)) {
    issue(make(InstrType::SlowDown, s, {}));
    return;
  }
  if (has_active(3)) return;  // let the safety instruction play out
  const bool gap_ok = last_guidance_ns_ < 0 || double(s.t_ns - last_guidance_ns_) / 1e9 >= p_.min_gap_s;

  // 3. Low-priority info.
  if (s.underexposed && cooled(InstrType::MoreLight, s.t_ns) && gap_ok && !has_active(1)) {
    issue(make(InstrType::MoreLight, s, {}));
    return;
  }

  // 4. Guidance from the plan.
  if (!gap_ok) return;
  const auto& u = s.user;
  auto try_issue = [&](Instruction cand) {
    if (!cooled(cand.type, s.t_ns) || same_active(cand)) return false;
    issue(std::move(cand));
    return true;
  };
  switch (s.plan.mode) {
    case Mode::Finish:
      try_issue(make(InstrType::Finish, s, {}));
      return;
    case Mode::Sweep:
      if (double(s.t_ns - start_ns_) / 1e9 >= p_.sweep_after_s) try_issue(make(InstrType::Sweep, s, {{"dir", 1}, {"degrees", 360}}));
      return;
    case Mode::Target: break;
  }
  const Target& tg = s.plan.target;
  if (!tg.valid) return;
  double dx = tg.x - u.pos.x(), dz = tg.z - u.pos.z();
  double dist = std::hypot(dx, dz);
  double rel = wrap_pi(std::atan2(dx, dz) - u.yaw);  // + = left
  if (s.too_close > 0.5 && try_issue(make(InstrType::StepBack, s, {{"meters", 0.5}}))) return;
  if (dist > p_.reach_m) {
    if (std::fabs(rel) > deg2rad(p_.rotate_deg)) {
      try_issue(make(InstrType::RotateToward, s, {{"rel_deg", std::round(rad2deg(rel))}}));
    } else {
      double d = std::max(0.5, std::round(dist / 0.5) * 0.5);
      try_issue(make(InstrType::MoveTo, s, {{"distance_m", d}, {"rel_bearing_deg", std::round(rad2deg(rel))}}));
    }
    return;
  }
  double yaw_err = wrap_pi(tg.yaw - u.yaw);
  if (std::fabs(yaw_err) > deg2rad(p_.reach_yaw_deg)) {
    try_issue(make(InstrType::RotateToward, s, {{"rel_deg", std::round(rad2deg(yaw_err))}}));
    return;
  }
  double pitch_err = rad2deg(tg.pitch - u.pitch);
  if (pitch_err > p_.tilt_deg) try_issue(make(InstrType::TiltUp, s, {{"deg", std::round(pitch_err)}}));
  else if (pitch_err < -p_.tilt_deg) try_issue(make(InstrType::TiltDown, s, {{"deg", std::round(-pitch_err)}}));
}

}  // namespace capturekit::guide
