# Part 1 (Intelligent Room Capture, `capturekit/`): status and what is needed from you

The program now has two parts (see [program-spec/README.md](program-spec/README.md)). This file tracks Part 1 only; Part 2 (backend reconstruction) is not started.

See also [NEXT_SESSION.md](NEXT_SESSION.md) for the copy-paste start kit for a new conversation.

Updated 2026-11-14 · branch `part1-phases` · last Phase 2 commit `bd78bcd`

## 1. Overall progress

Three of the thirteen phases (0–12) are done in software. Phases 9–11 were added when the program became two parts, to cover room understanding (furniture, openings, missing areas), the voice assistant, and the overlay/review/privacy UI. Roughly **15 % of Part 1** is built. Every phase has acceptance items that need real phones, a Mac, or people (section 3). Those items stay open even after the software is written.

| Phase | Software on this machine | Acceptance status |
|---|---|---|
| 0 Conventions + raw recording | **Done**: conventions.md, recording protocol, clock-notes template, Android RawRecorder + clock check (APK builds), iOS RawRecorder + SampleApp sources, `ckit inspect-raw`, `ckit synth` | **Blocked on you**: 8 real golden sessions, per-device clock offsets, iOS build |
| 1 Scan Bundle spec + serialization | **Done**: proto + JSON Schemas + SPEC.md, C++ MCAP writer/reader, codec, sha256, manifest/camera finalize, nanobind `_ckcore`, `ckit validate`, `ckit import-raw` | **Met on synthetic data** (8/8 golden stand-ins validate with 0 ERROR; 12 broken fixtures give their codes; round trip and truncation fuzz pass). Must be re-run on real recordings |
| 2 Geometry core | **Done** (`bd78bcd`): frame-named poses, ARKit/ARCore/COLMAP/OpenGL adapters, rotate/scale/crop, interpolation, `ckit export-colmap`, `ckit reprojection-report` | **Met on synthetic data** (28 C++ / 28 Py tests; mean reprojection 0.59–1.01 px). ADR 0003 check needs your checkerboard recordings (ADR 0005) |
| 3 Replay harness | Not started; can be done here | Can be met here |
| 4 Quality + keyframe selector | Not started; can be done here | Can be met here; real-data tuning needs recordings |
| 5 Thermal/battery governor | Not started; C++ core and tests can be done here | 10-minute soak test needs phones |
| 6 Native capture pipelines | Not started; C API/session can be done here, Android compiles here, iOS cannot | Needs 2 iOS + 3 Android devices |
| 7 Finalize + upload | Not started; finalize, dev-server verifier and docker failure tests can be done here | On-device failure cases need phones |
| 8 Guidance core (GuideMind) | Not started; can be done here with synthetic rooms | Manual coverage labels on real sessions need you |
| 9 Room understanding | Not started; geometry parts + synthetic tests can be done here | Detector choice/latency per device tier, missing-area labels need you |
| 10 Voice assistant | Not started; scheduler, intents, Android TTS/STT can be done here | Latency on phones, user tuning of speech budget |
| 11 Overlay, review, consent, privacy | Not started; core overlay model, Android UI, redaction pipeline can be done here | iOS UI build, redaction set labelling, consent text owner |
| 12 Integration + field trials | Not started; `ckit e2e`/`trial-report` can be done here | Trials need participants and your target thresholds |

**Verified on this machine:** C++ core `ctest` 28/28 · Python `pytest` 28/28 + ruff clean · Android `assembleDebug testDebugUnitTest` passes (with the NDK-built core).
**Not verified:** anything iOS/Swift (no macOS here), and GitHub CI (branch not pushed).

## 2. What I set up on this machine
- Android SDK at `~/Android/Sdk` (cmdline-tools, platform 37, build-tools, CMake 4.1.2, NDK through Gradle). Installing it meant accepting the Android SDK licenses. Delete the folder if you don't want it.
- Synthetic golden sessions: `capturekit/build/golden/raw/G1..G8_*` (raw), `build/golden/bundles/` (imported), `build/golden/plots/` (trajectories). Regenerate with `capturekit/scripts/make_golden.sh`.

## 3. Tasks I cannot do here (need you, hardware or decisions)

