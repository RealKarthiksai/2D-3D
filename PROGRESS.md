# PROGRESS
Program = 2 parts (docs/program-spec/README.md). This whole repo = Part 1 only (Part 2 = repo roomscan-recon). Phases 0–12.

## Done (software, verified on synthetic data; device acceptance blocked, see docs/STATUS.md §3)
- 0–1 conventions, raw recorders, Scan Bundle spec/writer/validator (93f20d6) · 2 geometry core (bd78bcd)
- 3 replay harness (fb59838) · 4 quality + keyframe selector (7943988) · 5 thermal/battery governor (310d205)
- 6 C API + Session + recovery, JNI/Android capture, iOS source (69eaf41)
- 8 guidance core: LiveMap, coverage, NavGrid, planner, instructions, log, simulator, guide sink, in Session (PHASE8)
- 7 on-device validator + verdict table, tus clients (Android compiled, iOS source), verifier, docker failure matrix (ca81065)

## Decisions
- capturekit/docs/decisions 0001 JPEG not video · 0002 synthetic golden · 0003/0005 pixel centre (checkerboard) · 0004 /ar/points+planes · 0006 sharpness ratio · 0007 thermal map · 0008 shell encodes JPEGs · 0009 Android keyframes from ARCore CPU image · 0010 guidance coverage/free-space model · 0011 simulated-walker evaluation

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
- User's staged `reconstruction/` deletions + root README edits: never include; `git commit -- <paths>`. Old A–I docs untracked in legacy-nine-projects/.
- MCAP indexed reader hangs on unfinalized files (stream_reader scans linearly). Per-channel t_ns must be strictly increasing (events/planes +1 ns).
- Signed int overflow in test loops = UB. Use int64_t. `pkill -f` can kill your own shell.
- /tmp has a per-user quota (VS Code cpptools cache); long outputs go to capturekit/build/.
- pycolmap DB image IDs are unordered: export with DB ids. SfM registration varies run to run.
- C API takes int32_t for enums (UBSAN). tus-android-client POM omits tus-java-client: declared explicitly.
- Killed before the first MCAP chunk flush (~1 s of event time) loses that data; recovery says so.
- Guide sim: unsafe-target check uses the TRUE room; random wander beats the planner on time-to-90 (see reports/guide_eval_synth.md). Long sims: run 6+ procs in parallel, never in the foreground (10 min tool limit).
- iOS/Swift never compiled (no macOS): CaptureSession, thermal adapter, TusUploader are unverified.

## Next
- Phase 9: room understanding (surface classes, openings, objects, missing-area finder). Needs the Gate 0 answers in docs/STATUS.md §6 for detector/depth choices; geometry parts need no hardware.
