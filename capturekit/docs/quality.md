# Quality metrics and keyframe selection (Phase 4)

Core: `core/include/capturekit/quality/quality.hpp`. Sink: `quality` (`ckit replay --sink quality`).
Config: `QualityConfig` (version `q1`), flat JSON overrides, `hash()` recorded in the manifest
(`capture_settings.quality_config_hash`) and in a `quality.QUALITY_CONFIG` event.

## Per-frame metrics (luma, downscaled to ~320 px wide)
sharpness (Laplacian variance), texture (Laplacian variance after a further 4x downscale), sharpness_norm
(ADR 0006), gyro blur risk = mean |w| over the exposure window x exposure x fx (px), clip_low (<=5),
clip_high (>=250), FAST-9 corners and 4x4-grid spread, tracking state.

## Score
| Reason | Hard reject | Soft penalty |
|---|---|---|
| TRACKING_LOST | state NOT_AVAILABLE | — |
| TRACKING_LIMITED | — | x0.6 |
| MOTION_BLUR | > 4.0 px | > 1.5 px, linear to x0.5 |
| BLUR / SOFT_FOCUS | textured and norm < 0.35 | norm < 0.6 |
| OVER/UNDEREXPOSED | clipped >= 40 % | >= 10 % |
| LOW_TEXTURE, POOR_SPREAD | never | x0.5 / x(0.5+spread) |
| RECALIBRATING | session only: tracking recovery active (ADR 0031), keyframe capture paused | — |

## Keyframe selector
quality gate -> min gap (200 ms after last keyframe) -> novelty (translation / scene depth >= 0.1,
depth = median sparse-point depth or 2 m default; or rotation >= 10 deg) -> 300 ms candidate window ->
best quality frame becomes the keyframe. Every frame gets one decision with reason codes
(NOT_NOVEL, MIN_GAP, NOT_BEST plus metric reasons) written to /frame_index.

## Performance (desktop proxy)
`QualityPerf.DesktopProxyTiming`: 1.43 ms per 1920x1440 frame (Release, x86-64 dev machine, one
thread; target <= 3 ms, strict with `CK_PERF_STRICT=1`). `QualityPerf.NoHeapAllocationPerFrame` proves
zero allocations per frame after the first frame at a resolution. Device numbers: Phase 6 (needs phones).

## Tools
`ckit import-raw RAW OUT --select-keyframes [--config cfg.json]` keeps only selected keyframes.
`ckit quality-report BUNDLE... [--json out] [--no-sfm]` adds pycolmap registration rate of the selection.
Reports for the synthetic golden sessions: `docs/reports/quality_synth.json`.
