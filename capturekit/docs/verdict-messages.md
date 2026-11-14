# Verdict messages

Generated from `tools/ckit/verdict.py` (`ckit verdict-docs`). Do not edit by hand.
ERROR -> rescan. WARN -> proceed with a note. INFO -> details only.

| Code | Level | Severity | Title | What happened | What to do | Next |
|---|---|---|---|---|---|---|
| MISSING_MANIFEST | structural | ERROR | Scan is incomplete | The scan did not finish saving. | Scan the room again. | rescan |
| MANIFEST_SCHEMA | structural | ERROR | Scan data is damaged | The scan's summary file is not readable. | Scan the room again. | rescan |
| UNSUPPORTED_MAJOR | structural | ERROR | App update needed | This scan was made by a newer version of the app. | Update the app, then open the scan again. | rescan |
| UNKNOWN_MINOR | structural | WARN | Newer scan format | This scan uses newer, optional data we do not know yet. | Nothing. Extra data is ignored. | proceed_with_note |
| CAMERA_SCHEMA | structural | ERROR | Camera data is damaged | The camera description is missing or invalid. | Scan the room again. | rescan |
| MISSING_FILE | structural | ERROR | Scan is missing files | Some files of the scan are missing. | Scan the room again. | rescan |
| MCAP_TRUNCATED | structural | ERROR | Scan stopped early | The motion recording ends abruptly. | Scan the room again; keep the app open until it says done. | rescan |
| MCAP_UNREADABLE | structural | ERROR | Scan data is damaged | The motion recording cannot be read. | Scan the room again. | rescan |
| MISSING_CHANNEL | structural | ERROR | Sensor data missing | A required sensor recording is missing (poses, motion or camera info). | Scan again and allow camera and motion access. | rescan |
| SHA256_MISMATCH | integrity | ERROR | Scan data changed | A file does not match its checksum, so it was damaged or edited. | Scan the room again. | rescan |
| SIZE_MISMATCH | integrity | ERROR | Scan data changed | A file has a different size than recorded. | Scan the room again. | rescan |
| UNLISTED_FILE | integrity | WARN | Extra file in scan | The scan contains a file that is not listed. | Nothing. It is ignored. | proceed_with_note |
| NON_MONOTONIC_TIME | semantic | ERROR | Timing error | Timestamps go backwards, so frames cannot be matched to poses. | Scan the room again. | rescan |
| NAN_VALUE | semantic | ERROR | Invalid sensor values | Some sensor readings are not valid numbers. | Scan again; if it repeats, restart the phone. | rescan |
| NON_UNIT_QUATERNION | semantic | ERROR | Invalid camera rotations | Some camera rotations are not valid. | Scan the room again. | rescan |
| MISSING_INTRINSICS | semantic | ERROR | Camera info missing | Camera lens information is missing for the first photos. | Scan the room again. | rescan |
| KEYFRAME_WITHOUT_POSE | semantic | ERROR | Photos without position | Some photos have no tracked camera position. | Scan again, moving slowly while tracking is good. | rescan |
| KEYFRAME_FILE_MISSING | semantic | ERROR | Photos are missing | The scan lists photos that were not saved. | Scan the room again; make sure storage is not full. | rescan |
| COUNT_MISMATCH | semantic | WARN | Counts differ | Summary counts differ slightly from the recording. | Nothing. The recording is used as is. | proceed_with_note |
| KEYFRAME_TRACKING_LIMITED | semantic | WARN | Shaky tracking in places | Some photos were taken while tracking was weak. | Optional: rescan those areas slowly with more light. | proceed_with_note |
| POSE_GAP | semantic | WARN | Tracking was lost briefly | Tracking was interrupted for part of the scan. | Optional: rescan the area where tracking was lost. | proceed_with_note |
| FEW_KEYFRAMES | semantic | WARN | Not many photos | The scan has few usable photos, so the 3D result may have gaps. | Walk the room again, slower, covering all walls. | proceed_with_note |
| NO_AR_GEOMETRY | semantic | INFO | No surface hints | The phone did not report surfaces or feature points. | Nothing needed. | none |
| PARTIAL_BUNDLE | semantic | WARN | Scan was cut short | The scan was interrupted and recovered, so part of the room may be missing. | Scan the missing part again. | proceed_with_note |
| QUALITY_NOT_EVALUATED | quality | INFO | Quality not checked | Quality metrics were not available for this scan. | Nothing needed. | none |
