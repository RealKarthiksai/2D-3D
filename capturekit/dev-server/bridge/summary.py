"""Room summary for the bridge API, taken only from roomscan-recon's own output files.

Every number is copied from room.json / quality.json or computed from the floor polygon in
room.json; a missing or malformed value stays None (nothing is guessed). Stdlib only.
"""

from __future__ import annotations

import math

NO_POLYGON = "none"


def _num(v) -> float | int | None:
    """A finite number, else None (bools and strings are not numbers here)."""
    if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v):
        return None
    return v


def _strings(v) -> list[str]:
    return [s for s in v if isinstance(s, str)] if isinstance(v, list) else []


def _points(polygon) -> list[tuple[float, float]] | None:
    """The polygon as (x, y) pairs, or None when it is not a list of >= 3 finite pairs."""
    if not isinstance(polygon, list) or len(polygon) < 3:
        return None
    pts = []
    for p in polygon:
        if not isinstance(p, (list, tuple)) or len(p) < 2:
            return None
        x, y = _num(p[0]), _num(p[1])
        if x is None or y is None:
            return None
        pts.append((float(x), float(y)))
    return pts


def floor_dims_m(polygon, source) -> list[float] | None:
    """[long, short] side of the minimum-area rectangle around the floor polygon, in metres.

    The best rectangle has a side along one polygon edge: for each edge, project the vertices on
    the edge direction and its normal, area = span1 * span2; keep the smallest.
    """
    pts = _points(polygon)
    if pts is None or source == NO_POLYGON:
        return None
    best: tuple[float, float, float] | None = None
    for (x0, y0), (x1, y1) in zip(pts, pts[1:] + pts[:1], strict=True):
        length = math.hypot(x1 - x0, y1 - y0)
        if length == 0:
            continue
        ux, uy = (x1 - x0) / length, (y1 - y0) / length
        along = [x * ux + y * uy for x, y in pts]
        across = [-x * uy + y * ux for x, y in pts]
        s1, s2 = max(along) - min(along), max(across) - min(across)
        if best is None or s1 * s2 < best[0]:
            best = (s1 * s2, s1, s2)
    if best is None:
        return None
    return [round(max(best[1], best[2]), 2), round(min(best[1], best[2]), 2)]


def summarize(room: dict, quality: dict | None, recon_manifest: dict | None) -> dict:
    """Summary for the job JSON. ``room`` is room.json; the other two may be absent."""
    quality = quality if isinstance(quality, dict) else {}

    def pick(key):  # room.json first, quality.json repeats most of these fields
        return room.get(key) if room.get(key) is not None else quality.get(key)

    wall_ids = room.get("wall_ids")
    outputs = None
    if isinstance(recon_manifest, dict) and isinstance(recon_manifest.get("outputs"), dict):
        outputs = {
            name: {"status": e.get("status"), "reason": e.get("reason")}
            for name, e in recon_manifest["outputs"].items()
            if isinstance(e, dict)
        }
    source = pick("floor_polygon_source")
    return {
        "floor_area_m2": _num(pick("floor_area_m2")),
        "ceiling_height_m": _num(pick("ceiling_height_m")),
        "wall_count": len(wall_ids) if isinstance(wall_ids, list) else None,
        "floor_dims_m": floor_dims_m(room.get("floor_polygon"), source),
        "floor_polygon_source": source if isinstance(source, str) else None,
        "scale_source": pick("scale_source") if isinstance(pick("scale_source"), str) else None,
        "scale_confidence": _num(pick("scale_confidence")),
        "warnings": _strings(quality.get("warnings")),
        "notes": _strings(room.get("notes")),
        "outputs": outputs,
    }
