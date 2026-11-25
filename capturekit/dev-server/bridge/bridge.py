"""Scan bridge: runs roomscan-recon on a verified Scan Bundle zip and reports the job state.

A host process (not a container: it must reach the laptop's venv and GPU), Python >= 3.11,
stdlib only. roomscan-recon is only ever started as an external process (config ``steps``).

  python bridge.py --config bridge.json          (use roomscan-recon's venv python)

API (contract in dev-server/README.md "Bridge API"). Everything but /healthz needs the
X-CK-Key header; 401 otherwise:

  POST /jobs/<bid>[?retry=1]      create the job (idempotent); ?retry=1 requeues a failed one
  GET  /jobs/<bid>                job JSON, or 404
  GET  /jobs/<bid>/files/<name>   raw room.json | quality.json | reconstruction.json
  GET  /healthz

One worker runs one job at a time: download the zip from the verifier, extract it (unsafe
archives are refused), run each enabled step, read room.json / quality.json, summarize.
"""

from __future__ import annotations

import argparse
import copy
import hmac
import http.client
import json
import logging
import os
import queue
import re
import shutil
import signal
import subprocess
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, replace
from datetime import UTC, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from summary import summarize  # noqa: E402

from ckit.bundle.archive import ArchiveError, extract_archive  # noqa: E402

log = logging.getLogger("bridge")

KEY_HEADER = "X-CK-Key"
BUNDLE_ID_RE = re.compile(r"(?!\.+$)[A-Za-z0-9_.-]{1,128}")  # ids end up in paths; not "." / ".."
STEP_NAME_RE = re.compile(r"[A-Za-z0-9_-]{1,32}")
ROUTE = re.compile(r"/jobs/([^/]+)(?:/files/([^/]+))?")
FILES = {  # whitelist: URL name -> path inside the job folder
    "room.json": "run/spatial_map/room.json",
    "quality.json": "run/spatial_map/quality.json",
    "reconstruction.json": "run/reconstruction/manifest.json",
}
PLACEHOLDERS = ("python", "dataset", "bundle_dir", "run_dir")
MAX_BACKLOG = 256  # queued + processing jobs; more get 503
MAX_REASON = 300
MAX_BODY = 1 << 20  # requests carry no body we use; read at most this much


@dataclass(frozen=True)
class Step:
    name: str
    argv: tuple[str, ...]


@dataclass(frozen=True)
class Config:
    listen: str
    access_key: str
    verifier_url: str
    work_dir: Path
    recon_dir: Path
    python: str
    dataset: str
    timeout_s: float
    steps: tuple[Step, ...]  # enabled steps only


def load_config(path: Path) -> Config:
    """Read and validate the JSON config; raises ValueError naming the problem."""
    try:
        d = json.loads(path.read_text(encoding="utf-8"))
        steps = []
        for s in d["steps"]:
            if not s.get("enabled", True):
                continue
            name = s["name"]
            if not STEP_NAME_RE.fullmatch(name):
                raise ValueError(f"bad step name {name!r}")
            argv = tuple(str(a) for a in s["argv"])
            if not argv:
                raise ValueError(f"step {name}: empty argv")
            for a in argv:  # an unknown placeholder fails here, not in the middle of a job
                a.format_map(dict.fromkeys(PLACEHOLDERS, "x"))
            steps.append(Step(name, argv))
        cfg = Config(
            listen=str(d["listen"]),
            access_key=str(d.get("access_key", "")),
            verifier_url=str(d["verifier_url"]).rstrip("/"),
            work_dir=Path(d["work_dir"]),
            recon_dir=Path(d["recon_dir"]),
            python=str(d["python"]),
            dataset=str(d["dataset"]),
            timeout_s=float(d["timeout_s"]),
            steps=tuple(steps),
        )
    except (OSError, KeyError, TypeError, IndexError) as e:
        raise ValueError(f"{path}: {type(e).__name__}: {e}") from e
    if cfg.timeout_s <= 0:
        raise ValueError(f"{path}: timeout_s must be > 0")
    return cfg


def now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds")


