# Part 1 (Intelligent Room Capture, `capturekit/`): status and what is needed from you

The program now has two parts (see [program-spec/README.md](program-spec/README.md)). This repository is Part 1 only; Part 2 (backend reconstruction) is built separately in `roomscan-recon`.

See also [NEXT_SESSION.md](NEXT_SESSION.md) for the copy-paste start kit for a new conversation.

Updated 2026-11-17 (end of unattended run) · branch `main` · last commit `b3d39f0` + a large UNCOMMITTED tree (see PROGRESS.md; history was rewritten, older hashes no longer exist) · tests only you can run: [HUMAN_TESTS.md](HUMAN_TESTS.md)

## 0. What the default build shows (issue #6, ADR 0032)

Rule: ON only if it PASSed on the phone ([DEVICE_TEST_LOG.md](DEVICE_TEST_LOG.md)); everything else is OFF behind `FeatureFlags` (debug builds: `adb shell setprop debug.capturekit.<switch> 1`, then force-stop the app). Device acceptance of this build (walked scan on CPH2381 showing only kept features): **pending** (no phone attached when it was made).

| Feature | Default | Evidence | To switch back on (pass first, log it, then change the default) |
|---|---|---|---|
| Consent screen, withdraw | ON | DEVICE_TEST_LOG 2026-11-17 (H13) | — |
| Pause/Resume, Home/Back mid-scan, force-kill recovery | ON | H9, H5, H4 rows | — |
| My scans; Review verdict + buttons, bundle validation | ON | H29 row; first walked scan (validate PASS) | — |
| HUD pill, REC timer, reticle, debug line | ON | H9 row | — |
| Safety/tracking prompts (slow down, hold still, relocalize, more light, aim at texture, recalibrate), text + TTS | ON | H8 dark-room row ("More light") | — |
| Navigation, Progress and Finish prompts + their haptics | OFF `guidance` | walked scan FAIL (H9/H22) | H9, H22 |
| Live room card (mini-map, arrow, checklist, %) | OFF `guidance` (removed for good by issue #8) | walked scan FAIL | H9, H11 |
| Weak-wall review sheet; Review room drawing + walls | OFF `guidance` (empty-scan notice stays, B5) | walked scan FAIL (8 walls for 4) | H10, H9 |
| Detector, camera boxes chip, object labels, review "Found" | OFF `detector` | never device-tested | H18, H19, H33 |
| Voice commands (mic chip, VoiceController, StateAnswers) | OFF `voice_commands` (no mic permission asked) | never device-tested | H12, H31 |
| Recalibration card | OFF `recalibrate_card` (core keyframe pause stays) | cover/uncover check not run | H36 |
| Face redaction | ON, **unverified** | never device-tested | H14 (keep ON: off ships unblurred faces) |
| Upload/tus, Sentry, research/trial chip | field flavour only | standard build has none | H16, H17, H30, H32 |
| Raw recorder, Clock check | ON | needed for golden recordings | H1, H2 |
| iOS | BLOCKED: never compiled | no Mac | H27 |

## 1. Overall progress

Nine of the thirteen phases (0–8, minus device acceptance) are done in software; Phase 9 is about 50 % done in software (uncommitted: detections in the bundle, shell, gaps, detector worker; engine wiring and steering pending) and Phases 10–11 are mostly built on Android only (voice, consent, upload UI added uncommitted). Phases 9–11 were added when the program became two parts, to cover room understanding (furniture, openings, missing areas), the voice assistant, and the overlay/review/privacy UI. Ordered plan of every open item: `.claude/agent-memory/planner/briefs/remaining-work-audit.md`. Every phase has acceptance items that need real phones, a Mac, or people (section 3). Those items stay open even after the software is written.

| Phase | Software on this machine | Acceptance status |
|---|---|---|
| 0 Conventions + raw recording | **Done**: conventions.md, recording protocol, clock-notes template, Android RawRecorder + clock check (APK builds), iOS RawRecorder + SampleApp sources, `ckit inspect-raw`, `ckit synth` | **Blocked on you**: 8 real golden sessions, per-device clock offsets, iOS build |
| 1 Scan Bundle spec + serialization | **Done**: proto + JSON Schemas + SPEC.md, C++ MCAP writer/reader, codec, sha256, manifest/camera finalize, nanobind `_ckcore`, `ckit validate`, `ckit import-raw` | **Met on synthetic data** (8/8 golden stand-ins validate with 0 ERROR; 12 broken fixtures give their codes; round trip and truncation fuzz pass). Must be re-run on real recordings |
| 2 Geometry core | **Done** (`2e55389`): frame-named poses, ARKit/ARCore/COLMAP/OpenGL adapters, rotate/scale/crop, interpolation, `ckit export-colmap`, `ckit reprojection-report` | **Met on synthetic data** (28 C++ / 28 Py tests; mean reprojection 0.59–1.01 px). ADR 0003 check needs your checkerboard recordings (ADR 0005) |
| 3 Replay harness | **Done** (`d0c03ec`): ReplaySink, `ckit replay` with faults, golden replay CI | **Met on synthetic data** |
| 4 Quality + keyframe selector | **Done** (`8b00160`): metrics, selector, quality sink, quality-report | **Met on synthetic data** (1.43 ms/frame); thresholds need tuning on real recordings |
| 5 Thermal/battery governor | **Done** (`ae5ede6`): ladder, hysteresis, governor sink, Android + iOS adapters | Timeline and replay tests met; 10-minute soak needs phones (docs/soak-test.md) |
| 6 Native capture pipelines | **Done** (`09cfcba`): C API, Session, recovery, JNI + Android capture, iOS source | Core met incl. SIGKILL recovery and ASAN/UBSAN/TSAN; devices needed (docs/capture-test-protocol.md); iOS uncompiled |
| 7 Finalize + upload | **Done** (`fe73262`): on-device validator, verdict table, verifier, tus clients (Android compiled, iOS source), docker failure matrix | Server matrix met (8/8); phone cases in docs/upload-test-protocol.md |
| 8 Guidance core (GuideMind) | **Done** (`606e618`): LiveMap, coverage, NavGrid, planner, instructions, simulator, guide sink; OverlayModel event (`5fd4887`, ADR 0013) | Manual coverage labels on real sessions need you |
| 9 Room understanding | **Partial** (`0a4a67d`, `36a676b`, `e07dc94` + uncommitted): SurfaceClassifier, opening detector, 3D object lifter, overlay v2 (ADR 0022); uncommitted: detections channel in the bundle (schema 1.1.0, ADR 0016) and C API, RoomShell, gap finder, landmarks, virtual coverage grids, DetectorWorker (flag off). Not done: engine on_detections (U5-C), gap steering (U10-C), outputs channels 1.3.0 (U11-CT), debug flags and UI objects (U11-A) | Detector choice/latency per device tier, missing-area labels need you |
| 10 Voice assistant | **Partial, Android only**: SpeechScheduler (`ac76a25`, ADR 0014), TTS, VoiceIntent, VoiceStats, StatusPill (`c3e6d51`); uncommitted: VoiceController push-to-talk (API 31+), repeatLast, StateAnswers (ADR 0019). No core scheduler | Latency on phones, user tuning of speech budget |
| 11 Overlay, review, consent, privacy | **Partial**: core overlay, Android HUD, ReviewActivity, Redactor (ADR 0021), ReviewSheet, RoomView, flavours (committed); uncommitted: ConsentStore/ConsentActivity + manifest consent (schema 1.2.0, ADR 0020), My scans + upload states, Sentry (field only), ResearchLog (field). Consent text is a placeholder | iOS UI build, redaction set labelling, consent text owner |
| 12 Integration + field trials | `ckit e2e`, `trial-report`, frozen-1.0.0 example dir (`9f4ad14`); trials not started | Trials need participants and your target thresholds |

**Verified on this machine (2026-11-17, uncommitted tree on b3d39f0):** C++ core `ctest` 163/163 (WSL, clean build) · WSL `pytest` 126 passed, 8 skipped · ruff check and format clean (2 files reformatted after the run) · replay_golden 0 differences · frozen-1.0.0 sha256 + validate PASS · Android `:capturekit:testDebugUnitTest` 184/184 and `assembleStandardDebug`/`assembleFieldDebug` OK. Docker smoke not run (docker down).
**Not verified:** anything iOS/Swift (no macOS here); CI runs on `main` but the macOS job has never been seen green.

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
   - Done: `reconstruction/` removed (`fab4a0d`); the legacy nine-project docs are no longer in the tree; work is on `main` (pushed). Check the GitHub Actions page for the macOS job.
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
Unattended queue stopped by the user after B1-B6 plus U7-A, U8-A, U8-C, U9-C (code only, uncommitted, reviewed). Remaining, in order: U5-C, U7-C (ADR 0023), U10-C, U11-CT, U11-A, a real U12-D. iOS blocked. Detail: `.claude/agent-memory/planner/briefs/unattended-queue.md` ("Status at stop"). Everything that needs a phone or a decision is in [HUMAN_TESTS.md](HUMAN_TESTS.md) (H1-H35).



## 6. Gate 0: real-device track (from the Phase 8-12 research plan; start now, needs you)
Provisional answers while the user was away: see capturekit/docs/decisions/0018-delegated-decisions.md (revisit). Detector: ADR 0017; face blur: ADR 0021.
Phase 8 thresholds were tuned on synthetic rooms and a simulated person, so they are hypotheses until real data exists:
1. A macOS CI runner (or a Mac) to compile iOS: clears the biggest unknown (CaptureSession, ThermalAdapter, TusUploader never compiled).
2. Run the Android capture screen and upload worker on two physical phones.
3. Record the checkerboard sessions (closes ADR 0003) and >= 10 real rooms on 2-3 devices with the raw recorders; I replay them through
   quality, keyframing, governor and guide, then retune (the keyframe selector lost to fixed spacing on some synthetic sessions).
4. Send 3-5 real bundles to Part 2 and correlate guide coverage with reconstruction quality: this turns the sufficiency rule
   (3 views, 15 deg, 75 deg incidence) from a guess into evidence.
5. Install Polycam/Scaniverse and note what instructions, if any, they give (the plan found none by voice; confirm).
Open questions to answer before Phase 9: does the bundle ever carry depth (LiDAR / ARCore Depth)? which phones are in scope (LiDAR iPhones only,
or any iPhone plus broad Android)? which voice locales (English-India, Hindi, Telugu)? who owns legal review of consent and retention?
