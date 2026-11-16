# Starting a new session (copy-paste kit)

## 1. Paste this as the first message of the new conversation

```
Continue Part 1 on branch main. Read AGENTS.md, PROGRESS.md and the brief named in its Next
section (under .claude/agent-memory/planner/briefs/) only. Work one milestone of
remaining-work-audit.md at a time, using the repo rules in AGENTS.md. Skip anything that
needs phones, a Mac or people and list it under "blocked" in docs/STATUS.md. Run each
acceptance check for real before reporting. Commit only when asked. Do not push.
```

To work on a specific phase instead, say "Do Phase N" and read `docs/phases/NN-*.md`.

## 2. State at hand-off
- The program is two parts (docs/program-spec/README.md). This whole repository is Part 1 only; Part 2 is the separate repo `roomscan-recon`.
- Branch `main` at `21ce79a`. Phase hashes are in PROGRESS.md (older hashes such as `93f20d6`/`bd78bcd` no longer exist).
- Done: Phases 0–8 in software (verified on synthetic data). Phase 9 ~10 %; Phases 10–11 partly on Android only; Phase 12 not started. Next: M1 Android stability finish, then M2 Phase 9 openings.
- Full status and the list of things only the user can do: `docs/STATUS.md`.
- Gotchas and working commands: `PROGRESS.md`.

## 3. One-time machine setup (already done on the dev machine; repeat on a new one)
```sh
cd capturekit
git submodule update --init --recursive
./scripts/bootstrap.sh                       # tools/.venv, OpenCV prebuilt, references
cd tools && uv sync --extra dev && cd ..
scripts/build_core_py.sh                     # builds _ckcore (needs cmake, g++, python dev headers)
scripts/make_golden.sh                       # synthetic golden sessions, about 20 min, in build/golden/raw
# Android (optional): install the SDK (cmdline-tools, platform 37, build-tools 37.0.0), then
export ANDROID_HOME=$HOME/Android/Sdk
```
Tools used: cmake 4.x, g++/clang, protoc, uv, Python 3.12 (via uv), Java 21, docker.

## 4. Check the baseline before starting
```sh
cd capturekit
cmake -S core -B build/core && cmake --build build/core -j8 && ctest --test-dir build/core   # 9 tests
cd tools && uv run pytest -q && uv run ruff check . && uv run ruff format --check .           # 21 tests
cd ../android && ./gradlew assembleDebug testDebugUnitTest --console=plain -q                 # exit 0
```
If a test hangs, run it under `ulimit -v 3000000` and `timeout`; see Gotchas in PROGRESS.md.

## 5. Rules that matter most (full text in AGENTS.md)
- Core never reads the system clock; time comes from `t_ns`.
- No bare 4x4 matrices in public APIs; use frame-named types.
- Quaternions on disk are `[w,x,y,z]`; Eigen `coeffs()` is `[x,y,z,w]`.
- Record assumptions as 3–6 line ADRs in `capturekit/docs/decisions/` (next number: 0015; index in README.md).
- End each phase by updating `PROGRESS.md` and `docs/STATUS.md`.
- Final report is at most 25 lines.
- Real device recordings go in `capturekit/bench/golden/` (Git LFS). They replace the synthetic sessions and the Phase 1 and 2 checks must then be re-run on them.

## 6. When the user brings real recordings
Say: "Process the golden recordings in capturekit/bench/golden/". Then:
1. `ckit inspect-raw` each session, filling the device matrix in `docs/program-spec/recording-protocol.md`.
2. `ckit inspect-raw <clock-session> --clock` and record the offsets in `docs/program-spec/clock-notes.md`; apply any offset above 1 ms in the recorder or `device.json`.
3. `ckit import-raw` and `ckit validate` each one; fix any recorder bugs found.
4. Re-run `ckit reprojection-report` on the real bundles. Check ADR 0003 with the checkerboard recordings (ADR 0005): calibrated cx, cy vs platform intrinsics, |Δ| ≈ 0.5 px refutes it.
