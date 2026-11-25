"""Phase 7: verifier logic against a local tusd-layout store (no docker), plus tus client units."""

import base64
import hashlib
import http.client
import io
import json
import sys
import threading
import time
import urllib.error
import urllib.request
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parents[2] / "dev-server" / "verifier"))
import bundle_verify  # noqa: E402
import server  # noqa: E402
from bundle_verify import DirStore, Verifier  # noqa: E402

from ckit.upload import (  # noqa: E402
    Faults,
    TusUploader,
    UploadError,
    UploadResult,
    _meta,
    delete_after_verify,
)


def sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


class Fake:
    """Writes tusd-style objects: <key> and <key>.info (MetaData, Size)."""

    def __init__(self, root: Path, bundle_id="b1"):
        self.root, self.bid, self.n = root, bundle_id, 0

    def put(
        self,
        path: str,
        data: bytes,
        declared: bytes | None = None,
        complete=True,
        cut=None,
        **extra,
    ):
        self.n += 1
        key = f"u{self.n:04d}"
        meta = {
            "bundle_id": self.bid,
            "path": path,
            "sha256": sha(declared if declared is not None else data),
            **extra,
        }
        (self.root / f"{key}.info").write_text(json.dumps({"Size": len(data), "MetaData": meta}))
        if complete or cut:
            (self.root / key).write_bytes(data[:cut] if cut else data)
        time.sleep(0.002)  # distinct mtimes: latest upload of a path wins

    def manifest(self, files: dict[str, bytes], **kw):
        m = {"files": [{"path": p, "size": len(d), "sha256": sha(d)} for p, d in files.items()]}
        m["validation"] = {"verdict": "PASS"}
        self.put("manifest.json", json.dumps(m).encode(), **kw)


@pytest.fixture
def env(tmp_path):
    return Fake(tmp_path), Verifier(DirStore(tmp_path))


FILES = {"streams.mcap": b"a" * 1000, "camera.json": b"{}", "keyframes/000000.jpg": b"jpeg" * 50}


def test_pending_until_manifest(env):
    f, v = env
    f.put("streams.mcap", FILES["streams.mcap"])
    s = v.check("b1")
    assert s.status == "PENDING" and "manifest.json has not arrived" in s.reason


def test_complete(env):
    f, v = env
    for p, d in FILES.items():
        f.put(p, d)
    f.manifest(FILES)
    s = v.check("b1")
    assert s.status == "COMPLETE" and s.verdict == {"verdict": "PASS"}


def test_incomplete_missing_file_then_resolves_when_it_arrives(env):
    f, v = env
    f.put("streams.mcap", FILES["streams.mcap"])
    f.put("camera.json", FILES["camera.json"])
    f.manifest(FILES)
    s = v.check("b1")
    assert s.status == "INCOMPLETE" and s.missing == ["keyframes/000000.jpg"]
    f.put("keyframes/000000.jpg", FILES["keyframes/000000.jpg"])
    assert v.check("b1").status == "COMPLETE"


def test_truncated_upload_is_incomplete_not_complete(env):
    f, v = env
    f.put("streams.mcap", FILES["streams.mcap"])
    f.put("camera.json", FILES["camera.json"])
    f.put("keyframes/000000.jpg", FILES["keyframes/000000.jpg"], cut=60)  # object shorter than Size
    f.manifest(FILES)
    s = v.check("b1")
    assert s.status == "INCOMPLETE" and "keyframes/000000.jpg" in s.missing


def test_corrupted_chunk_detected_and_repaired_by_reupload(env):
    f, v = env
    bad = bytearray(FILES["streams.mcap"])
    bad[10] ^= 0xFF
    f.put("streams.mcap", bytes(bad), declared=FILES["streams.mcap"])
    f.put("camera.json", FILES["camera.json"])
    f.put("keyframes/000000.jpg", FILES["keyframes/000000.jpg"])
    f.manifest(FILES)
    s = v.check("b1")
    assert s.status == "CORRUPT" and s.corrupt[0]["path"] == "streams.mcap"
    f.put("streams.mcap", FILES["streams.mcap"])
    f.manifest(FILES)
    assert v.check("b1").status == "COMPLETE"


def test_tampered_file_after_manifest_is_corrupt(env):
    f, v = env
    for p, d in FILES.items():
        f.put(p, d if p != "camera.json" else b'{"x":1}')  # same size class, different bytes
    f.manifest(FILES)
    s = v.check("b1")
    assert s.status == "CORRUPT" and "camera.json" in s.reason


