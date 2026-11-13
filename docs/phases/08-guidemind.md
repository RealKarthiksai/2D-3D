# Phase 8: GuideMind, the surveyor brain
**Goal/why:** the differentiator and the riskiest part, so it comes after data and replay are trustworthy. Without dense depth, coverage is defined by which views were captured.
Read docs/program-spec/room-scanner-program/B-GuideMind.md first (only in this phase).

## Decisions
- Sparse points + detected planes. Rejected: dense TSDF/mesh (needs depth), neural fields (too heavy).
- Plane-based 2D coverage cells + voxels for objects. Rejected: uniform voxel grid everywhere. Surfels deferred.
- Frontier/next-best-view planner with rule-based fallback. Learned/RL planner deferred until guidance logs exist (Part H).
- Templated instructions. Rejected: LLM phrasing in the real-time loop.
- Semantics optional behind a flag. Rejected: making them required.

## Build (shared C++; platforms supply only audio, haptics, overlay). Stage by stage, each replay-tested before the next.
1. LiveMap: ingest sparse feature points and plane anchors (floor, walls, ceiling) into a gravity-aligned room frame; merge duplicate planes; estimate room extents.
2. CoverageEngine: per-plane cell grids (default 10 cm) + voxels for objects. Per cell: distinct-viewpoint count, angular spread, nearest range, best sharpness, last seen. Covered = configurable (default ≥3 views, ≥15° spread, in range, sharp). Per-surface and total coverage.
3. NavGrid: 2D floor occupancy. Free = traversed or clearly open; obstacles from points/planes above floor height; person-sized footprint.
4. Planner: frontier clusters of uncovered cells scored by coverage gain − travel cost − rotation cost − safety penalty; hysteresis (commit until reached or obsolete); rule-based fallback.
5. InstructionGenerator: fixed vocabulary with parameters ("slow down", "pan left to the corner", "step back to see the full wall", "revisit behind <object>"), per-type cooldowns, no back-to-back repeats, priority interrupts from quality (blur → "slow down" immediately). Output Instruction {type, params, priority, speak_text, haptic, overlay_hint}. Voice first.
6. Semantics (flag): geometry-based plane classification first; ARKit plane classification where supported (check support); interface for an on-device detector/segmenter (Core ML, LiteRT) with a stub. Guidance must work with semantics off.
7. GuideLog: instructions, issue time, time-to-compliance, coverage delta → guidance_log.json.
8. `ckit guide-eval`: final coverage, instruction count, repetition rate, plan-through-obstacle violations.

## Tests
Unit tests per stage with synthetic rooms (box, L-shaped, furniture blocks); replay regression on golden sessions; no instruction targets a position inside an obstacle; cooldown and repetition tests.

## Constraints
Deterministic for a given input stream. No allocation spikes on the update path. Average update ≤10 ms on the desktop proxy benchmark.

## Acceptance
All stage tests pass. guide-eval reports committed. Coverage verified against manual labels on 2–3 golden sessions.
