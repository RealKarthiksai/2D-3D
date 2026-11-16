# PROGRESS
Program = 2 parts (docs/program-spec/README.md). This whole repo = Part 1 only (Part 2 = repo roomscan-recon). Phases 0–12. Branch `main`.

## Done (software, verified on synthetic data; device acceptance blocked, see docs/STATUS.md §3)
- 0–1 conventions, raw recorders, Scan Bundle spec/writer/validator (cd456bf) · 2 geometry core (2e55389) · 3 replay harness (d0c03ec)
- 4 quality + keyframe selector (8b00160) · 5 thermal/battery governor (ae5ede6) · 6 C API + Session + recovery, JNI/Android capture, iOS source (09cfcba)
- 7 on-device validator, verdicts, tus clients, verifier, docker failure matrix (fe73262) · 8 guidance core: LiveMap, coverage, NavGrid, planner, simulator (606e618)
- JNI handle map (f3b071a, ADR 0012) · core OverlayModel event (5fd4887, f93c5ea, ADR 0013)
- 9 PARTIAL (~10 %): understanding types + SurfaceClassifier behind param `understanding` (default 0) (0a4a67d). Openings, objects, shell, gaps not started.
- 10/11 PARTIAL, Android only: overlay parsing, IngestStats, Kotlin SpeechScheduler (ac76a25, ADR 0014); perf baseline doc (ae934ab); sample HUD, UnderstandingView, TTS, ReviewActivity (verdict only, no upload), permissions (21ce79a). No core/spec/ckit work; iOS has none.
- Umbrella plan of all open items: .claude/agent-memory/planner/briefs/remaining-work-audit.md (milestones M0–M14).

## Decisions
- capturekit/docs/decisions/README.md indexes ADRs 0001–0014 (latest: 0014 SpeechScheduler in Kotlin for now).

## Commands
- C++: `cd capturekit && cmake -S core -B build/core && cmake --build build/core -j8 && ctest --test-dir build/core`
- Py module + C API lib: `capturekit/scripts/build_core_py.sh`; Python: `cd capturekit/tools && uv run pytest -q && uv run ruff check . && uv run ruff format --check .`
- Bundles: `ckit import-raw RAW OUT [--select-keyframes]`, `ckit validate B --level quality`, `ckit verdict B`
- Replay: `ckit replay B --sink metrics --sink quality --sink governor [faults]`; baselines `scripts/replay_golden.sh [--update]`
- Reports: `ckit reprojection-report`, `ckit quality-report B --uniform` (SfM, minutes)
- Simulated phone: `ckit simulate-capture B OUT [--kill-after N]`, `ckit recover DIR`
- Guide: `ckit guide-eval [B...] [--seeds 3 --max-s 450]` (sim matrix ~10 min), `ckit replay B --sink guide`
- Upload: `dev-server/smoke.sh`, `dev-server/failure_test.sh` (docker), `ckit upload B --consent`
- Android: `cd capturekit/android && ANDROID_HOME=~/Android/Sdk ./gradlew assembleDebug testDebugUnitTest`
- Sanitizers: CI job core-sanitizers (cmake -fsanitize=address,undefined | thread)

## Gotchas
- History was rewritten: older hashes in reports/briefs (93f20d6, bd78bcd, fb59838, …) no longer exist; use `git log`.
- MCAP indexed reader hangs on unfinalized files (stream_reader scans linearly). Per-channel t_ns must be strictly increasing (events/planes +1 ns).
- Signed int overflow in test loops = UB. Use int64_t. `pkill -f` can kill your own shell. C API takes int32_t for enums (UBSAN). tus-android-client POM omits tus-java-client: declared explicitly.
- pycolmap DB image IDs are unordered: export with DB ids. SfM registration varies run to run.
- Killed before the first MCAP chunk flush (~1 s of event time) loses that data; recovery says so.
- /tmp has a per-user quota (VS Code cpptools cache); long outputs go to capturekit/build/.
- Guide sim: unsafe-target check uses the TRUE room; random wander beats the planner on time-to-90 (reports/guide_eval_synth.md). Long sims: 6+ procs in parallel, never foreground (10 min tool limit).
- iOS/Swift never compiled (no macOS); macOS CI job never seen green. Schema minor clash: phase9 and consent briefs both claim 1.1.0 (proposal: phase9 1.1.0, consent 1.2.0).

## Next
- M1 Android stability finish (in progress, uncommitted): luma/UV ring, AIMD frameEveryN, off-main ARCore shutdown, exit-reason telemetry (brief android-stability-and-speed steps 5-9). Then M2 Phase 9 openings (brief phase9-room-understanding step 2).
