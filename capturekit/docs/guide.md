# GuideMind (Phase 8): coverage, planner, instructions

Core: `core/include/capturekit/guide/` (deterministic, no clock, no I/O). Stages, each with unit tests in `core/tests/guide_test.cpp`:

| Stage | File | What it does |
|---|---|---|
| Params | params.hpp | every threshold, versioned, hash recorded in the manifest (`guide_config_hash`) |
| LiveMap | live_map.hpp | AR planes: updates replace, duplicates merge, floor/ceiling levels, room extents |
| Coverage | coverage.hpp | per-plane 10 cm cells: qualifying views, ray spread, range, sharpness; sufficient = 3 views + 15 deg; occluded-forever cells drop out |
| NavGrid | nav_grid.hpp | 2D occupancy: free = traversed or carved by sight rays or seen floor; obstacles from points 0.2-1.8 m; target needs whole body disc known free |
| Planner | planner.hpp | next-best-view: lattice x headings x pitches, score = marginal gain / (path + turn + safety + instruction overhead), overlap constraint, hysteresis 1.3, discovery looks for a missing ceiling/floor, rule-based fallbacks (sweep, walk to unseen area, finish) |
| Instructions | instruction.hpp | fixed vocabulary, egocentric text (positive yaw = left), cooldowns, 2 s minimum gap, priority interrupts (tracking loss, blur), lifecycle (issued -> completed / expired / superseded) |
| Log | guide_log.hpp | `guidance_log.json`: instructions, time-to-compliance, coverage delta, coverage series |

Vocabulary: SLOW_DOWN, HOLD_STILL, RELOCALIZE, MOVE_TO, ROTATE_TOWARD, TILT_UP, TILT_DOWN, STEP_BACK, SWEEP, MORE_LIGHT, FINISH
(templates in `instruction.cpp`, message ids are the translation keys; short spoken variants are <= 8 words).

Where it runs: `GuideEngine` inside the capture `Session` (instructions go out as `{"type":"instruction",...}` events via
`ck_poll_events` and as `/events` GUIDE_INSTRUCTION records; the log is written into the bundle at finalize; the planner rate follows the
governor's `ml_rate_scale`), and as the `guide` replay sink (`ckit replay B --sink guide`, `ckit guide-eval B`).
Input tiers: B (planes + sparse points) works today, C (pose only) falls back to SWEEP; tier A (depth) is not wired.

## Evaluation
`ckit guide-eval --seeds 3 --max-s 450 --json docs/reports/guide_eval_synth.json` (planner vs scripted vs wander, 3 rooms, see
ADR 0011). Metrics: coverage at the horizon, time to 80/90 %, instructions, repetition rate, target switches per minute, unsafe
targets against the true room, claimed-vs-true coverage gap. Results: `docs/reports/guide_eval_synth.md`.

## Budgets and limits
Average step 1-2 ms (engine + simulator ground truth, `GuideSim.AverageStepWithinBudget`, budget 10 ms); a replan costs about 70 ms every
0.5 s, so the engine runs on the session worker, never on an AR callback.
Known limits: only detected planes are covered (Phase 9 adds the room shell and missing-area finder); no depth tier; obstacles come from
sparse points (a dilated 3x3 neighbourhood closes gaps); a person is modelled, not measured; English only.

## Overlay event (glanceable "what the app understands")
`GuideEngine::overlay(t_ns)` (`core/include/capturekit/guide/overlay.hpp`) builds an `OverlayModel`; `Session::guide_tick` emits it as a
**non-critical** event every 500 ms of event time (dropped and counted if the outbound queue is full; not written to the bundle; ADR 0013).
No `capturekit.h` change. Flat JSON, sorted keys, always under 4 KB (tested), one line per event:

| Field | Meaning |
|---|---|
| `type` ("overlay"), `t_ns` | event type, event time |
| `completeness`, `seen` | 0..1, sufficient / seen fraction of valid plane cells |
| `tracking` | 1 normal, 2 limited, 3 not available |
| `mode` | `sweep` \| `target` \| `finish` |
| `wall_found`, `floor_found`, `ceiling_found`, `walls` | map flags and wall plane count |
| `user_x`, `user_z`, `user_yaw_deg` | user position (m) and heading (positive = left) |
| `has_target`, `target_x`, `target_z` | current plan target (only while `mode` is `target`) |
| `why_id`, `why_text` | one reason, rendered by the core catalog (<= 8 words, English): sweep, tracking lost / weak, moving too fast, too dark, left/right/ahead/behind wall not scanned yet, ceiling/floor not seen yet, scan complete, keep scanning |
| `surfaces` | wall segments, ONE string `x0,z0,x1,z1,pct,id;...` (world metres, pct = % of the wall's cells seen, id = stable 1-based wall number in first-seen order, never renumbered), at most 12 walls. v1 had 5 fields (no id); parsers accept 5 or 6 |
| `yaw_mask` | integer 0..4095 (overlay v2, ADR 0022): bit i set = a keyframe looked along heading [30i, 30i+30) deg (yaw positive = left, 0 = world +z). Keyframes looking within 20 deg of straight up/down are ignored. Moves with zero planes; absent in v1 |

Golden: `core/tests/golden/overlay_box_seed1.jsonl` (sim `box`, seed 1, 60 s, one snapshot per 5 s). Regenerate with
`CK_UPDATE_GOLDEN=1 capturekit_core_tests --gtest_filter='Overlay.Golden*'` and review the diff.
