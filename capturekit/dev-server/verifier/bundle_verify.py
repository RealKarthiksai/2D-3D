"""Server-side bundle verification (stdlib only; storage behind a small interface).

A Scan Bundle arrives as one tus upload per file with metadata {bundle_id, path, sha256}; the
manifest is uploaded LAST. Status is always recomputed from what is actually in storage (never
from hook events), so a missed hook or a restart cannot leave a bundle stuck:

  PENDING     no completed manifest.json yet
  INCOMPLETE  manifest arrived, some listed files are not (completely) uploaded
  CORRUPT     a listed file is complete but its size/sha256 differs from the manifest, or an
              upload's own declared sha256 differs from its bytes. Takes priority over INCOMPLETE.
  COMPLETE    manifest arrived and every listed file matches sha256 and size

Archive mode (Issue #10): an upload with tus metadata archive=1 (path = the zip's file name)
carries the whole bundle as one zip. The latest complete archive upload wins; an unfinished one
is PENDING. It is COMPLETE when the whole-file sha256 matches the uploader's value and
`verify_archive` finds the zip sound, else CORRUPT (corrupt = [{path: <zip name>, reason}]).
The verdict is the manifest's `validation`.
"""

from __future__ import annotations

import hashlib
import json
import re
import shutil
import tempfile
import zipfile
from collections.abc import Iterator
from dataclasses import dataclass, field
from pathlib import Path
from typing import BinaryIO, Protocol

try:  # the bundle tooling (tests, laptop) or the copy baked into the verifier image
    from ckit.bundle.archive import verify_archive
except ImportError:
    from ck_archive import verify_archive

MANIFEST = "manifest.json"
BUNDLE_ID_RE = re.compile(r"(?!\.+$)[A-Za-z0-9_.-]{1,128}")  # ids end up in paths; not "." / ".."


@dataclass
class Upload:
    key: str  # storage key of the finished object
    size: int  # declared tus Upload-Length
    meta: dict  # tus metadata (bundle_id, path, sha256, ...)
    mtime: float  # creation/modification time, for "latest upload of a path wins"
    complete: bool  # finished object present with the declared size
    stored_size: int = 0


class Store(Protocol):
    def uploads(self, bundle_id: str) -> list[Upload]: ...
    def open(self, key: str) -> BinaryIO: ...


@dataclass
class Status:
    bundle_id: str
    status: str
    reason: str = ""
    received: list[str] = field(default_factory=list)
    missing: list[str] = field(default_factory=list)
    corrupt: list[dict] = field(default_factory=list)
    verdict: dict | None = None

    def to_json(self) -> dict:
        return {
            "bundle_id": self.bundle_id, "status": self.status, "reason": self.reason,
            "received": sorted(self.received), "missing": sorted(self.missing),
            "corrupt": self.corrupt, "verdict": self.verdict,
        }  # fmt: skip