def last_line(path: Path) -> str:
    """Last non-empty line of a log file (the tail only), cut to MAX_REASON characters."""
    try:
        with path.open("rb") as f:
            f.seek(0, os.SEEK_END)
            f.seek(max(0, f.tell() - 4096))
            tail = f.read().decode("utf-8", errors="replace")
    except OSError:
        return "no log"
    lines = [ln.strip() for ln in tail.splitlines() if ln.strip()]
    return (lines[-1] if lines else "no output")[:MAX_REASON]


def kill_tree(proc: subprocess.Popen) -> None:
    """Stop the step and everything it started (a timed-out recon must not keep the GPU)."""
    if sys.platform == "win32":
        subprocess.run(
            ["taskkill", "/F", "/T", "/PID", str(proc.pid)], capture_output=True, check=False
        )
    else:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    proc.kill()


class Bridge:
    def __init__(self, cfg: Config) -> None:
        self.cfg = cfg
        self.lock = threading.Lock()  # guards self.jobs and every job.json write
        self.jobs: dict[str, dict] = {}
        self.queue: queue.Queue[str | None] = queue.Queue()
        self.worker = threading.Thread(target=self._work, name="bridge-worker", daemon=True)
        self._load_jobs()

    # --- job store -------------------------------------------------------------------------
    def job_dir(self, bid: str) -> Path:
        if not BUNDLE_ID_RE.fullmatch(bid):
            raise ValueError(f"invalid bundle id {bid!r}")
        return self.cfg.work_dir / "jobs" / bid

    def _save(self, job: dict) -> None:
        d = self.job_dir(job["bundle_id"])
        d.mkdir(parents=True, exist_ok=True)
        tmp = d / "job.json.tmp"
        tmp.write_text(json.dumps(job, indent=2, sort_keys=True), encoding="utf-8")
        os.replace(tmp, d / "job.json")  # atomic: a kill never leaves half a job file

    def _load_jobs(self) -> None:
        """Reload jobs from disk; ones that were queued or running when we stopped run again."""
        pending = []
        for f in sorted((self.cfg.work_dir / "jobs").glob("*/job.json")):
            try:
                job = json.loads(f.read_text(encoding="utf-8"))
                self.job_dir(job["bundle_id"])
                if not {"state", "created"} <= job.keys():
                    raise KeyError("state/created")
            except (OSError, ValueError, KeyError, TypeError) as e:
                log.warning("ignoring unreadable %s: %s", f, e)
                continue
            if job["state"] in ("queued", "processing"):
                self._reset(job)
                self._save(job)
                pending.append(job)
            self.jobs[job["bundle_id"]] = job
        for job in sorted(pending, key=lambda j: j["created"]):
            self.queue.put(job["bundle_id"])

    def _reset(self, job: dict) -> None:
        job.update(
            state="queued", reason=None, step=None, steps_done=0, started=None, finished=None,
            summary=None, steps_total=len(self.cfg.steps),
        )  # fmt: skip

    def _backlog(self) -> int:
        return sum(1 for j in self.jobs.values() if j["state"] in ("queued", "processing"))

    def get(self, bid: str) -> dict | None:
        with self.lock:
            job = self.jobs.get(bid)
            return copy.deepcopy(job) if job else None

    def submit(self, bid: str, retry: bool) -> tuple[int, dict]:
        """Create the job (201), return the existing one (200) or requeue a failed one (200)."""
        with self.lock:
            job = self.jobs.get(bid)
            if job is not None and not (retry and job["state"] == "failed"):
                return 200, copy.deepcopy(job)
            if self._backlog() >= MAX_BACKLOG:
                return 503, {"error": "too many queued jobs"}
            code = 200
            if job is None:
                code = 201
                job = {"bundle_id": bid, "created": now()}
                self.jobs[bid] = job
            self._reset(job)
            self._save(job)
            self.queue.put(bid)
            return code, copy.deepcopy(job)

    def _update(self, bid: str, **fields) -> None:
        with self.lock:
            self.jobs[bid].update(fields)
            self._save(self.jobs[bid])

    def _fail(self, bid: str, reason: str) -> None:
        log.warning("job %s failed: %s", bid, reason)
        self._update(bid, state="failed", reason=reason[:MAX_REASON], finished=now())

    # --- worker ----------------------------------------------------------------------------
    def start(self) -> None:
        self.worker.start()

    def stop(self) -> None:
        self.queue.put(None)
        self.worker.join(timeout=10)

    def _work(self) -> None:
        while (bid := self.queue.get()) is not None:
            try:
                self._run(bid)
            except Exception as e:  # noqa: BLE001 - one bad job must not kill the worker
                log.exception("job %s crashed", bid)
                self._fail(bid, f"internal error: {type(e).__name__}")

    def _download(self, bid: str, dest: Path) -> None:
        headers = {KEY_HEADER: self.cfg.access_key} if self.cfg.access_key else {}
        req = urllib.request.Request(
            f"{self.cfg.verifier_url}/bundles/{bid}/archive", headers=headers
        )
        tmp = dest.with_name(dest.name + ".tmp")
        with urllib.request.urlopen(req, timeout=60) as r, tmp.open("wb") as f:
            expected = int(r.headers.get("Content-Length", -1))
            shutil.copyfileobj(r, f, 1 << 20)
            if expected >= 0 and f.tell() != expected:
                raise ValueError(f"truncated download ({f.tell()} of {expected} bytes)")
        os.replace(tmp, dest)

    def _run_step(self, step: Step, fixed: dict[str, str], logs: Path) -> str | None:
        """Run one step; returns the failure reason, or None when it exited 0."""
        argv = [a.format_map(fixed) for a in step.argv]
        logs.mkdir(parents=True, exist_ok=True)
        log_path = logs / f"{step.name}.log"
        try:
            with log_path.open("wb") as out:
                proc = subprocess.Popen(  # noqa: S603 - argv list from the config, never a shell
                    argv, cwd=self.cfg.recon_dir, stdin=subprocess.DEVNULL, stdout=out,
                    env={**os.environ, "PYTHONUTF8": "1"},  # non-ASCII output must not crash a step
                    stderr=subprocess.STDOUT, start_new_session=sys.platform != "win32",
                )  # fmt: skip
                try:
                    code = proc.wait(timeout=self.cfg.timeout_s)
                except subprocess.TimeoutExpired:
                    kill_tree(proc)
                    proc.wait()
                    return f"timeout after {self.cfg.timeout_s:g}s"
        except OSError as e:
            return f"{step.name} could not start: {e}"
        if code != 0:
            return f"{step.name} exited {code}: {last_line(log_path)}"
        return None

    def _run(self, bid: str) -> None:
        d = self.job_dir(bid)
        steps = self.cfg.steps
        self._update(
            bid, state="processing", started=now(), finished=None, reason=None,
            step="download", steps_done=0, steps_total=len(steps),
        )  # fmt: skip
        bundle_dir, run_dir = d / "bundle", d / "run"
        for stale in (bundle_dir, run_dir):  # a retry starts clean
            shutil.rmtree(stale, ignore_errors=True)
        try:
            self._download(bid, d / "bundle.zip")
        except (OSError, http.client.HTTPException, ValueError) as e:
            return self._fail(bid, f"download failed: {e}")
        self._update(bid, step="extract")
        try:
            extract_archive(d / "bundle.zip", bundle_dir)
        except (ArchiveError, OSError) as e:
            return self._fail(bid, f"archive rejected: {e}")
        fixed = {
            "python": self.cfg.python,
            "dataset": self.cfg.dataset,
            "bundle_dir": str(bundle_dir),
            "run_dir": str(run_dir),
        }
        for i, step in enumerate(steps):
            self._update(bid, step=step.name, steps_done=i)
            reason = self._run_step(step, fixed, d / "logs")
            if reason:
                return self._fail(bid, reason)
        spatial_map = run_dir / "spatial_map"
        try:
            room = json.loads((spatial_map / "room.json").read_text(encoding="utf-8"))
        except FileNotFoundError:
            return self._fail(bid, "room.json missing")
        except (OSError, ValueError) as e:
            return self._fail(bid, f"room.json unreadable: {e}")
        summary = summarize(
            room,
            self._optional_json(spatial_map / "quality.json"),
            self._optional_json(run_dir / "reconstruction" / "manifest.json"),
        )
        self._update(
            bid, state="done", step=None, steps_done=len(steps), finished=now(), summary=summary
        )

    @staticmethod
    def _optional_json(path: Path) -> dict | None:
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        return data if isinstance(data, dict) else None