def test_size_mismatch_and_unreadable_manifest(env):
    f, v = env
    f.put("camera.json", b"{}" * 3)
    f.put("manifest.json", b"not json")
    assert v.check("b1").status == "CORRUPT"
    f2 = Fake(f.root, "b2")
    f2.put("camera.json", b"{}" * 3)
    f2.manifest({"camera.json": b"{}"})
    assert v.check("b2").corrupt[0]["reason"].startswith("size ")


def test_bundles_are_isolated(env):
    f, v = env
    for p, d in FILES.items():
        f.put(p, d)
    f.manifest(FILES)
    assert v.check("b1").status == "COMPLETE"
    assert v.check("other").status == "PENDING"


def test_status_recomputed_from_storage_after_restart(env):
    f, v = env
    for p, d in FILES.items():
        f.put(p, d)
    f.manifest(FILES)
    v2 = Verifier(DirStore(f.root))  # a fresh process: no memory of any hook event
    assert v2.check("b1").status == "COMPLETE"


def test_tus_metadata_encoding():
    assert _meta({"a": "x"}) == "a eA=="


def test_retention_requires_consent_and_complete(tmp_path):
    d = tmp_path / "b"
    d.mkdir()
    (d / "f").write_text("x")
    assert not delete_after_verify(d, UploadResult(status="COMPLETE"), consent=False) and d.exists()
    assert not delete_after_verify(d, UploadResult(status="CORRUPT"), consent=True) and d.exists()
    assert delete_after_verify(d, UploadResult(status="COMPLETE"), consent=True) and not d.exists()


# --- archive mode: the whole bundle as one zip (tus metadata archive=1) ---------------------------


def make_zip(files: dict[str, bytes], tamper: str | None = None) -> bytes:
    """A bundle zip whose manifest lists `files`; `tamper` swaps that entry's bytes afterwards."""
    manifest = {"files": [{"path": p, "size": len(d), "sha256": sha(d)} for p, d in files.items()]}
    manifest["validation"] = {"verdict": "PASS"}
    manifest["bundle_id"] = "b1"
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("manifest.json", json.dumps(manifest))
        for p, d in files.items():
            zf.writestr(p, bytes([d[0] ^ 1]) + d[1:] if p == tamper else d)
    return buf.getvalue()


def test_archive_complete_with_manifest_verdict(env):
    f, v = env
    f.put("scan.zip", make_zip(FILES), archive="1")
    s = v.check("b1")
    assert (
        s.status == "COMPLETE" and s.verdict == {"verdict": "PASS"} and s.received == ["scan.zip"]
    )


def test_archive_whole_file_sha_mismatch_is_corrupt(env):
    f, v = env
    data = bytearray(make_zip(FILES))
    data[-30] ^= 0xFF  # a flipped byte in transit
    f.put("scan.zip", bytes(data), declared=make_zip(FILES), archive="1")
    s = v.check("b1")
    assert s.status == "CORRUPT" and s.corrupt == [
        {"path": "scan.zip", "reason": "sha256 differs from the uploader's value"}
    ]


def test_archive_with_tampered_entry_is_corrupt(env):
    f, v = env
    f.put("scan.zip", make_zip(FILES, tamper="streams.mcap"), archive="1")
    s = v.check("b1")
    assert s.status == "CORRUPT" and "sha256 mismatch: streams.mcap" in s.corrupt[0]["reason"]
    assert s.corrupt[0]["path"] == "scan.zip" and s.verdict is None


def test_archive_not_a_zip_is_corrupt(env):
    f, v = env
    f.put("scan.zip", b"definitely not a zip", archive="1")
    s = v.check("b1")
    assert s.status == "CORRUPT" and "not a readable zip" in s.corrupt[0]["reason"]


def test_archive_incomplete_is_pending_and_latest_complete_wins(env):
    f, v = env
    data = make_zip(FILES)
    f.put("scan.zip", data, archive="1", cut=100)  # object shorter than Size
    s = v.check("b1")
    assert s.status == "PENDING" and "scan.zip" in s.reason
    f.put("scan.zip", b"junk", archive="1")  # an older, broken finished upload ...
    f.put("scan.zip", data, archive="1")  # ... is replaced by the newest complete one
    assert v.check("b1").status == "COMPLETE"


def test_archive_result_is_cached_per_stored_object(env, monkeypatch):
    f, v = env
    f.put("scan.zip", make_zip(FILES), archive="1")
    calls = []
    real = bundle_verify.verify_archive
    monkeypatch.setattr(bundle_verify, "verify_archive", lambda p: calls.append(p) or real(p))
    assert v.check("b1").status == v.check("b1").status == "COMPLETE"
    assert len(calls) == 1


def test_loose_folder_mode_ignores_unrelated_archive_flag(env):
    f, v = env
    for p, d in FILES.items():
        f.put(p, d, archive="0")
    f.manifest(FILES)
    assert v.check("b1").status == "COMPLETE"


