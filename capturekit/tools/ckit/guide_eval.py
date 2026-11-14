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
    return out


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
                "runs": len(g),
            }
    pl = [x for x in runs if x["policy"] == "planner"]
    gap = max(
        (abs((x["coverage_claimed_final"] or 0) - x["coverage_gt_final"]) for x in pl), default=0
    )
    rep_rate = statistics.median([x["repetition_rate"] for x in pl] or [0])
    acc = {
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
    }  # fmt: skip
    return {
        "config": {"rooms": list(rooms), "policies": list(policies), "seeds": seeds, "max_s": max_s,
                   "params": params or {}, "compliance": compliance, "speed": speed},
        "summary": summary, "acceptance": acc, "runs": runs,
    }  # fmt: skip


def replay_bundle(bundle: Path, params: dict | None = None) -> dict:
    """Guidance engine on a recorded bundle: final coverage, instruction count, repetition rate,
    plan-through-obstacle (unsafe target) violations."""
    from ckit.replay.driver import replay

    rep = replay(bundle, ["guide"], sink_config={"guide": params or {}})
    g = rep["sinks"]["guide"]["engine"]
    return {
        "bundle": bundle.name,
        "tier": g["tier"],
        "coverage_completeness": g["coverage"]["completeness"],
        "coverage_seen": g["coverage"]["seen"],
        "planes": g["coverage"]["planes"],
        "instructions": g["log"]["instructions"],
        "by_type": g["log"]["by_type"],
        "repetition_rate": g["log"]["repetition_rate"],
        "unsafe_targets": g["log"]["unsafe_targets"],
        "target_switches": g["log"]["target_switches"],
        "first_instructions": rep["sinks"]["guide"]["first_instructions"][:5],
    }


def table(rep: dict) -> str:
    lines = ["room       policy    cov@end  t80    t90    reached90"]
    for room, d in rep["summary"].items():
        for pol, s in d.items():
            lines.append(
                f"{room:<10} {pol:<9} {s['median_final_coverage']:<8} {s['median_t80_s']:<6.0f} "
                f"{s['median_t90_s']:<6.0f} {s['reached_90']}/{s['runs']}"
            )
    lines.append(json.dumps(rep["acceptance"], sort_keys=True))
    return "\n".join(lines)