### 3.1 Hardware and accounts to get
| # | Item | Needed for | Notes |
|---|---|---|---|
| H1 | ≥2 ARKit iPhones (iOS 17+), different tiers (e.g. one recent Pro, one older non-Pro) | Phases 0, 2, 5, 6, 7, 9–12 | Prefer one without LiDAR, since the product is camera-only |
| H2 | ≥3 ARCore-certified Android phones (low, mid, high tier) | Phases 0, 2, 5, 6, 7, 9–12 | Check the [ARCore supported devices list](https://developers.google.com/ar/devices); include one with Depth API |
| H3 | A Mac with Xcode 16.3+ and `xcodegen` (`brew install xcodegen`) | iOS build/test, SampleApp | Nothing Swift has been compiled yet |
| H4 | Apple Developer account (free is fine for personal devices) | Installing the SampleApp on iPhones | |
| H5 | Rooms matching G1–G8 (small, large, corridor, cluttered, dim, blank-wall) | Golden recordings, trials | See `docs/program-spec/recording-protocol.md` |

### 3.2 Phase 0: recordings (unblocks the most)
1. **Android:** install the APK from `capturekit/android/sample-app/build/outputs/apk/debug/sample-app-debug.apk` (`adb install -r …`). Open **Raw recorder**, press Start, scan, press Stop.
   Pull the sessions with `adb pull /sdcard/Android/data/dev.capturekit.sample/files/sessions`.
2. **iOS:** `cd capturekit/ios/SampleApp && xcodegen && open CaptureKitSample.xcodeproj`, then set your signing team and run it on the device. Sessions are in Files → On My iPhone → CaptureKitSample → sessions.
3. Record all 8 scenarios (G1–G8) per device, following `recording-protocol.md`. Name each folder `G<n>_<device-slug>_<yyyymmdd>_<nn>`.
4. **Clock check:** on each device, open **Clock check**, hold the phone still for 10 s, move it for 10 s, then stop. Run `tools/.venv/bin/ckit inspect-raw <clock-session> --clock`.
5. Put the sessions in `capturekit/bench/golden/` (Git LFS; never plain git) and tell me. I will run inspect-raw, import-raw and validate on them, fill in `clock-notes.md` and the device matrix, and fix any recorder bugs they reveal.
6. Report anything that went wrong: crashes, empty files, black JPEGs, rotated images.

### 3.3 Later device and field work (by phase)
| Phase | What you run | What you give back |
|---|---|---|
| 2 | Per phone: one ~20 s recording of a printed checkerboard (9×6 inner corners, note the square size), moved slowly through different angles | — (I compare checkerboard calibration with platform intrinsics: confirms or refutes ADR 0003, see ADR 0005) |
| 5 | 10-minute soak test per device (protocol written in Phase 5) | Filled results template |
| 6 | 5-minute scan, forced kill, phone call, backgrounding, covered lens, low light on 2 iOS + 3 Android | Session folders + notes; device list for `docs/reports/devices.md` |
| 6 | Android: try whether ARCore frames are high enough resolution or a Camera2 still is needed (test build provided then) | Which devices, what resolution |
| 7 | Upload failure matrix on phones: airplane mode, app kill, reboot, disk full | Pass/fail per case |
| 8 | Hand-label coverage on 2–3 real golden sessions (simple tool provided then) | Label files |
| 9 | Hand-label missing areas (gaps) on 2–3 real sessions | Label files |
| 10 | Try voice commands and speech rate on each phone | Latency + notes |
| 12 | Guided vs unguided trials with real participants (protocol and consent placeholder provided) | Session bundles + notes |

### 3.4 Decisions only you can make
1. **Phase 12 target thresholds** (registered-image ratio, mean reprojection error, coverage %, crash-free %, battery per scan). These must be agreed before trials.
2. **Device support matrix:** which phones and OS versions are officially supported.
3. **Privacy and consent:** Part 1 builds the consent screens and on-device redaction (Phase 11); the consent text and the server-side retention policy (Part 2) need an owner. Tell me who.
4. **Production endpoints and keys:** tus upload URL, Sentry DSN. Never commit them; they go in `config/*.local.json` or CI secrets.
5. **Repo housekeeping:**
   - The superseded nine-project docs are now in `docs/program-spec/legacy-nine-projects/` (still untracked). Commit them as archive, or delete?
   - Your staged deletion of `reconstruction/` and the edits to `README.md` were left as they were and not committed.
   - The branch `part1-phases` has not been pushed. Push it and open a PR so CI, including the macOS job, can run.
6. Whether **synthetic sessions may stand in** for some device acceptance items in CI (ADR 0002 currently says no).

## 4. Commands that work today
```sh
cd capturekit
cmake -S core -B build/core && cmake --build build/core -j8 && ctest --test-dir build/core
scripts/build_core_py.sh                                  # builds _ckcore for ckit
cd tools && uv run pytest -q && uv run ruff check . && cd ..
tools/.venv/bin/ckit synth G4 /tmp/g4 --duration 20       # synthetic raw session
tools/.venv/bin/ckit inspect-raw <raw> --plot out.png
tools/.venv/bin/ckit import-raw <raw> <bundle> && tools/.venv/bin/ckit validate <bundle>
cd android && ANDROID_HOME=~/Android/Sdk ./gradlew assembleDebug testDebugUnitTest
```

## 5. Next step on my side
Phase 3 (replay harness) needs no hardware.
