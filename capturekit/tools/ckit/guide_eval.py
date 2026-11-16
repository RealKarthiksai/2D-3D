"""`ckit guide-eval`: does guidance help? Simulated walkers on synthetic rooms (the C++ GuideMind
engine runs for real), and guidance replay on recorded bundles.

Simulator mode: rooms x policies x seeds. Policies: `planner` (follows instructions with a human
model: compliance, 1-3 s reaction delay, yaw noise), `scripted` (looped perimeter laps + centre
spins) and `wander` (random walk). Ground-truth coverage uses the engine's sufficiency rule
with true occlusion; time-to-coverage that is never reached is censored at the horizon.
"""

from __future__ import annotations

import json
import statistics
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

from ckit.core import load

ROOMS = ("box", "L", "furniture")
POLICIES = ("planner", "scripted", "wander")
KEEP = (
    "duration_s", "path_m", "finished", "coverage_gt_final", "coverage_claimed_final", "t80_s",
    "t90_s",
    "t95_s", "finish_issued_s", "flap_per_min", "unsafe_targets_gt", "tracking_loss_events",
    "blocked_events", "instructions_received", "instructions_followed", "keyframes", "params_hash",
)  # fmt: skip
# Spoken-prompt budget: mirrors the Android SpeechScheduler.Config.maxPerMinute default
# (non-urgent lines per minute).
SPEECH_BUDGET_PER_MIN = 8.0


def instruction_demand_per_min(instructions, duration_s) -> float | None:
    """Instruction demand/min: engine instructions (all priorities) per minute, counted BEFORE the
    Android speech scheduler drops anything (gap, budget, repeat, busy). An upper bound on spoken
    prompts/min, not the spoken rate. None when duration is unknown or zero."""
    if instructions is None or not duration_s or duration_s <= 0:
        return None
    return round(instructions * 60.0 / duration_s, 2)


def _one(args: tuple) -> dict:
    room, policy, seed, max_s, params, compliance, speed = args
    r = json.loads(load().guide.simulate(room, policy, seed, max_s, params, compliance, speed))
    out = {k: r.get(k) for k in KEEP}
    out.update(room=room, policy=policy, seed=seed)
    g = r.get("guide")
    if g:
        out["repetition_rate"] = g["log"]["repetition_rate"]
        out["instructions"] = g["log"]["instructions"]
        out["unsafe_targets_engine"] = g["log"]["unsafe_targets"]
        out["by_type"] = g["log"]["by_type"]
    out["instruction_demand_per_min"] = instruction_demand_per_min(
        out.get("instructions"), out.get("duration_s")
    )
    return out


def _median_or_none(xs) -> float | None:
    v = [x for x in xs if x is not None]
    return round(statistics.median(v), 3) if v else None


def _censored(v, horizon: float) -> float:
    return horizon + 1.0 if v is None or v < 0 else float(v)


def simulate_matrix(
    rooms=ROOMS, policies=POLICIES, seeds=3, max_s=450.0, params: dict | None = None,
    compliance=0.85, speed=0.9, workers=6,
) -> dict:  # fmt: skip
    p = json.dumps(params or {}, sort_keys=True)
    jobs = [
        (r, pol, s, max_s, p, compliance, speed)
        for r in rooms
        for pol in policies
        for s in range(seeds)
    ]
    with ProcessPoolExecutor(max_workers=workers) as ex:
        runs = list(ex.map(_one, jobs))
    runs.sort(key=lambda r: (r["room"], r["policy"], r["seed"]))
    summary = summarize(runs, rooms, policies, max_s)
    return {
        "config": {"rooms": list(rooms), "policies": list(policies), "seeds": seeds, "max_s": max_s,
                   "params": params or {}, "compliance": compliance, "speed": speed},
        "summary": summary, "acceptance": acceptance(runs, summary, rooms), "runs": runs,
    }  # fmt: skip


def summarize(runs: list[dict], rooms, policies, max_s: float) -> dict:
    """Per room x policy medians (pure)."""
    summary: dict = {}
    for r in rooms:
        for pol in policies:
            g = [x for x in runs if x["room"] == r and x["policy"] == pol]
            summary.setdefault(r, {})[pol] = {
                "median_final_coverage": round(
                    statistics.median(x["coverage_gt_final"] for x in g), 3
                ),
                "median_t80_s": statistics.median(_censored(x["t80_s"], max_s) for x in g),
                "median_t90_s": statistics.median(_censored(x["t90_s"], max_s) for x in g),
                "reached_90": sum(1 for x in g if x["t90_s"] is not None and x["t90_s"] >= 0),
                "median_instruction_demand_per_min": _median_or_none(
                    x.get("instruction_demand_per_min") for x in g
                ),
                "median_repetition_rate": _median_or_none(x.get("repetition_rate") for x in g),
                "runs": len(g),
            }
    return summary