def make_handler(bridge: Bridge) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        def _send(self, code: int, obj: dict) -> None:
            self._send_bytes(code, json.dumps(obj, indent=2, sort_keys=True).encode())

        def _send_bytes(self, code: int, body: bytes) -> None:
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _authorized(self) -> bool:
            key = bridge.cfg.access_key
            presented = (self.headers.get(KEY_HEADER) or "").encode()
            return not key or hmac.compare_digest(presented, key.encode())

        def _route(self) -> tuple[str, str | None, dict] | None:
            """(bundle id, file name, query) for a valid request, else None after replying."""
            parts = urllib.parse.urlsplit(self.path)
            m = ROUTE.fullmatch(parts.path)
            if not m:
                self._send(404, {"error": "not found"})
            elif not BUNDLE_ID_RE.fullmatch(m[1]):
                self._send(400, {"error": "invalid bundle id"})
            else:
                return m[1], m[2], urllib.parse.parse_qs(parts.query)
            return None

        def do_GET(self):  # noqa: N802
            if self.path == "/healthz":
                return self._send(200, {"ok": True})
            if not self._authorized():
                return self._send(401, {"error": "access key required"})
            if (route := self._route()) is None:
                return None
            bid, name, _ = route
            job = bridge.get(bid)
            if job is None:
                return self._send(404, {"error": "no such job"})
            if name is None:
                return self._send(200, job)
            if name not in FILES:
                return self._send(404, {"error": "file not available"})
            try:
                body = (bridge.job_dir(bid) / FILES[name]).read_bytes()
            except OSError:
                return self._send(404, {"error": f"{name} does not exist (yet)"})
            return self._send_bytes(200, body)

        def _drain_body(self) -> bool:
            """Read and drop the body; False after answering 400 for a bad Content-Length."""
            try:
                n = int(self.headers.get("Content-Length", 0))
            except ValueError:
                n = -1
            if not 0 <= n <= MAX_BODY:
                self._send(400, {"error": "bad Content-Length"})
                return False
            self.rfile.read(n)
            return True

        def do_POST(self):  # noqa: N802
            if not self._authorized():
                return self._send(401, {"error": "access key required"})
            if not self._drain_body():
                return None
            if (route := self._route()) is None:
                return None
            bid, name, query = route
            if name is not None:
                return self._send(404, {"error": "not found"})
            code, job = bridge.submit(bid, retry=query.get("retry") == ["1"])
            return self._send(code, job)

        def log_message(self, *a):  # quiet
            pass

    return Handler


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    ap.add_argument("--config", required=True, type=Path)
    ap.add_argument("--no-key", action="store_true", help="run without an access key (smoke only)")
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(message)s")
    try:
        cfg = load_config(args.config)
    except ValueError as e:
        print(f"bad config: {e}", file=sys.stderr)
        return 2
    if args.no_key:
        cfg = replace(cfg, access_key="")
    else:
        cfg = replace(cfg, access_key=cfg.access_key or os.environ.get("CK_ACCESS_KEY", ""))
        if not cfg.access_key:
            print(
                "refusing to start: set access_key in the config or CK_ACCESS_KEY", file=sys.stderr
            )
            return 2
    host, _, port = cfg.listen.rpartition(":")
    bridge = Bridge(cfg)
    bridge.start()
    server = ThreadingHTTPServer((host or "0.0.0.0", int(port)), make_handler(bridge))
    log.info("bridge listening on %s, work dir %s", cfg.listen, cfg.work_dir)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        bridge.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
