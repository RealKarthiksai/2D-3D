"""`ckit validate`: the executable definition of a valid Scan Bundle (spec/scanbundle/SPEC.md)."""

from __future__ import annotations

import bisect
import hashlib
import json
import math
from dataclasses import asdict, dataclass, field
from pathlib import Path

import jsonschema

from ckit.bundle import SPEC_DIR, SUPPORTED_MAJOR, SUPPORTED_MINOR
from ckit.bundle.streams import REQUIRED_TOPICS, read_streams

LEVELS = ["structural", "integrity", "semantic", "quality"]
KEYFRAME_POSE_TOL_NS = 50_000_000
POSE_GAP_WARN_NS = 500_000_000
QUAT_TOL = 1e-3
MIN_KEYFRAMES = 10


@dataclass
class Issue:
    code: str
    severity: str  # ERROR | WARN | INFO
    level: str
    message: str
    where: str = ""


@dataclass
class Report:
    bundle: str
    level: str
    issues: list[Issue] = field(default_factory=list)

    def add(self, code, severity, level, message, where="") -> None:
        self.issues.append(Issue(code, severity, level, message, where))

    @property
    def verdict(self) -> str:
        sev = {i.severity for i in self.issues}
        return "FAIL" if "ERROR" in sev else "WARN" if "WARN" in sev else "PASS"

    def codes(self, severity: str | None = None) -> set[str]:
        return {i.code for i in self.issues if severity is None or i.severity == severity}

    def to_json(self) -> dict:
        return {
            "bundle": self.bundle,
            "level": self.level,
            "verdict": self.verdict,
            "issues": [asdict(i) for i in self.issues],
        }

    def summary(self) -> str:
        n = {s: sum(1 for i in self.issues if i.severity == s) for s in ("ERROR", "WARN", "INFO")}
        lines = [
            f"{self.verdict}  {self.bundle}  (errors={n['ERROR']} warnings={n['WARN']} "
            f"info={n['INFO']}, level={self.level})"
        ]
        lines += [
            f"  {i.severity:5} {i.code}: {i.message}" + (f" [{i.where}]" if i.where else "")
            for i in self.issues
        ]
        return "\n".join(lines)


def _schema(name: str) -> dict:
    return json.loads((SPEC_DIR / "schema" / name).read_text())