# --- ckit upload of a packed zip against an in-process tus server + verifier ---------------------


@pytest.fixture
def tus(tmp_path):
    """A tusd-shaped server (objects + .info in a DirStore folder) with the verifier status API,
    guarded by the X-CK-Key header like the real stack."""
    verifier = Verifier(DirStore(tmp_path))
    key = "secret"

    class H(BaseHTTPRequestHandler):
        def _reply(self, code, headers=None, body=b""):
            self.send_response(code)
            for k, v in (headers or {}).items():
                self.send_header(k, v)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _body(self) -> bytes | None:  # None = refused (wrong key)
            data = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            if self.headers.get("X-CK-Key") != key:
                self._reply(401)
                return None
            return data

        def do_POST(self):  # noqa: N802
            if self._body() is None:
                return
            pairs = (kv.split(" ") for kv in self.headers["Upload-Metadata"].split(","))
            meta = {k: base64.b64decode(v).decode() for k, v in pairs}
            name = f"u{len(list(tmp_path.glob('*.info'))) + 1:04d}"
            time.sleep(0.02)  # distinct mtimes: the newest upload wins
            info = {"Size": int(self.headers["Upload-Length"]), "MetaData": meta}
            (tmp_path / f"{name}.info").write_text(json.dumps(info))
            (tmp_path / name).write_bytes(b"")
            self._reply(
                201, {"Location": f"http://127.0.0.1:{self.server.server_port}/files/{name}"}
            )

        def do_HEAD(self):  # noqa: N802
            if self._body() is not None:
                size = (tmp_path / self.path.rsplit("/", 1)[1]).stat().st_size
                self._reply(200, {"Upload-Offset": str(size)})

        def do_PATCH(self):  # noqa: N802
            data = self._body()
            if data is not None:
                with (tmp_path / self.path.rsplit("/", 1)[1]).open("ab") as f:
                    f.write(data)
                    size = f.tell()
                self._reply(204, {"Upload-Offset": str(size)})

        def do_GET(self):  # noqa: N802
            if self._body() is not None:
                bid = self.path.rsplit("/", 1)[1]
                self._reply(200, body=json.dumps(verifier.check(bid).to_json()).encode())

        def log_message(self, *a):
            pass

    srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{srv.server_port}", key
    srv.shutdown()


def uploader(base: str, key: str | None, **kw) -> TusUploader:
    return TusUploader(
        base + "/files/", base, chunk_size=64, sleep=lambda s: None,
        headers={"X-CK-Key": key} if key else None, **kw,
    )  # fmt: skip


def test_upload_zip_is_one_complete_upload_with_the_key(tus, tmp_path_factory):
    base, key = tus
    z = tmp_path_factory.mktemp("out") / "scan_1.zip"
    z.write_bytes(make_zip(FILES))
    res = uploader(base, key).upload_bundle(z)
    assert res.status == "COMPLETE" and res.server["received"] == ["scan_1.zip"]
    assert res.server["verdict"] == {"verdict": "PASS"}


def test_upload_zip_without_the_key_is_refused(tus, tmp_path_factory):
    base, _ = tus
    z = tmp_path_factory.mktemp("out") / "scan_1.zip"
    z.write_bytes(make_zip(FILES))
    with pytest.raises(UploadError, match="HTTP 401"):
        uploader(base, None).upload_bundle(z)


def test_upload_zip_damaged_in_transit_is_resent_once(tus, tmp_path_factory):
    base, key = tus
    z = tmp_path_factory.mktemp("out") / "scan_1.zip"
    z.write_bytes(make_zip(FILES))
    res = uploader(base, key, faults=Faults(corrupt_path="scan_1.zip")).upload_bundle(z)
    assert res.repaired == ["scan_1.zip"] and res.status == "COMPLETE", res.reason


def test_delete_after_verify_removes_a_zip(tmp_path):
    z = tmp_path / "scan.zip"
    z.write_bytes(b"x")
    assert delete_after_verify(z, UploadResult(status="COMPLETE"), consent=True) and not z.exists()


# --- verifier HTTP handlers (server.py) against a DirStore ---------------------------------------


