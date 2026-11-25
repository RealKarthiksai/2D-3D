"""HTTP front for the verifier: tusd hooks + status API. S3/MinIO storage (boto3).

  POST /hook                  tusd http hook (other POST paths 404, bad Content-Length 400);
                              the answer is HTTP 200 and the decision is in the body:
                                pre-create: rejects a wrong X-CK-Key (401) or bad bundle_id (400)
                                post-finish: verifies in a thread (a hook gets 15 s from tusd),
                                  and tells the bridge once an archive upload is COMPLETE
  GET  /bundles/<id>          recomputed status JSON (PENDING/INCOMPLETE/CORRUPT/COMPLETE)
  GET  /bundles/<id>/archive  the verified zip (409 unless COMPLETE, 404 if not an archive)
  GET  /healthz

Every GET except /healthz needs the X-CK-Key header when CK_ACCESS_KEY is set (empty = auth off).
"""

from __future__ import annotations

import hmac
import json
import logging
import os
import re
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from bundle_verify import BUNDLE_ID_RE, Store, Upload, Verifier, iter_chunks

log = logging.getLogger("verifier")
BUCKET = os.environ.get("S3_BUCKET", "scanbundles")
ACCESS_KEY = os.environ.get("CK_ACCESS_KEY", "")
BRIDGE_URL = os.environ.get("BRIDGE_URL", "").rstrip("/")
KEY_HEADER = "X-CK-Key"
MAX_HOOK_BODY = 1 << 20
BUNDLE_ROUTE = re.compile(r"/bundles/([^/]+)(/archive)?")


class S3Store(Store):
    def __init__(self) -> None:
        import boto3  # only the container has it; tests use DirStore and never build this
        from botocore.config import Config

        self.s3 = boto3.client(
            "s3",
            endpoint_url=os.environ.get("S3_ENDPOINT", "http://minio:9000"),
            aws_access_key_id=os.environ.get("AWS_ACCESS_KEY_ID", "capturekit"),
            aws_secret_access_key=os.environ.get("AWS_SECRET_ACCESS_KEY", "capturekit-dev-only"),
            region_name="us-east-1",
            config=Config(retries={"max_attempts": 5}, s3={"addressing_style": "path"}),
        )

    def uploads(self, bundle_id: str) -> list[Upload]:
        from botocore.exceptions import ClientError

        out = []
        for page in self.s3.get_paginator("list_objects_v2").paginate(Bucket=BUCKET):
            for o in page.get("Contents", []):
                if not o["Key"].endswith(".info"):
                    continue
                info = json.loads(self.s3.get_object(Bucket=BUCKET, Key=o["Key"])["Body"].read())
                meta = info.get("MetaData") or {}
                if meta.get("bundle_id") != bundle_id:
                    continue
                key = o["Key"][: -len(".info")]
                try:
                    stored = self.s3.head_object(Bucket=BUCKET, Key=key)["ContentLength"]
                    exists = True
                except ClientError:
                    stored, exists = 0, False
                out.append(Upload(key, info["Size"], meta, o["LastModified"].timestamp(),
                                  exists and stored == info["Size"], stored))  # fmt: skip
        return out

    def open(self, key: str):
        return self.s3.get_object(Bucket=BUCKET, Key=key)["Body"]


store: Store
verifier: Verifier
lock = threading.Lock()


def configure(new_store: Store) -> None:
    """Set the storage the handlers use (S3 in the container, a DirStore in tests)."""
    global store, verifier
    store, verifier = new_store, Verifier(new_store)


def key_ok(presented: str | None) -> bool:
    return not ACCESS_KEY or hmac.compare_digest((presented or "").encode(), ACCESS_KEY.encode())


def hook_key(event: dict) -> str | None:
    """X-CK-Key from a tusd hook body (Go-canonical header names, values are lists)."""
    for name, values in ((event.get("HTTPRequest") or {}).get("Header") or {}).items():
        if name.lower() == KEY_HEADER.lower() and values:
            return values[0]
    return None


def reject(code: int, message: str) -> dict:
    return {
        "RejectUpload": True,
        "HTTPResponse": {
            "StatusCode": code,
            "Body": json.dumps({"error": message}),
            "Header": {"Content-Type": "application/json"},
        },
    }


def pre_create(event: dict) -> dict:
    if not key_ok(hook_key(event)):
        return reject(401, "access key required")
    bid = ((event.get("Upload") or {}).get("MetaData") or {}).get("bundle_id")
    if bid is not None and not BUNDLE_ID_RE.fullmatch(bid):
        return reject(400, "invalid bundle_id")
    return {}