def _sha256(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def _finite(*vals: float) -> bool:
    return all(math.isfinite(v) for v in vals)


def validate(bundle: Path, level: str = "semantic") -> Report:
    bundle = Path(bundle)
    upto = LEVELS.index(level)
    rep = Report(str(bundle), level)
    S = "structural"

    # ---- structural -------------------------------------------------------------------
    mpath = bundle / "manifest.json"
    manifest = None
    if not mpath.exists():
        rep.add("MISSING_MANIFEST", "ERROR", S, "manifest.json not found")
    else:
        try:
            manifest = json.loads(mpath.read_text())
            jsonschema.validate(manifest, _schema("manifest.schema.json"))
        except (json.JSONDecodeError, jsonschema.ValidationError) as e:
            msg = e.message if isinstance(e, jsonschema.ValidationError) else str(e)
            rep.add("MANIFEST_SCHEMA", "ERROR", S, msg[:300])
            manifest = manifest if isinstance(manifest, dict) else None
    if manifest and isinstance(manifest.get("schema_version"), str):
        try:
            major, minor, _ = (int(x) for x in manifest["schema_version"].split("."))
            if major != SUPPORTED_MAJOR:
                rep.add("UNSUPPORTED_MAJOR", "ERROR", S, f"schema_version {major}.x not supported")
                return rep
            if minor > SUPPORTED_MINOR:
                rep.add("UNKNOWN_MINOR", "WARN", S, f"schema_version minor {minor} is newer")
        except ValueError:
            pass
    cpath = bundle / "camera.json"
    try:
        jsonschema.validate(json.loads(cpath.read_text()), _schema("camera.schema.json"))
    except FileNotFoundError:
        rep.add("CAMERA_SCHEMA", "ERROR", S, "camera.json not found")
    except (json.JSONDecodeError, jsonschema.ValidationError) as e:
        msg = e.message if isinstance(e, jsonschema.ValidationError) else str(e)
        rep.add("CAMERA_SCHEMA", "ERROR", S, msg[:300])
    files = manifest.get("files", []) if manifest else []
    files = [f for f in files if isinstance(f, dict) and "path" in f]
    for f in files:
        if not (bundle / f["path"]).is_file():
            rep.add("MISSING_FILE", "ERROR", S, "listed file missing", f["path"])
    spath = bundle / "streams.mcap"
    streams = None
    if spath.exists():
        streams = read_streams(spath)
        if not streams.readable:
            rep.add("MCAP_UNREADABLE", "ERROR", S, "; ".join(streams.problems)[:300])
        elif not streams.finalized:
            rep.add("MCAP_TRUNCATED", "ERROR", S, "no valid MCAP footer/summary (not finalized)")
        if streams.readable:
            for t in REQUIRED_TOPICS:
                if t not in streams.topics_present:
                    rep.add("MISSING_CHANNEL", "ERROR", S, "required channel absent", t)
    elif not any(i.code == "MISSING_FILE" for i in rep.issues):
        rep.add("MISSING_FILE", "ERROR", S, "streams.mcap missing", "streams.mcap")

    # ---- integrity --------------------------------------------------------------------
    if upto >= 1 and manifest:
        listed = set()
        for f in files:
            p = bundle / f["path"]
            listed.add(f["path"])
            if not p.is_file():
                continue
            if "size" in f and p.stat().st_size != f["size"]:
                rep.add(
                    "SIZE_MISMATCH",
                    "ERROR",
                    "integrity",
                    f"size {p.stat().st_size} != {f['size']}",
                    f["path"],
                )
            if "sha256" in f and _sha256(p) != f["sha256"]:
                rep.add("SHA256_MISMATCH", "ERROR", "integrity", "sha256 differs", f["path"])
        for p in sorted(bundle.rglob("*")):
            rel = p.relative_to(bundle).as_posix()
            if p.is_file() and rel != "manifest.json" and rel not in listed:
                rep.add("UNLISTED_FILE", "WARN", "integrity", "file not in manifest", rel)

    # ---- semantic ---------------------------------------------------------------------
    if upto >= 2 and streams and streams.readable:
        _semantic(rep, bundle, manifest, streams)

    # ---- quality (stub until Phase 4 metrics exist) ------------------------------------
    if upto >= 3:
        frames = streams.get("/frame_index") if streams else []
        if not any(getattr(f, "quality", 0) for f in frames):
            rep.add("QUALITY_NOT_EVALUATED", "INFO", "quality", "no per-frame quality metrics")
    return rep


def _semantic(rep: Report, bundle: Path, manifest: dict | None, st) -> None:
    M = "semantic"
    for topic, times in st.log_times.items():
        msgs = st.get(topic)
        bad = sum(1 for a, b in zip(times, times[1:], strict=False) if b <= a)
        bad += sum(1 for m, lt in zip(msgs, times, strict=True) if m.t_ns != lt)
        if bad:
            rep.add("NON_MONOTONIC_TIME", "ERROR", M, f"{bad} out-of-order/mismatched t_ns", topic)
    poses = st.get("/pose")
    nan = [p.t_ns for p in poses if not _finite(p.tx, p.ty, p.tz, p.qw, p.qx, p.qy, p.qz)]
    if nan:
        rep.add("NAN_VALUE", "ERROR", M, f"{len(nan)} poses with NaN/Inf", f"/pose t={nan[0]}")
    for topic in ("/imu/gyro", "/imu/accel"):
        n = sum(1 for s in st.get(topic) if not _finite(s.x, s.y, s.z))
        if n:
            rep.add("NAN_VALUE", "ERROR", M, f"{n} samples with NaN/Inf", topic)
    nonunit = [
        p.t_ns
        for p in poses
        if _finite(p.qw, p.qx, p.qy, p.qz)
        and abs(math.sqrt(p.qw**2 + p.qx**2 + p.qy**2 + p.qz**2) - 1) > QUAT_TOL
    ]
    if nonunit:
        rep.add(
            "NON_UNIT_QUATERNION",
            "ERROR",
            M,
            f"{len(nonunit)} non-unit quaternions",
            f"/pose t={nonunit[0]}",
        )
    intr = st.get("/intrinsics")
    if any(not _finite(k.fx, k.fy, k.cx, k.cy) or k.fx <= 0 for k in intr):
        rep.add("NAN_VALUE", "ERROR", M, "invalid intrinsics", "/intrinsics")
    frames = st.get("/frame_index")
    kfs = [f for f in frames if f.keyframe]
    if kfs and (not intr or intr[0].t_ns > kfs[0].t_ns):
        rep.add("MISSING_INTRINSICS", "ERROR", M, "no intrinsics at or before first keyframe")
    pt = [p.t_ns for p in poses]
    no_pose, missing, limited = [], [], 0
    for f in kfs:
        i = bisect.bisect_left(pt, f.t_ns)
        before = pt[i - 1] if i > 0 else None
        at_or_after = pt[i] if i < len(pt) else None
        exact = at_or_after == f.t_ns
        ok = exact or (
            before is not None
            and at_or_after is not None
            and f.t_ns - before <= KEYFRAME_POSE_TOL_NS
            and at_or_after - f.t_ns <= KEYFRAME_POSE_TOL_NS
        )
        if not ok:
            no_pose.append(f.t_ns)
        if not f.file or not (bundle / f.file).is_file():
            missing.append(f.file or f"frame_id={f.frame_id}")
        if f.tracking_state not in (0, 1):
            limited += 1
    if no_pose:
        rep.add(
            "KEYFRAME_WITHOUT_POSE",
            "ERROR",
            M,
            f"{len(no_pose)} keyframes lack bracketing poses",
            f"t={no_pose[0]}",
        )
    if missing:
        rep.add(
            "KEYFRAME_FILE_MISSING",
            "ERROR",
            M,
            f"{len(missing)} keyframe files missing",
            missing[0],
        )
    if limited:
        rep.add(
            "KEYFRAME_TRACKING_LIMITED", "WARN", M, f"{limited} keyframes while tracking not NORMAL"
        )
    gaps = [b - a for a, b in zip(pt, pt[1:], strict=False) if b - a > POSE_GAP_WARN_NS]
    if gaps:
        rep.add("POSE_GAP", "WARN", M, f"{len(gaps)} gaps > 500 ms (max {max(gaps) / 1e6:.0f} ms)")
    if len(kfs) < MIN_KEYFRAMES:
        rep.add("FEW_KEYFRAMES", "WARN", M, f"only {len(kfs)} keyframes")
    if not st.get("/ar/points") and not st.get("/ar/planes"):
        rep.add("NO_AR_GEOMETRY", "INFO", M, "no /ar/points or /ar/planes")
    if manifest and isinstance(manifest.get("counts"), dict):
        c = manifest["counts"]
        actual = {
            "poses": len(poses),
            "imu_gyro": len(st.get("/imu/gyro")),
            "imu_accel": len(st.get("/imu/accel")),
            "intrinsics": len(intr),
            "frames": len(frames),
            "keyframes": len(kfs),
            "events": len(st.get("/events")),
        }
        diff = [f"{k}: {c.get(k)} != {v}" for k, v in actual.items() if c.get(k) != v]
        if diff:
            rep.add("COUNT_MISMATCH", "WARN", M, "; ".join(diff))
    status = (manifest or {}).get("completion", {}).get("status")
    if status in ("partial", "recovered"):
        rep.add("PARTIAL_BUNDLE", "WARN", M, f"completion.status = {status}")