def call(base, method, path, key=None, body=None):
    headers = {"X-CK-Key": key} if key else {}
    req = urllib.request.Request(base + path, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def hook(base, typ, meta, key=None, event=None):
    header = {"X-Ck-Key": [key]} if key else {}  # tusd sends Go-canonical header names
    ev = event or {"Upload": {"MetaData": meta}, "HTTPRequest": {"Header": header}}
    code, body = call(base, "POST", "/hook", body=json.dumps({"Type": typ, "Event": ev}).encode())
    assert code == 200
    return json.loads(body)


@pytest.fixture
def srv(tmp_path, monkeypatch):
    monkeypatch.setattr(server, "ACCESS_KEY", "secret")
    monkeypatch.setattr(server, "BRIDGE_URL", "")
    server.configure(DirStore(tmp_path))
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield Fake(tmp_path), f"http://127.0.0.1:{httpd.server_port}"
    httpd.shutdown()


def test_pre_create_checks_the_key_and_the_bundle_id_and_fails_closed(srv, monkeypatch):
    _, base = srv
    ok = {"bundle_id": "b1"}
    refused = hook(base, "pre-create", ok)
    assert refused["RejectUpload"] and refused["HTTPResponse"]["StatusCode"] == 401
    assert hook(base, "pre-create", ok, key="wrong")["HTTPResponse"]["StatusCode"] == 401
    assert hook(base, "pre-create", ok, key="secret") == {}
    bad_id = hook(base, "pre-create", {"bundle_id": "../x"}, key="secret")
    assert bad_id["HTTPResponse"]["StatusCode"] == 400
    broken = json.loads(call(base, "POST", "/hook", body=b'{"Type": "pre-create"}')[1])
    assert broken["RejectUpload"] and broken["HTTPResponse"]["StatusCode"] == 500
    monkeypatch.setattr(server, "ACCESS_KEY", "")  # auth off (CI): everything is accepted
    assert hook(base, "pre-create", ok) == {}


def test_endpoints_need_the_key_and_the_archive_is_the_one_that_was_verified(srv):
    f, base = srv
    data = make_zip(FILES)
    f.put("scan.zip", data, archive="1")
    assert call(base, "GET", "/healthz")[0] == 200
    for path in ("/bundles/b1", "/bundles/b1/archive"):
        assert call(base, "GET", path)[0] == 401 and call(base, "GET", path, key="x")[0] == 401
    assert json.loads(call(base, "GET", "/bundles/b1", key="secret")[1])["status"] == "COMPLETE"
    assert call(base, "GET", "/bundles/b1/archive", key="secret") == (200, data)
    assert call(base, "GET", "/bundles/..", key="secret")[0] == 400
    f.put("scan.zip", b"a newer upload that does not verify", archive="1")
    code, body = call(base, "GET", "/bundles/b1/archive", key="secret")
    assert code == 409 and b"CORRUPT" in body  # never streams an unverified newer upload


def test_check_with_archive_returns_the_verified_upload(env):
    f, v = env
    f.put("scan.zip", make_zip(FILES), archive="1")
    st, up = v.check_with_archive("b1")
    assert st.status == "COMPLETE" and up.meta["path"] == "scan.zip"
    f.put("scan.zip", b"junk", archive="1")
    st, up = v.check_with_archive("b1")
    assert st.status == "CORRUPT" and up is None


def test_loose_bundle_has_no_archive_to_download(srv):
    f, base = srv
    for p, d in FILES.items():
        f.put(p, d)
    f.manifest(FILES)
    assert call(base, "GET", "/bundles/b1/archive", key="secret")[0] == 404


def test_finished_archive_notifies_the_bridge_with_retry_and_key(srv, monkeypatch):
    f, base = srv
    seen = []

    class Bridge(BaseHTTPRequestHandler):
        def do_POST(self):  # noqa: N802
            seen.append((self.path, self.headers.get("X-CK-Key")))
            self.send_response(200)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def log_message(self, *a):
            pass

    bridge = ThreadingHTTPServer(("127.0.0.1", 0), Bridge)
    threading.Thread(target=bridge.serve_forever, daemon=True).start()
    monkeypatch.setattr(server, "BRIDGE_URL", f"http://127.0.0.1:{bridge.server_port}")
    f.put("scan.zip", make_zip(FILES), archive="1")
    meta = {"bundle_id": "b1", "path": "scan.zip", "archive": "1"}
    assert hook(base, "post-finish", meta) == {}  # answers at once; verification runs in a thread
    deadline = time.monotonic() + 10
    while not seen and time.monotonic() < deadline:
        time.sleep(0.05)
    bridge.shutdown()
    assert seen == [("/jobs/b1?retry=1", "secret")]


def test_hook_accepts_only_the_hook_path_and_a_sane_content_length(srv):
    _, base = srv
    assert call(base, "POST", "/other", body=b"{}")[0] == 404
    assert call(base, "POST", "/bundles/b1", body=b"{}")[0] == 404
    host, port = base.removeprefix("http://").split(":")
    for length in ("abc", "-1", str(10**9)):
        conn = http.client.HTTPConnection(host, int(port), timeout=10)
        conn.putrequest("POST", "/hook")
        conn.putheader("Content-Length", length)
        conn.endheaders()
        assert conn.getresponse().status == 400, length
        conn.close()
