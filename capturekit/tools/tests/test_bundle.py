"""Phase 1: valid and broken bundles must produce the expected validator codes."""

import json
import shutil
from pathlib import Path

import numpy as np
import pytest

from ckit.bundle.import_raw import import_raw, to_internal_pose
from ckit.bundle.streams import read_streams
from ckit.bundle.validate import validate
from ckit.core import load
from ckit.raw import RawSession
from ckit.rot import F_FLIP, quat_to_mat

ck = load()


@pytest.fixture(scope="module")
def good(
    tmp_path_factory,
) -> Path:
    raw = Path(__file__).parent / "fixtures" / "raw_small"
    out = tmp_path_factory.mktemp("good") / "bundle"
    import_raw(raw, out)
    return out


def _copy(good: Path, tmp_path: Path) -> Path:
    b = tmp_path / "b"
    shutil.copytree(good, b)
    return b


def _refinalize(b: Path) -> None:
    """Rewrite manifest hashes after a deliberate stream edit (so only the semantic issue shows)."""
    m = json.loads((b / "manifest.json").read_text())
    files = ck.bundle.list_files(str(b))
    m["files"] = files
    (b / "manifest.json").write_text(json.dumps(m, indent=2))


def _rewrite_streams(b: Path, mutate) -> None:
    """Re-encode streams.mcap via the C++ writer; mutate() edits poses/frames/intrinsics."""
    src = ck.bundle.read_streams(str(b / "streams.mcap"))
    poses, frames, intr = src["poses"], src["frames"], src["intrinsics"]
    poses, frames, intr = mutate(poses, frames, intr)
    w = ck.bundle.StreamWriter()
    assert w.open(str(b / "streams.mcap"))
    for k in intr:
        w.write_intrinsics(k["t_ns"], k["width"], k["height"], k["fx"], k["fy"], k["cx"], k["cy"])
    for p in poses:
        w.write_pose(p["t_ns"], p["p"], p["q"], p["state"], p["reason"])
    for t, x, y, z in src["gyro"]:
        w.write_gyro(t, x, y, z)
    for t, x, y, z in src["accel"]:
        w.write_accel(t, x, y, z)
    for f in frames:
        w.write_frame(f)
    w.write_event(1, "test", "X")
    w.close()
    _refinalize(b)


def test_valid_bundle_has_no_errors(good) -> None:
    rep = validate(good, "quality")
    assert rep.codes("ERROR") == set(), rep.summary()


def test_roundtrip_matches_raw(good) -> None:
    raw = RawSession.load(Path(__file__).parent / "fixtures" / "raw_small")
    st = read_streams(good / "streams.mcap")
    poses = st.get("/pose")
    assert len(poses) == len(raw.poses)
    for rp, bp in zip(raw.poses, poses, strict=True):
        p, q = to_internal_pose(rp["p"], rp["q"], "arkit")
        np.testing.assert_allclose([bp.tx, bp.ty, bp.tz], p, atol=1e-12)
        np.testing.assert_allclose([bp.qw, bp.qx, bp.qy, bp.qz], q, atol=1e-12)
        # The internal camera looks down +Z, the ARKit camera down -Z.
        R_int = quat_to_mat([bp.qw, bp.qx, bp.qy, bp.qz])
        np.testing.assert_allclose(R_int, quat_to_mat(rp["q"]) @ F_FLIP, atol=1e-9)
    assert len(st.get("/imu/gyro")) == sum(r["type"] == "gyro" for r in raw.imu)
    assert len(st.get("/frame_index")) == len(raw.frames)
    assert st.get("/intrinsics")[0].fx == raw.intrinsics[0]["fx"]