def notify_bridge(bid: str) -> None:
    if not BRIDGE_URL:
        return
    req = urllib.request.Request(
        # retry=1: a job that failed while the zip was still PENDING runs again
        f"{BRIDGE_URL}/jobs/{bid}?retry=1",
        data=b"",
        method="POST",
        headers={KEY_HEADER: ACCESS_KEY},
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            log.info("bridge notified for %s: HTTP %s", bid, r.status)
    except (urllib.error.URLError, OSError) as e:
        log.warning(
            "bridge notify for %s failed (POST /jobs/%s?retry=1 retries it): %s", bid, bid, e
        )


def post_finish(bid: str, meta: dict) -> None:
    """Verify right after an upload finished, in a thread (a hook gets 15 s, a zip takes more)."""
    try:
        with lock:
            st = verifier.check(bid)
        log.info("bundle %s -> %s", bid, st.status)
        if meta.get("archive") == "1" and st.status == "COMPLETE":
            notify_bridge(bid)
    except Exception:  # noqa: BLE001
        log.exception("post-finish for %s failed (status is recomputed on GET anyway)", bid)


class Handler(BaseHTTPRequestHandler):
    def _send(self, code: int, obj: dict) -> None:
        body = json.dumps(obj, indent=2, sort_keys=True).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_archive(self, bid: str) -> None:
        with lock:
            st, up = verifier.check_with_archive(bid)  # up is exactly the zip that was verified
        if st.status != "COMPLETE":
            return self._send(409, {"error": f"bundle is {st.status}, not COMPLETE"})
        if up is None:
            return self._send(404, {"error": "bundle was not uploaded as an archive"})
        self.send_response(200)
        self.send_header("Content-Type", "application/zip")
        self.send_header("Content-Length", str(up.stored_size))
        self.end_headers()
        try:
            with store.open(up.key) as f:
                for chunk in iter_chunks(f):
                    self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
            log.warning("client dropped while downloading the archive of %s", bid)

    def do_GET(self):  # noqa: N802
        path = self.path.split("?")[0]
        if path == "/healthz":
            return self._send(200, {"ok": True})
        if not key_ok(self.headers.get(KEY_HEADER)):
            return self._send(401, {"error": "access key required"})
        m = BUNDLE_ROUTE.fullmatch(path)
        if not m:
            return self._send(404, {"error": "not found"})
        bid = m[1]
        if not BUNDLE_ID_RE.fullmatch(bid):
            return self._send(400, {"error": "invalid bundle id"})
        try:
            if m[2]:
                return self._send_archive(bid)
            with lock:
                return self._send(200, verifier.check(bid).to_json())
        except Exception as e:  # noqa: BLE001
            log.exception("request for %s failed", bid)
            return self._send(500, {"error": type(e).__name__})

    def _read_body(self) -> bytes | None:
        """The request body, or None after answering 400 for a bad or oversized Content-Length."""
        try:
            n = int(self.headers.get("Content-Length", 0))
        except ValueError:
            n = -1
        if not 0 <= n <= MAX_HOOK_BODY:
            self._send(400, {"error": "bad Content-Length"})
            return None
        return self.rfile.read(n)

    def do_POST(self):  # noqa: N802
        raw = self._read_body()  # first: answering with the body unread resets the connection
        if raw is None:
            return None
        if self.path.split("?")[0] != "/hook":
            return self._send(404, {"error": "not found"})
        pre_creating = False
        try:
            ev = json.loads(raw or b"{}")
            pre_creating = ev.get("Type") == "pre-create"
            event = ev["Event"]
            if pre_creating:
                return self._send(200, pre_create(event))
            meta = event["Upload"]["MetaData"]
            bid, path = meta.get("bundle_id"), meta.get("path")
            log.info("post-finish bundle=%s path=%s", bid, path)
            if (
                bid
                and BUNDLE_ID_RE.fullmatch(bid)
                and (path == "manifest.json" or meta.get("archive") == "1")
            ):
                threading.Thread(target=post_finish, args=(bid, meta), daemon=True).start()
        except Exception:  # noqa: BLE001
            log.exception("hook handling failed (status is recomputed on GET anyway)")
            if pre_creating:  # fail closed: never accept an upload whose key check did not run
                return self._send(200, reject(500, "hook failed"))
        self._send(200, {})  # never fail the upload because of the hook

    def log_message(self, *a):  # quiet
        pass


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(message)s")
    configure(S3Store())
    ThreadingHTTPServer(("0.0.0.0", 8081), Handler).serve_forever()
