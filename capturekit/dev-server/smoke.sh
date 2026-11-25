#!/usr/bin/env bash
# End-to-end smoke: bring the stack up, push a real (synthetic) Scan Bundle through tus with the
# `ckit upload` client, and require the server to mark it COMPLETE. Then the same bundle as ONE zip,
# and the scan bridge (with the test-only fake_recon instead of roomscan-recon) must take it from
# the verifier to a finished job with a room summary. Used by CI (job "upload").
set -euo pipefail
cd "$(dirname "$0")"
COMPOSE=(docker compose -f "$PWD/docker-compose.yml")  # absolute: the trap may run from another directory
CKIT="${CKIT:-../tools/.venv/bin/ckit}"
PYTHON="${PYTHON:-$(dirname "$CKIT")/python}"
WORK="${WORK:-$(mktemp -d)}"
BRIDGE_PID=""
trap '[ -z "$BRIDGE_PID" ] || kill "$BRIDGE_PID" 2>/dev/null || true; "${COMPOSE[@]}" down -v >/dev/null 2>&1 || true' EXIT

"${COMPOSE[@]}" up -d --build >/dev/null
for i in $(seq 1 60); do
  curl -fsS localhost:8081/healthz >/dev/null 2>&1 && curl -fsS -o /dev/null -X OPTIONS localhost:1080/files/ -H 'Tus-Resumable: 1.0.0' && break
  sleep 1
done
$CKIT synth G7 "$WORK/raw" --duration 3 --seed 0 >/dev/null
$CKIT import-raw "$WORK/raw" "$WORK/src" >/dev/null
$CKIT simulate-capture "$WORK/src" "$WORK/bundle" >/dev/null
$CKIT validate "$WORK/bundle" --level quality | tail -1
$CKIT pack "$WORK/bundle" --out "$WORK/scan.zip" >/dev/null  # before the folder upload adds its state file
$CKIT upload "$WORK/bundle" --consent | tee "$WORK/result.json"
grep -q '"status": "COMPLETE"' "$WORK/result.json" && echo "SMOKE OK"

# --- one-zip upload, then the bridge ---
$CKIT upload "$WORK/scan.zip" --consent | tee "$WORK/zip_result.json"
grep -q '"status": "COMPLETE"' "$WORK/zip_result.json"
BID=$("$PYTHON" -c "import json,sys; print(json.load(open(sys.argv[1]))['bundle_id'])" "$WORK/bundle/manifest.json")
cat > "$WORK/bridge.json" <<JSON
{"listen": "127.0.0.1:8082", "access_key": "", "verifier_url": "http://localhost:8081",
 "work_dir": "$WORK/bridge-work", "recon_dir": "$WORK", "python": "$PYTHON", "dataset": "scanbundle",
 "timeout_s": 120,
 "steps": [{"name": "spatial", "enabled": true,
            "argv": ["{python}", "$PWD/bridge/fake_recon.py", "--src", "{bundle_dir}", "--out", "{run_dir}"]}]}
JSON
"$PYTHON" bridge/bridge.py --config "$WORK/bridge.json" --no-key >"$WORK/bridge.log" 2>&1 &
BRIDGE_PID=$!
for i in $(seq 1 30); do curl -fsS localhost:8082/healthz >/dev/null 2>&1 && break; sleep 1; done
curl -fsS -X POST "localhost:8082/jobs/$BID" >/dev/null
for i in $(seq 1 60); do
  curl -fsS "localhost:8082/jobs/$BID" >"$WORK/job.json"
  grep -Eq '"state": "(done|failed)"' "$WORK/job.json" && break
  sleep 1
done
grep -q '"state": "done"' "$WORK/job.json" || { cat "$WORK/job.json" "$WORK/bridge.log"; exit 1; }
grep -q '"wall_count": 4' "$WORK/job.json"
echo "BRIDGE OK"
