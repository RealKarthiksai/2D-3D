#!/usr/bin/env bash
# Build the _ckcore Python module into build/core-py/py (used by ckit; see tools/ckit/core.py).
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PYTHON:-tools/.venv/bin/python}"
cmake -S core -B build/core-py -DCMAKE_BUILD_TYPE=Release -DCAPTUREKIT_BUILD_TESTS=OFF \
  -DCAPTUREKIT_BUILD_PYTHON=ON -DPython_EXECUTABLE="$(realpath "$PY")" >/dev/null
cmake --build build/core-py --target _ckcore capturekit_capi --parallel >/dev/null
echo "built $(ls build/core-py/py/_ckcore*.so) $(ls build/core-py/py/libcapturekit_capi.so)"
