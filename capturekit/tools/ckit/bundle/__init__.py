"""Scan Bundle I/O and validation (spec/scanbundle/SPEC.md)."""

from pathlib import Path

from ckit.core import CAPTUREKIT_ROOT

SPEC_DIR = CAPTUREKIT_ROOT / "spec" / "scanbundle"
DESC_PATH = SPEC_DIR / "messages" / "capturekit.desc"
SUPPORTED_MAJOR = 1
SUPPORTED_MINOR = 0

__all__ = ["SPEC_DIR", "DESC_PATH", "SUPPORTED_MAJOR", "SUPPORTED_MINOR", "Path"]