class Verifier:
    def __init__(self, store: Store) -> None:
        self.store = store
        self._hash_cache: dict[tuple[str, int, float], str] = {}
        self._archive_cache: dict[tuple[str, int, float], tuple[list[str], dict | None]] = {}

    def _sha256(self, u: Upload) -> str:
        ck = (u.key, u.stored_size, u.mtime)
        if ck not in self._hash_cache:
            h = hashlib.sha256()
            with self.store.open(u.key) as f:
                for chunk in iter(lambda: f.read(1 << 20), b""):
                    h.update(chunk)
            self._hash_cache[ck] = h.hexdigest()
        return self._hash_cache[ck]

    @staticmethod
    def _latest_complete(ups: list[Upload]) -> dict[str, Upload]:
        out: dict[str, Upload] = {}
        for u in sorted(ups, key=lambda x: x.mtime):
            p = u.meta.get("path")
            if p and u.complete:
                out[p] = u
        return out

    @staticmethod
    def latest_archive(ups: list[Upload], *, complete_only: bool = True) -> Upload | None:
        """Newest archive upload (tus metadata archive=1) that has a zip name."""
        cands = [
            u for u in ups
            if u.meta.get("archive") == "1" and u.meta.get("path")
            and (u.complete or not complete_only)
        ]  # fmt: skip
        return max(cands, key=lambda u: u.mtime, default=None)

    def _inspect_archive(self, u: Upload) -> tuple[list[str], dict | None]:
        """(problems, manifest validation) of the stored zip; cached like the file hashes."""
        ck = (u.key, u.stored_size, u.mtime)
        if ck not in self._archive_cache:
            with tempfile.TemporaryDirectory() as tmp:
                copy = Path(tmp) / "bundle.zip"
                with self.store.open(u.key) as src, copy.open("wb") as dst:
                    shutil.copyfileobj(src, dst, 1 << 20)
                problems = verify_archive(copy)
                validation = None
                if not problems:
                    with zipfile.ZipFile(copy) as zf:
                        validation = json.loads(zf.read(MANIFEST)).get("validation")
            self._archive_cache[ck] = (problems, validation)
        return self._archive_cache[ck]

    def _check_archive(
        self, bundle_id: str, ups: list[Upload]
    ) -> tuple[Status, Upload | None] | None:
        """(status, the verified upload if COMPLETE) in archive mode; None without an archive."""
        u = self.latest_archive(ups)
        if u is None:
            partial = self.latest_archive(ups, complete_only=False)
            if partial is None:
                return None
            name = partial.meta["path"]
            pending = Status(bundle_id, "PENDING", f"archive upload {name} is not finished yet")
            return pending, None
        name = u.meta["path"]
        st = Status(bundle_id, "PENDING", received=[name])
        declared = u.meta.get("sha256")
        if declared and self._sha256(u) != declared:
            st.corrupt.append({"path": name, "reason": "sha256 differs from the uploader's value"})
        else:
            problems, st.verdict = self._inspect_archive(u)
            if problems:
                st.corrupt.append({"path": name, "reason": "; ".join(problems[:5])})
        if st.corrupt:
            st.status, st.reason = "CORRUPT", f"{name}: {st.corrupt[0]['reason']}"
        else:
            st.status, st.reason = "COMPLETE", f"archive {name} verified"
        return st, (u if st.status == "COMPLETE" else None)

    def check_with_archive(self, bundle_id: str) -> tuple[Status, Upload | None]:
        """Status plus the archive upload that was verified (None unless COMPLETE in archive mode).

        One storage listing serves both, so what gets streamed is exactly what was verified.
        """
        ups = self.store.uploads(bundle_id)
        archive = self._check_archive(bundle_id, ups)
        if archive is not None:
            return archive
        return self._check_files(bundle_id, ups), None

    def check(self, bundle_id: str) -> Status:
        return self.check_with_archive(bundle_id)[0]

    def _check_files(self, bundle_id: str, ups: list[Upload]) -> Status:
        done = self._latest_complete(ups)
        st = Status(bundle_id, "PENDING", received=list(done))
        man = done.get(MANIFEST)
        if man is None:
            st.reason = "manifest.json has not arrived (it is uploaded last)"
            partial = sorted({u.meta.get("path", "?") for u in ups if not u.complete})
            if partial:
                st.reason += f"; incomplete uploads: {', '.join(partial)}"
            return st
        try:
            with self.store.open(man.key) as f:
                manifest = json.loads(f.read().decode())
            files = manifest["files"]
        except Exception as e:  # noqa: BLE001
            st.status, st.reason = "CORRUPT", f"manifest.json unreadable: {type(e).__name__}"
            st.corrupt.append({"path": MANIFEST, "reason": "unreadable"})
            return st
        st.verdict = manifest.get("validation")
        declared = man.meta.get("sha256")
        if declared and self._sha256(man) != declared:
            st.corrupt.append(
                {"path": MANIFEST, "reason": "sha256 differs from the uploader's value"}
            )
        for entry in files:
            path = entry["path"]
            u = done.get(path)
            if u is None:
                st.missing.append(path)
                continue
            if u.stored_size != entry.get("size", u.stored_size):
                st.corrupt.append(
                    {"path": path, "reason": f"size {u.stored_size} != manifest {entry['size']}"}
                )
                continue
            actual = self._sha256(u)
            if "sha256" in entry and actual != entry["sha256"]:
                st.corrupt.append({"path": path, "reason": "sha256 differs from the manifest"})
            elif u.meta.get("sha256") and actual != u.meta["sha256"]:
                st.corrupt.append(
                    {"path": path, "reason": "sha256 differs from the uploader's value"}
                )
        if st.corrupt:
            st.status = "CORRUPT"
            st.reason = "; ".join(f"{c['path']}: {c['reason']}" for c in st.corrupt)
        elif st.missing:
            st.status = "INCOMPLETE"
            st.reason = f"{len(st.missing)} listed file(s) not uploaded yet: " + ", ".join(
                sorted(st.missing)[:5]
            )
        else:
            st.status, st.reason = "COMPLETE", f"{len(files)} files verified"
        return st


class DirStore:
    """Test/local store: <root>/<key> object files and <root>/<key>.info JSON (tusd layout)."""

    def __init__(self, root) -> None:
        from pathlib import Path

        self.root = Path(root)

    def uploads(self, bundle_id: str) -> list[Upload]:
        out = []
        for info in sorted(self.root.glob("*.info")):
            d = json.loads(info.read_text())
            if d.get("MetaData", {}).get("bundle_id") != bundle_id:
                continue
            key = info.name[: -len(".info")]
            obj = self.root / key
            stored = obj.stat().st_size if obj.exists() else 0
            out.append(Upload(key, d["Size"], d["MetaData"], info.stat().st_mtime_ns / 1e9,
                              obj.exists() and stored == d["Size"], stored))  # fmt: skip
        return out

    def open(self, key: str) -> BinaryIO:
        return open(self.root / key, "rb")


def iter_chunks(f: BinaryIO, n: int = 1 << 20) -> Iterator[bytes]:
    return iter(lambda: f.read(n), b"")
