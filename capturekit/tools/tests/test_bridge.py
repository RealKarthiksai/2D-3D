"""Scan bridge (dev-server/bridge): room summary and the job runner, over real HTTP.

roomscan-recon is replaced by bridge/fake_recon.py (test-only); the verifier by a tiny server that
hands out the zip at /bundles/<id>/archive. Everything else (queue, worker, subprocess, extraction,
auth, job files) is the real code.
"""

import http.client
import json
import math
import sys
import threading
import time
import urllib.error
import urllib.request
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

DEV = Path(__file__).parents[2] / "dev-server"
sys.path[:0] = [str(DEV / "bridge"), str(DEV / "verifier")]
import bridge as bridge_mod  # noqa: E402
import bundle_verify  # noqa: E402
from summary import floor_dims_m, summarize  # noqa: E402

FAKE = str(DEV / "bridge" / "fake_recon.py")
KEY = "secret"
ROOM = {
    "floor_polygon": [[0, 0], [4, 0], [4, 3], [0, 3]],
    "floor_polygon_source": "wall_intersections",
    "floor_area_m2": 12.0,
    "ceiling_height_m": 2.5,
    "wall_ids": [1, 2, 3, 4],
    "scale_source": "metric_priors",
    "scale_confidence": 0.8,
    "notes": ["n"],
}


# --- summary ---------------------------------------------------------------------------------


