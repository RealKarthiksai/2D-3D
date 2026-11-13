# Scan Bundle v1.0.0

The Scan Bundle is the contract between capture and everything downstream. `ckit validate` is the executable definition of "valid". Conventions: [conventions.md](../../../docs/program-spec/conventions.md). This supersedes the `video.mp4` layout in older docs (ADR 0001).

## Layout
```
<bundle>/
  manifest.json        written LAST; lists every other file with sha256 + size
  camera.json          nominal camera (first intrinsics), image storage orientation, IMU extrinsic
  streams.mcap         MCAP, protobuf channels (below), chunked, uncompressed, summary on finalize
  keyframes/NNNNNN.jpg JPEG keyframes, sensor-native orientation, no EXIF orientation
  guidance_log.json    optional, GuideMind log (Phase 8)
```

## Channels (`messages/capturekit.proto`, schema data = `messages/capturekit.desc` FileDescriptorSet)
| Topic | Message | Required | Rate |
|---|---|---|---|
| `/pose` | `capturekit.v1.Pose` | yes | AR frame rate |
| `/imu/gyro` | `capturekit.v1.ImuSample` | yes | ≥100 Hz typical |
| `/imu/accel` | `capturekit.v1.ImuSample` | yes | ≥100 Hz typical |
| `/intrinsics` | `capturekit.v1.Intrinsics` | yes (≥1 before first keyframe) | on change |
| `/frame_index` | `capturekit.v1.FrameIndex` | yes | per candidate frame |
| `/events` | `capturekit.v1.Event` | yes (may be empty) | sparse |
| `/ar/points` | `capturekit.v1.PointCloud` | optional (ADR 0004) | ≤ frame rate |
| `/ar/planes` | `capturekit.v1.PlaneAnchor` | optional (ADR 0004) | on update |

The MCAP `log_time` and `publish_time` of each message equal its `t_ns`. Within a channel, `t_ns` is strictly increasing.

## Versioning (semver on `manifest.schema_version`)
- **Major** changes break readers. Readers reject an unknown major with `UNSUPPORTED_MAJOR` (ERROR).
- **Minor** changes add fields, channels or optional files. Readers accept an unknown minor with `UNKNOWN_MINOR` (WARN) and ignore what they don't understand.
- **Patch** changes are documentation or validator-tolerance changes only.
- Protobuf field numbers are never reused. New fields are additive and belong to a minor bump.

## Validator (`ckit validate <bundle> [--level structural|integrity|semantic|quality]`)
The levels are cumulative. Output is a JSON report (`{"verdict", "issues":[{"code","severity","level","message","where"}]}`) plus a human summary. Exit code 1 if any ERROR.

| Code | Severity | Level | Meaning |
|---|---|---|---|
| MISSING_MANIFEST | ERROR | structural | no manifest.json |
| MANIFEST_SCHEMA | ERROR | structural | manifest.json is not valid JSON or fails the schema |
| UNSUPPORTED_MAJOR | ERROR | structural | schema_version major is not 1 |
| UNKNOWN_MINOR | WARN | structural | schema_version minor is newer than the validator |
| CAMERA_SCHEMA | ERROR | structural | camera.json missing, unparsable or fails the schema |
| MISSING_FILE | ERROR | structural | a file listed in the manifest does not exist |
| MCAP_TRUNCATED | ERROR | structural | streams.mcap has no valid footer or summary (not finalized) |
| MCAP_UNREADABLE | ERROR | structural | streams.mcap cannot be parsed even by sequential scan |
| MISSING_CHANNEL | ERROR | structural | a required channel is absent |
| SHA256_MISMATCH | ERROR | integrity | file hash differs from the manifest |
| SIZE_MISMATCH | ERROR | integrity | file size differs from the manifest |
| UNLISTED_FILE | WARN | integrity | a file in the bundle is not listed in the manifest |
| NON_MONOTONIC_TIME | ERROR | semantic | t_ns not strictly increasing within a channel, or log_time ≠ t_ns |
| NAN_VALUE | ERROR | semantic | NaN or Inf in a pose, IMU sample or intrinsics |
| NON_UNIT_QUATERNION | ERROR | semantic | abs(norm(q) − 1) > 1e-3 |
| MISSING_INTRINSICS | ERROR | semantic | no /intrinsics at or before the first keyframe |
| KEYFRAME_WITHOUT_POSE | ERROR | semantic | no pose within ±50 ms bracketing the keyframe (interpolable) |
| KEYFRAME_FILE_MISSING | ERROR | semantic | frame_index keyframe references a file that is not in the bundle |
| COUNT_MISMATCH | WARN | semantic | manifest counts differ from the MCAP |
| KEYFRAME_TRACKING_LIMITED | WARN | semantic | keyframe captured while tracking is not NORMAL |
| POSE_GAP | WARN | semantic | gap > 500 ms in /pose |
| FEW_KEYFRAMES | WARN | semantic | fewer than 10 keyframes |
| NO_AR_GEOMETRY | INFO | semantic | /ar/points and /ar/planes both absent |
| PARTIAL_BUNDLE | WARN | semantic | completion.status is partial or recovered |
| QUALITY_NOT_EVALUATED | INFO | quality | quality level is a stub until Phase 4 metrics are present |

Verdict: any ERROR → `FAIL`, else any WARN → `WARN`, else `PASS`.

Note: several plane anchors updated in one AR frame share a timestamp. Writers add +1 ns to each later anchor in the same frame, which keeps `t_ns` strictly increasing on `/ar/planes`.