def _break_truncate(b):
    data = (b / "streams.mcap").read_bytes()
    (b / "streams.mcap").write_bytes(data[: len(data) * 2 // 3])
    _refinalize(b)


def _break_sha(b):
    kf = sorted((b / "keyframes").glob("*.jpg"))[0]
    raw = bytearray(kf.read_bytes())
    raw[100] ^= 0xFF
    kf.write_bytes(bytes(raw))


def _break_kf_without_pose(b):
    def m(poses, frames, intr):
        t = frames[2]["t_ns"]
        return [p for p in poses if abs(p["t_ns"] - t) > 200_000_000], frames, intr

    _rewrite_streams(b, m)


def _break_nonmono(b):
    # Write a valid file, then swap two pose messages' timestamps via a fresh Python MCAP.
    from mcap.writer import Writer

    from ckit.bundle import DESC_PATH
    from ckit.bundle.streams import spec_class

    st = read_streams(b / "streams.mcap")
    desc = DESC_PATH.read_bytes()
    with open(b / "streams.mcap", "wb") as f:
        w = Writer(f)
        w.start("capturekit", "test")
        ids = {}
        for topic, msgs in st.messages.items():
            name = type(msgs[0]).DESCRIPTOR.full_name
            sid = w.register_schema(name, "protobuf", desc)
            ids[topic] = w.register_channel(topic, "protobuf", sid)
        for topic in ("/events",):
            if topic not in ids:
                sid = w.register_schema("capturekit.v1.Event", "protobuf", desc)
                ids[topic] = w.register_channel(topic, "protobuf", sid)
        for topic, msgs in st.messages.items():
            msgs = list(msgs)
            if topic == "/pose":
                msgs[5], msgs[6] = msgs[6], msgs[5]
            for m in msgs:
                w.add_message(ids[topic], m.t_ns, m.SerializeToString(), m.t_ns)
        ev = spec_class("capturekit.v1.Event")(t_ns=1, source="t", code="X")
        w.add_message(ids["/events"], 1, ev.SerializeToString(), 1)
        w.finish()
    _refinalize(b)


def _break_nan(b):
    def m(poses, frames, intr):
        poses[4]["p"] = [float("nan"), 0.0, 0.0]
        return poses, frames, intr

    _rewrite_streams(b, m)


def _break_quat(b):
    def m(poses, frames, intr):
        poses[4]["q"] = [0.9, 0.1, 0.0, 0.0]
        return poses, frames, intr

    _rewrite_streams(b, m)


def _break_intrinsics(b):
    _rewrite_streams(b, lambda p, f, i: (p, f, []))


def _break_manifest(b):
    (b / "manifest.json").unlink()


def _break_major(b):
    m = json.loads((b / "manifest.json").read_text())
    m["schema_version"] = "2.0.0"
    (b / "manifest.json").write_text(json.dumps(m))


def _break_minor(b):
    m = json.loads((b / "manifest.json").read_text())
    m["schema_version"] = "1.7.0"
    (b / "manifest.json").write_text(json.dumps(m))


def _break_missing_file(b):
    sorted((b / "keyframes").glob("*.jpg"))[-1].unlink()


def _break_extra_file(b):
    (b / "notes.txt").write_text("x")


CASES = [
    (_break_truncate, "MCAP_TRUNCATED", "ERROR"),
    (_break_sha, "SHA256_MISMATCH", "ERROR"),
    (_break_kf_without_pose, "KEYFRAME_WITHOUT_POSE", "ERROR"),
    (_break_nonmono, "NON_MONOTONIC_TIME", "ERROR"),
    (_break_nan, "NAN_VALUE", "ERROR"),
    (_break_quat, "NON_UNIT_QUATERNION", "ERROR"),
    (_break_intrinsics, "MISSING_INTRINSICS", "ERROR"),
    (_break_manifest, "MISSING_MANIFEST", "ERROR"),
    (_break_major, "UNSUPPORTED_MAJOR", "ERROR"),
    (_break_minor, "UNKNOWN_MINOR", "WARN"),
    (_break_missing_file, "MISSING_FILE", "ERROR"),
    (_break_extra_file, "UNLISTED_FILE", "WARN"),
]


@pytest.mark.parametrize("breaker,code,severity", CASES, ids=[c[1] for c in CASES])
def test_broken_bundle_yields_code(good, tmp_path, breaker, code, severity) -> None:
    b = _copy(good, tmp_path)
    breaker(b)
    rep = validate(b, "semantic")
    assert code in rep.codes(severity), rep.summary()
    if severity == "ERROR":
        assert rep.verdict == "FAIL"


def test_truncation_fuzz_never_crashes(good, tmp_path) -> None:
    b = _copy(good, tmp_path)
    data = (good / "streams.mcap").read_bytes()
    for cut in range(0, len(data), max(1, len(data) // 60)):
        (b / "streams.mcap").write_bytes(data[:cut])
        rep = validate(b, "semantic")
        assert rep.verdict == "FAIL"
        ck.bundle.read_streams(str(b / "streams.mcap"))
