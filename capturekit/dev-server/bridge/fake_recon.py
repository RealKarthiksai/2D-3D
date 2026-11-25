"""TEST-ONLY stand-in for roomscan-recon's spatial step (used by tests and smoke.sh).

Writes a room.json / quality.json into ``<out>/spatial_map/`` (the given fixture files, or a
built-in 4 x 3 m room) so the bridge can be exercised without a GPU or roomscan-recon. It
produces no real reconstruction and must never be configured on a real laptop.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

ROOM = {
    "floor_polygon": [[0, 0], [4, 0], [4, 3], [0, 3]],
    "floor_polygon_source": "wall_intersections",
    "floor_area_m2": 12.0,
    "ceiling_height_m": 2.5,
    "wall_ids": [1, 2, 3, 4],
    "scale_source": "metric_priors",
    "scale_confidence": 0.8,
    "notes": ["fake_recon: test data"],
}
QUALITY = {"warnings": ["fake_recon: test data"]}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--out", required=True, type=Path, help="run dir; files go to <out>/spatial_map"
    )
    ap.add_argument("--src", type=Path, help="extracted bundle; must contain manifest.json")
    ap.add_argument("--room", type=Path, help="room.json to write instead of the built-in room")
    ap.add_argument("--quality", type=Path, help="quality.json to write instead of the built-in")
    ap.add_argument("--fail", type=int, default=0, help="print an error line and exit with CODE")
    ap.add_argument("--say", help="print this line first (may be non-ASCII)")
    ap.add_argument("--sleep", type=float, default=0.0, help="seconds to wait first (timeouts)")
    a = ap.parse_args(argv)
    time.sleep(a.sleep)
    if a.say:
        print(a.say, flush=True)
    if a.src is not None and not (a.src / "manifest.json").is_file():
        print(f"fake_recon: no manifest.json in {a.src}")
        return 4
    if a.fail:
        print("progress line", flush=True)
        print("fake_recon: simulated failure: unsupported bundle format")
        return a.fail
    spatial_map = a.out / "spatial_map"
    spatial_map.mkdir(parents=True, exist_ok=True)
    room = a.room.read_text() if a.room else json.dumps(ROOM)
    quality = a.quality.read_text() if a.quality else json.dumps(QUALITY)
    (spatial_map / "room.json").write_text(room)
    (spatial_map / "quality.json").write_text(quality)
    return 0


if __name__ == "__main__":
    sys.exit(main())
