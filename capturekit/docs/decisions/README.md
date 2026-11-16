# docs/decisions

Architecture Decision Records (ADRs), one file per decision: NNNN-title.md.

## Index
- [0001](0001-jpeg-keyframes-not-video.md) JPEG keyframes + MCAP, not video.mp4
- [0002](0002-synthetic-golden-sessions.md) Synthetic stand-ins for golden sessions
- [0003](0003-intrinsics-pixel-centre.md) Platform intrinsics use the integer pixel-centre convention
- [0004](0004-ar-points-planes-channels.md) Bundle carries AR feature points and plane anchors
- [0005](0005-pixel-centre-check-by-calibration.md) Verify the pixel-centre convention with a checkerboard
- [0006](0006-sharpness-ratio-and-texture-gate.md) Sharpness is a fine/coarse Laplacian ratio, gated by coarse texture
- [0007](0007-thermal-state-mapping.md) Mapping OS thermal signals onto governor levels 0-3
- [0008](0008-shell-encodes-keyframe-jpegs.md) Core selects keyframes, shell encodes the JPEG (request/ack)
- [0009](0009-android-keyframes-from-arcore-cpu-image.md) Android keyframes from the ARCore CPU image; Camera2 stills deferred
- [0010](0010-guidance-coverage-and-free-space-model.md) Guidance coverage on planes, free space by carving, marginal-gain planning
- [0011](0011-guidance-evaluated-with-simulated-walker.md) Guidance evaluated with a simulated walker until real trials exist
- [0012](0012-jni-handle-map-not-raw-pointer.md) JNI handle map instead of raw pointer cast
- [0013](0013-overlay-snapshot-as-non-critical-event.md) Overlay snapshot ships as a non-critical event, not a C API accessor
- [0014](0014-speech-scheduler-in-kotlin-for-now.md) SpeechScheduler lives in Kotlin for now, not in the C++ core
- [0015](0015-sim-walker-hold-level-for-understanding-scenes.md) Sim walker hold_level mode for understanding scenes
- [0017](0017-phase9-object-detector-efficientdet-lite0.md) Phase 9 object detector: EfficientDet-Lite0 (COCO), geometry-only fallback
- [0018](0018-delegated-decisions.md) Open human decisions settled by the planner while the user was away (revisit)
- [0021](0021-face-redaction-detector.md) Face redaction: android.media.FaceDetector now, BlazeFace gated on a licence read
- [0024](0024-opening-detector-v1-parameters.md) OpeningDetector v1 parameters (sim-tuned hypotheses, revisit with real recordings)