def acceptance(runs: list[dict], summary: dict, rooms) -> dict:
    """Acceptance block over planner runs (pure). Budget verdict is None without data."""
    pl = [x for x in runs if x["policy"] == "planner"]
    gap = max(
        (abs((x["coverage_claimed_final"] or 0) - x["coverage_gt_final"]) for x in pl), default=0
    )
    rep_rate = statistics.median([x.get("repetition_rate", 0) for x in pl] or [0])
    ppm = [
        x["instruction_demand_per_min"]
        for x in pl
        if x.get("instruction_demand_per_min") is not None
    ]
    return {
        "planner_beats_scripted_median_t90_rooms": sum(
            1 for r in rooms if "scripted" in summary[r] and "planner" in summary[r]
            and summary[r]["planner"]["median_t90_s"] < summary[r]["scripted"]["median_t90_s"]
        ),
        "planner_beats_wander_median_t90_rooms": sum(
            1 for r in rooms if "wander" in summary[r] and "planner" in summary[r]
            and summary[r]["planner"]["median_t90_s"] < summary[r]["wander"]["median_t90_s"]
        ),
        "rooms": len(rooms),
        "unsafe_targets_gt_total": sum(x["unsafe_targets_gt"] or 0 for x in pl),
        "max_flap_per_min": max((x["flap_per_min"] or 0 for x in pl), default=0),
        "max_claim_vs_truth_gap": round(gap, 3),
        "median_repetition_rate": round(rep_rate, 3),
        "median_instruction_demand_per_min": round(statistics.median(ppm), 2) if ppm else None,
        "max_instruction_demand_per_min": max(ppm) if ppm else None,
        "prompts_per_min_budget": SPEECH_BUDGET_PER_MIN,
        "instruction_demand_within_budget": (
            all(v <= SPEECH_BUDGET_PER_MIN for v in ppm) if ppm else None
        ),
    }  # fmt: skip


def replay_bundle(bundle: Path, params: dict | None = None) -> dict:
    """Guidance engine on a recorded bundle: final coverage, instruction count, repetition rate,
    plan-through-obstacle (unsafe target) violations."""
    from ckit.replay.driver import load_events, replay

    rep = replay(bundle, ["guide"], sink_config={"guide": params or {}})
    g = rep["sinks"]["guide"]["engine"]
    ts = [e[0] for e in load_events(bundle)[0]]
    dur = (max(ts) - min(ts)) / 1e9 if ts else 0.0
    return {
        "bundle": bundle.name,
        "tier": g["tier"],
        "coverage_completeness": g["coverage"]["completeness"],
        "coverage_seen": g["coverage"]["seen"],
        "planes": g["coverage"]["planes"],
        "instructions": g["log"]["instructions"],
        "by_type": g["log"]["by_type"],
        "repetition_rate": g["log"]["repetition_rate"],
        "duration_s": round(dur, 3),
        "instruction_demand_per_min": instruction_demand_per_min(g["log"]["instructions"], dur),
        "unsafe_targets": g["log"]["unsafe_targets"],
        "target_switches": g["log"]["target_switches"],
        "first_instructions": rep["sinks"]["guide"]["first_instructions"][:5],
    }


def table(rep: dict) -> str:
    lines = ["room       policy    cov@end  t80    t90    reached90  demand/min   repetition"]
    for room, d in rep["summary"].items():
        for pol, s in d.items():
            reached = f"{s['reached_90']}/{s['runs']}"
            ppm = s.get("median_instruction_demand_per_min")
            rep_rate = s.get("median_repetition_rate")
            lines.append(
                f"{room:<10} {pol:<9} {s['median_final_coverage']:<8} {s['median_t80_s']:<6.0f} "
                f"{s['median_t90_s']:<6.0f} {reached:<10} {_fmt(ppm):<12} {_fmt(rep_rate)}"
            )
    lines.append(json.dumps(rep["acceptance"], sort_keys=True))
    return "\n".join(lines)


def _fmt(v) -> str:
    return "-" if v is None else f"{v:g}"
