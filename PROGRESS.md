# PROGRESS

## Done
- Scaffolding v0.0.1 (362a839).
- Phase 0 software + Phase 1 complete on synthetic data (93f20d6). Device acceptance open; see docs/STATUS.md §3.

## Decisions
- capturekit/docs/decisions/0001 JPEG not video · 0002 synthetic golden stand-ins · 0003 intrinsics pixel centre (verify Phase 2) · 0004 /ar/points + /ar/planes channels

## Commands
- C++: `cd capturekit && cmake -S core -B build/core && cmake --build build/core -j8 && ctest --test-dir build/core`
- Py core module: `capturekit/scripts/build_core_py.sh` (ckit loads build/core-py/py/_ckcore)
- Python: `cd capturekit/tools && uv run pytest -q && uv run ruff check . && uv run ruff format --check .`
- Golden synth: `capturekit/scripts/make_golden.sh` (8 sessions, ~20 min) → build/golden/raw
- Bundle: `ckit import-raw <raw> <bundle> && ckit validate <bundle> --level quality`
- Android: `cd capturekit/android && ANDROID_HOME=~/Android/Sdk ./gradlew assembleDebug testDebugUnitTest`

## Gotchas
- Spec docs: docs/program-spec/room-scanner-program/ (untracked; ask user before committing).
- User's staged `reconstruction/` deletions + README edits: never include; commit with `git commit -- <paths>`.
- MCAP C++ indexed reader hangs on unfinalized files → stream_reader uses TypedRecordReader linear scan.
- Signed int overflow in test loops (i*33'000'000) = UB → infinite loop in Release. Use int64_t.
- `pkill -f <name>` kills the calling shell if the name is in its command line.
- protobuf python pinned 3.20.3 (nerfstudio extra); use descriptor-based dynamic classes.
- Planes: same-frame anchors get +1 ns each (per-channel strict monotonic t_ns).
- iOS/Swift never compiled (no macOS): treat as unverified until CI macOS job runs.

## Next
- Phase 2: geometry core (adapters, projection-invariance tests, export-colmap, reprojection report).
