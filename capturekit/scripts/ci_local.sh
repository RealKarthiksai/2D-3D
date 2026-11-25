#!/usr/bin/env bash
# Run the Linux jobs of ../.github/workflows/ci.yml locally (WSL/Ubuntu), so "CI green" can be checked before a push.
# Keep each job_* body in step with its ci.yml job; build trees and the venv live in $HOME, not on OneDrive.
# Usage: scripts/ci_local.sh [--jobs core,sanitizers,python,schema,golden]   (default: core,python,schema,golden)
# Not mirrored: upload (needs docker), ios (needs macOS), android (run on Windows, see scripts/README.md).
set -euo pipefail
cd "$(dirname "$0")/.."
CK="$PWD"
CI="${CKCI_DIR:-$HOME/ck-ci}"
JOBS="core,python,schema,golden"

while [ $# -gt 0 ]; do
  case "$1" in
    --jobs) JOBS="${2:?--jobs needs a comma list}"; shift 2 ;;
    *) echo "usage: $0 [--jobs core,sanitizers,python,schema,golden]" >&2; exit 2 ;;
  esac
done

if ! command -v cmake >/dev/null; then
  for d in "$HOME"/tools/cmake-*/bin; do [ -d "$d" ] && PATH="$d:$PATH"; done
fi
export PATH="$HOME/.local/bin:$PATH"
# Caps for the 7 GB WSL box (PROGRESS.md): ci.yml uses a bare --parallel, this env var bounds it.
export CMAKE_BUILD_PARALLEL_LEVEL="${CMAKE_BUILD_PARALLEL_LEVEL:-4}"
export UV_PROJECT_ENVIRONMENT="$HOME/ck-venv" UV_PYTHON=3.12 CKIT_CORE_DIR="$CI/core-py/py"
mkdir -p "$CI/logs"

need_submodules() {
  local f
  for f in eigen/Eigen/Core sophus/sophus/se3.hpp mcap/cpp/mcap/include/mcap/mcap.hpp nanobind/CMakeLists.txt; do
    [ -e "third_party/$f" ] || { echo "third_party/$f missing. Run: git submodule update --init --recursive" >&2; return 1; }
  done
}

py_sync() { (cd tools && uv sync --locked --extra dev); }
py_core() { PYTHON="$UV_PROJECT_ENVIRONMENT/bin/python" BUILD_DIR="$CI/core-py" scripts/build_core_py.sh; }

job_core() {
  need_submodules
  cmake -S core -B "$CI/core" -DCMAKE_BUILD_TYPE=Release
  cmake --build "$CI/core" --parallel
  ctest --test-dir "$CI/core" --output-on-failure
}

job_sanitizers() {
  need_submodules
  cmake -S core -B "$CI/asan" -DCMAKE_BUILD_TYPE=RelWithDebInfo \
    -DCMAKE_CXX_FLAGS="-fsanitize=address,undefined -fno-omit-frame-pointer" \
    -DCMAKE_EXE_LINKER_FLAGS="-fsanitize=address,undefined"
  cmake --build "$CI/asan" --parallel --target capturekit_core_tests
  UBSAN_OPTIONS=halt_on_error=1 "$CI/asan/tests/capturekit_core_tests"
  cmake -S core -B "$CI/tsan" -DCMAKE_BUILD_TYPE=RelWithDebInfo \
    -DCMAKE_CXX_FLAGS="-fsanitize=thread" -DCMAKE_EXE_LINKER_FLAGS="-fsanitize=thread"
  cmake --build "$CI/tsan" --parallel --target capturekit_core_tests
  TSAN_OPTIONS=halt_on_error=1 "$CI/tsan/tests/capturekit_core_tests" --gtest_filter='Session*' --gtest_repeat=5
}

job_python() {
  need_submodules
  py_sync
  py_core
  cd tools
  uv run ruff check .
  uv run ruff format --check .
  uv run pytest
  uv run ckit --version
}

job_golden() {
  need_submodules
  py_sync
  compgen -G "$CKIT_CORE_DIR/_ckcore*.so" >/dev/null || py_core
  CKIT="$UV_PROJECT_ENVIRONMENT/bin/ckit" timeout 300 scripts/replay_golden.sh
}

job_schema() {
  py_sync
  local tmp
  tmp="$(mktemp -d)"
  trap "rm -rf '$tmp'" EXIT
  (
    cd spec/scanbundle/examples/frozen-1.0.0
    test -f manifest.json || { echo "frozen-1.0.0 fixture missing (see its README.md)"; exit 1; }
    sha256sum --check --strict SHA256SUMS
    find . -type f ! -name README.md ! -name .gitattributes ! -name SHA256SUMS | sed 's|^\./||' | sort > "$tmp/actual.txt"
    awk '{print $2}' SHA256SUMS | sed 's|^\*||;s|^\./||' | sort > "$tmp/listed.txt"
    diff "$tmp/listed.txt" "$tmp/actual.txt" || { echo "fixture files differ from SHA256SUMS"; exit 1; }
    grep -q '"schema_version": *"1\.0\.0"' manifest.json
  )
  cd tools
  uv run ckit validate ../spec/scanbundle/examples/frozen-1.0.0 --level semantic
}

job_actionlint() { actionlint ../.github/workflows/ci.yml; }

declare -a ROWS=()
fail=0
run_job() {
  local name="$1" rc log="$CI/logs/$1.log"
  echo "=== $name ==="
  set +e
  ( set -e; "job_$name" ) 2>&1 | tee "$log"
  rc=${PIPESTATUS[0]}
  set -e
  if [ "$rc" -eq 0 ]; then
    ROWS+=("$name PASS")
  else
    ROWS+=("$name FAIL")
    fail=1
    echo "--- $name failed (rc=$rc), last lines of $log:"
    tail -n 15 "$log"
  fi
}

if command -v actionlint >/dev/null; then run_job actionlint; fi
IFS=, read -ra WANT <<<"$JOBS"
for j in "${WANT[@]}"; do
  case "$j" in
    core|sanitizers|python|schema|golden) run_job "$j" ;;
    *) echo "unknown job: $j" >&2; exit 2 ;;
  esac
done
ROWS+=("upload SKIP(needs-docker)" "ios SKIP(needs-macOS)" "android SKIP(run-on-Windows)")

echo "=== summary ==="
summary=""
for r in "${ROWS[@]}"; do summary+="$r | "; done
echo "${summary% | }"
exit "$fail"