def rotated(poly, deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return [[x * c - y * s + 5, x * s + y * c - 2] for x, y in poly]


def test_summary_of_a_square_room_copies_roomscan_values():
    quality = {"warnings": ["low parallax"], "floor_area_m2": 99.0}
    s = summarize(ROOM, quality, {"outputs": {"mesh": {"status": "skipped", "reason": "no gpu"}}})
    assert s == {
        "floor_area_m2": 12.0, "ceiling_height_m": 2.5, "wall_count": 4,
        "floor_dims_m": [4.0, 3.0], "floor_polygon_source": "wall_intersections",
        "scale_source": "metric_priors", "scale_confidence": 0.8,
        "warnings": ["low parallax"], "notes": ["n"],
        "outputs": {"mesh": {"status": "skipped", "reason": "no gpu"}},
    }  # fmt: skip


def test_floor_dims_of_an_l_shape_and_a_rotated_room_are_the_bounding_rectangle():
    l_shape = [[0, 0], [4, 0], [4, 1], [1, 1], [1, 3], [0, 3]]
    assert floor_dims_m(l_shape, "floor_hull") == [4.0, 3.0]
    assert floor_dims_m(rotated(l_shape, 33), "floor_hull") == [4.0, 3.0]
    assert floor_dims_m(rotated(ROOM["floor_polygon"], 70), "floor_hull") == [4.0, 3.0]


def test_summary_never_invents_numbers():
    empty = summarize(
        {"floor_polygon": [], "floor_polygon_source": "none", "wall_ids": []}, None, None
    )
    assert empty["floor_dims_m"] is None and empty["wall_count"] == 0
    assert empty["floor_area_m2"] is None and empty["outputs"] is None and empty["warnings"] == []
    bare = summarize({}, None, None)
    assert all(bare[k] is None for k in ("floor_area_m2", "ceiling_height_m", "wall_count"))
    assert bare["scale_confidence"] is None and bare["floor_dims_m"] is None
    junk = summarize(
        {"floor_area_m2": float("nan"), "ceiling_height_m": "2.5", "scale_confidence": True},
        None,
        None,
    )
    assert junk["floor_area_m2"] is None and junk["ceiling_height_m"] is None
    assert junk["scale_confidence"] is None
    assert floor_dims_m([[0, 0], [1, 0]], "floor_hull") is None  # not a polygon
    assert floor_dims_m(ROOM["floor_polygon"], "none") is None  # roomscan gave up on the floor
    assert floor_dims_m([[0, 0], [1, "x"], [2, 2]], "floor_hull") is None


@pytest.mark.parametrize("pattern", [bridge_mod.BUNDLE_ID_RE, bundle_verify.BUNDLE_ID_RE])
def test_bundle_id_pattern_is_path_safe(pattern):
    assert pattern.fullmatch("0f8c1a2e-5b7d-4c0e-9a31-6d2b8f4e7a10")
    assert pattern.fullmatch("scan_2026.11-24")
    for bad in ["", ".", "..", "../x", "a/b", "a\\b", "a b", "a\n", "x" * 129, "a%2fb"]:
        assert not pattern.fullmatch(bad), bad


# --- the job runner over HTTP --------------------------------------------------------------------


def build_zip(dest: Path, entries: dict[str, bytes] | None = None) -> bytes:
    with zipfile.ZipFile(dest, "w") as zf:
        for name, data in (entries or {"manifest.json": b'{"files": []}'}).items():
            zf.writestr(name, data)
    return dest.read_bytes()


class FakeVerifier:
    """GET /bundles/<id>/archive with the key; bundles not in .archives answer 409 (pending)."""

    def __init__(self):
        self.archives: dict[str, bytes] = {}
        outer = self

        class H(BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802
                bid = self.path.split("/")[2]
                if self.headers.get("X-CK-Key") != KEY:
                    code, body = 401, b""
                elif bid not in outer.archives:
                    code, body = 409, b"pending"
                else:
                    code, body = 200, outer.archives[bid]
                self.send_response(code)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *a):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"


class Env:
    def __init__(
        self, tmp_path: Path, verifier: FakeVerifier, extra=(), timeout_s=60.0, steps=None
    ):
        fake = ("{python}", FAKE, "--src", "{bundle_dir}", "--out", "{run_dir}", *extra)
        step = steps or (bridge_mod.Step("spatial", fake),)
        self.cfg = bridge_mod.Config(
            listen="127.0.0.1:0", access_key=KEY, verifier_url=verifier.url,
            work_dir=tmp_path / "work", recon_dir=tmp_path, python=sys.executable,
            dataset="scanbundle", timeout_s=timeout_s, steps=step,
        )  # fmt: skip
        self.verifier, self.servers = verifier, []
        self.bridge = self.serve()

    def serve(self) -> bridge_mod.Bridge:
        b = bridge_mod.Bridge(self.cfg)
        b.start()
        srv = ThreadingHTTPServer(("127.0.0.1", 0), bridge_mod.make_handler(b))
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.servers.append((b, srv))
        self.url = f"http://127.0.0.1:{srv.server_port}"
        return b

    def call(self, method, path, key=KEY):
        req = urllib.request.Request(
            self.url + path, method=method, data=b"" if method == "POST" else None,
            headers={"X-CK-Key": key} if key else {},
        )  # fmt: skip
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def job(self, bid):
        code, body = self.call("GET", f"/jobs/{bid}")
        assert code == 200, body
        return json.loads(body)

    def wait(self, bid, states=("done", "failed")):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            job = self.job(bid)
            if job["state"] in states:
                return job
            time.sleep(0.05)
        raise AssertionError(f"job {bid} stuck in {self.job(bid)['state']}")

    def close(self):
        for b, srv in self.servers:
            srv.shutdown()
            b.stop()


@pytest.fixture
def make_env(tmp_path):
    verifier = FakeVerifier()
    verifier.archives["b1"] = build_zip(tmp_path / "ok.zip")
    envs = []

    def make(**kw) -> Env:
        envs.append(Env(tmp_path, verifier, **kw))
        return envs[-1]

    yield make
    for e in envs:
        e.close()
    verifier.server.shutdown()


def test_queued_job_runs_to_done_with_the_summary_and_raw_files(make_env):
    env = make_env()
    code, body = env.call("POST", "/jobs/b1")
    assert code == 201 and json.loads(body)["state"] == "queued"
    job = env.wait("b1")
    assert job["state"] == "done" and job["reason"] is None and job["step"] is None
    assert job["steps_done"] == job["steps_total"] == 1 and job["started"] and job["finished"]
    assert job["summary"]["floor_dims_m"] == [4.0, 3.0] and job["summary"]["wall_count"] == 4
    code, raw = env.call("GET", "/jobs/b1/files/room.json")
    assert code == 200 and json.loads(raw)["floor_area_m2"] == 12.0
    assert env.call("GET", "/jobs/b1/files/quality.json")[0] == 200
    assert env.call("GET", "/jobs/b1/files/reconstruction.json")[0] == 404  # not produced
    assert env.call("GET", "/jobs/b1/files/job.json")[0] == 404  # whitelist only
    assert env.call("POST", "/jobs/b1")[0] == 200  # idempotent: same job, not rerun
    assert (env.cfg.work_dir / "jobs" / "b1" / "run" / "spatial_map" / "room.json").is_file()


def test_failing_step_reports_the_last_log_line(make_env):
    env = make_env(extra=("--fail", "7"))
    env.call("POST", "/jobs/b1")
    job = env.wait("b1")
    assert job["state"] == "failed" and job["step"] == "spatial" and job["summary"] is None
    assert (
        job["reason"]
        == "spatial exited 7: fake_recon: simulated failure: unsupported bundle format"
    )


def test_step_that_finishes_without_room_json_fails(make_env):
    noop = bridge_mod.Step("noop", ("{python}", "-c", "pass"))
    env = make_env(steps=(noop,))
    env.call("POST", "/jobs/b1")
    assert env.wait("b1")["reason"] == "room.json missing"


def test_step_timeout_fails_the_job(make_env):
    env = make_env(extra=("--sleep", "30"), timeout_s=1.0)
    env.call("POST", "/jobs/b1")
    started = time.monotonic()
    job = env.wait("b1")
    assert job["state"] == "failed" and job["reason"] == "timeout after 1s"
    assert time.monotonic() - started < 15


def test_unsafe_archive_is_refused_and_nothing_escapes(make_env, tmp_path):
    env = make_env()
    env.verifier.archives["evil"] = build_zip(
        tmp_path / "evil.zip", {"manifest.json": b"{}", "../escape.txt": b"x"}
    )
    env.call("POST", "/jobs/evil")
    job = env.wait("evil")
    assert job["state"] == "failed" and job["reason"].startswith("archive rejected")
    assert not (env.cfg.work_dir / "jobs" / "escape.txt").exists()


def test_failed_download_can_be_retried(make_env, tmp_path):
    env = make_env()
    env.call("POST", "/jobs/later")  # the verifier does not have it yet (409)
    job = env.wait("later")
    assert job["state"] == "failed" and job["reason"].startswith("download failed: HTTP Error 409")
    code, body = env.call("POST", "/jobs/later")  # without ?retry=1 the failure stays
    assert code == 200 and json.loads(body)["state"] == "failed"
    env.verifier.archives["later"] = build_zip(tmp_path / "later.zip")
    assert env.call("POST", "/jobs/later?retry=1")[0] == 200
    assert env.wait("later")["state"] == "done"


def test_bad_ids_and_missing_key(make_env):
    env = make_env()
    for bad in ("a$b", "..", "a%20b"):
        assert env.call("POST", f"/jobs/{bad}")[0] == 400, bad
        assert env.call("GET", f"/jobs/{bad}")[0] == 400, bad
    assert env.call("GET", "/jobs/nope")[0] == 404 and env.call("GET", "/elsewhere")[0] == 404
    for method, path in (
        ("POST", "/jobs/b1"),
        ("GET", "/jobs/b1"),
        ("GET", "/jobs/b1/files/room.json"),
    ):
        assert env.call(method, path, key=None)[0] == 401
        assert env.call(method, path, key="wrong")[0] == 401
    assert env.call("GET", "/healthz", key=None)[0] == 200
    assert not (env.cfg.work_dir / "jobs" / "b1").exists()  # a refused request created nothing


def test_restart_requeues_queued_and_interrupted_jobs(make_env):
    env = make_env()
    env.close()  # stop the first bridge, then fake what a crash leaves behind
    first = bridge_mod.Bridge(env.cfg)  # worker not started: the job stays queued
    assert first.submit("b1", retry=False)[0] == 201
    first.jobs["b1"]["state"] = "processing"  # killed mid-run
    first._save(first.jobs["b1"])
    env.servers = []
    env.serve()
    job = env.wait("b1")
    assert job["state"] == "done"


def test_bad_content_length_is_a_400_after_the_key_check(make_env):
    env = make_env()
    host, port = env.url.removeprefix("http://").split(":")

    def post(length, key):
        conn = http.client.HTTPConnection(host, int(port), timeout=10)
        conn.putrequest("POST", "/jobs/b1")
        conn.putheader("Content-Length", length)
        if key:
            conn.putheader("X-CK-Key", key)
        conn.endheaders()
        status = conn.getresponse().status
        conn.close()
        return status

    for length in ("abc", "-1", str(10**9)):
        assert post(length, KEY) == 400, length
        assert post(length, None) == 401, length  # no key: refused before the body is looked at
    assert env.call("GET", "/jobs/b1")[0] == 404  # none of them created a job


def test_step_printing_non_ascii_text_still_succeeds(make_env):
    env = make_env(extra=("--say", "room \u2192 ok \u2713"))
    env.call("POST", "/jobs/b1")
    assert env.wait("b1")["state"] == "done"
