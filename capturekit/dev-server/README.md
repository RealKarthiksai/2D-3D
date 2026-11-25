# dev-server

Local resumable-upload stack for SDK development: **tusd** (tus protocol, port 1080)
storing into **MinIO** (S3 API on 9000, console on 9001). The `scanbundles` bucket is
created on startup by the one-shot `minio-init` service. Dev credentials only
(`capturekit` / `capturekit-dev-only`; override via `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`).

```sh
cd dev-server
docker compose up -d          # start
docker compose ps -a          # minio + tusd running, minio-init exited (0)
docker compose logs -f tusd   # follow tusd
docker compose down           # stop (keeps data volume)
docker compose down -v        # stop and wipe stored uploads
```

## Smoke test

`./smoke_test.sh` creates an upload, PATCHes 16 bytes and checks `Upload-Offset`. Manually:

```sh
curl -i -X POST http://localhost:1080/files/ \
  -H "Tus-Resumable: 1.0.0" -H "Upload-Length: 5"
# -> 201 Created, Location: http://localhost:1080/files/<id>
curl -i -X PATCH http://localhost:1080/files/<id> \
  -H "Tus-Resumable: 1.0.0" -H "Upload-Offset: 0" \
  -H "Content-Type: application/offset+octet-stream" --data-binary "hello"
# -> 204 No Content, Upload-Offset: 5
```

Uploaded objects are visible in the MinIO console at http://localhost:9001 (bucket `scanbundles`).

Phones on the LAN: point the SDK at `http://<your-machine-ip>:1080/files/`.

## Verifier (Phase 7)

`verifier` (port 8081) marks a bundle by what is actually in the bucket (never by hook events, so a missed
hook or restart cannot strand it): `PENDING` (no manifest yet), `INCOMPLETE` (manifest in, files missing),
`CORRUPT` (size/sha256 differ from the manifest), `COMPLETE`. tusd calls it on `post-finish`.

```sh
curl localhost:8081/bundles/<bundle_id>       # status JSON incl. missing/corrupt files and the on-device verdict
./smoke.sh                                    # stack + `ckit upload` of a real synthetic bundle -> COMPLETE
./failure_test.sh                             # 8-case failure matrix (airplane mode, kill, restart, truncation, ...)
```

Clients upload one file per tus upload with metadata `bundle_id`, `path`, `sha256`, and `manifest.json` last
(`tools/ckit/upload.py` is the reference; Kotlin and Swift mirror it). `docs/upload-test-protocol.md` lists the
device-only cases.

## Archive upload (one zip per scan)

`ckit upload <scan>.zip` (a zip made by `ckit pack`, ADR 0034) is ONE tus upload with metadata
`bundle_id` (from the zip's `manifest.json`), `path` (the zip's file name), `sha256` (whole file) and
`archive=1`. The verifier then:

- takes the latest complete archive upload of the bundle (an unfinished one is `PENDING`);
- checks the whole-file sha256, then `verify_archive` (CRCs, every listed file's size and sha256, no extra
  entries) on a temporary copy; the result is cached per stored object;
- answers `COMPLETE` (verdict = the manifest's `validation`) or `CORRUPT` with
  `corrupt = [{"path": "<zip name>", "reason": ...}]`; the client re-sends the zip fresh once.

A bundle without any `archive=1` upload is checked file by file as before. Bundle ids must match
`^[A-Za-z0-9_.-]{1,128}$` (and not be `.`/`..`): they end up in paths and URLs.

`GET /bundles/<id>/archive` streams the verified zip (409 unless `COMPLETE`, 404 if the bundle was uploaded
loose). When `BRIDGE_URL` is set, the verifier `POST`s `<BRIDGE_URL>/jobs/<id>?retry=1` once an archive upload is
`COMPLETE` (so a job that failed earlier, e.g. while the zip was still pending, runs again; errors are only logged; the same `POST` by hand does it too).

**Access key.** With `CK_ACCESS_KEY` set (compose passes it to the verifier), tusd rejects `pre-create` without
header `X-CK-Key: <key>` (HTTP 401) and every verifier endpoint except `/healthz` answers 401 without it.
Empty (the default, CI and the failure matrix) = auth off. One shared key for the phone, `ckit` (`--key` or env
`CK_ACCESS_KEY`) and the bridge. Plain HTTP on the LAN: the key is not secret on the wire; use Tailscale
(below) outside a trusted Wi-Fi.

## Bridge API (scan bridge, `bridge/`)

Part 1 -> Part 2 link. `bridge/bridge.py` is a host process (Python >= 3.11, stdlib only; it must reach
roomscan-recon's venv and GPU, containers cannot). It downloads the verified zip from the verifier, extracts it
(unsafe archives are refused), runs the configured steps of roomscan-recon as external processes (no API
inside roomscan-recon) one job at a time, and reads `room.json` / `quality.json` for a summary. Contract used
by the app (`scan-bridge-app`); all but `/healthz` need `X-CK-Key` when a key is set (401 otherwise):

| Request | Response |
|---|---|
| `POST /jobs/<bundle_id>` | idempotent: creates the job (201) or returns the existing one (200); `?retry=1` requeues a `failed` job |
| `GET /jobs/<bundle_id>` | job JSON, or 404 |
| `GET /jobs/<bundle_id>/files/<name>` | raw file, `name` in `room.json`, `quality.json`, `reconstruction.json` (the recon `manifest.json`); 404 if not produced |
| `GET /healthz` | `{"ok": true}` |

400 = bundle id not matching the pattern above; 503 = too many queued jobs.

```json
{"bundle_id": "...", "state": "queued|processing|done|failed", "reason": null,
 "step": "download|extract|spatial|recon|null", "steps_done": 0, "steps_total": 1,
 "created": "2026-11-24T10:00:00+00:00", "started": null, "finished": null, "summary": null}
```

`reason` (failed only) is `"<step> exited <code>: <last non-empty log line>"`, `"timeout after <N>s"`,
`"download failed: ..."`, `"archive rejected: ..."` or `"room.json missing"`. `steps_total` counts the enabled
steps; `download` and `extract` come first. `summary` is `null` until `done`, then (every number nullable,
copied or computed only from roomscan-recon's `room.json`, `quality.json` and reconstruction manifest, never
guessed):

```json
{"floor_area_m2": 12.0, "ceiling_height_m": 2.5, "wall_count": 4, "floor_dims_m": [4.0, 3.0],
 "floor_polygon_source": "wall_intersections", "scale_source": "metric_priors", "scale_confidence": 0.8,
 "warnings": [], "notes": [], "outputs": {"mesh": {"status": "skipped", "reason": "..."}}}
```

`floor_dims_m` = `[long, short]` side of the minimum-area rectangle around the floor polygon (`null` for fewer
than 3 points or source `none`); `wall_count` = number of `wall_ids`; `outputs` = per-product
`{status, reason}` from the reconstruction manifest, `null` when the `recon` step did not run.

Job data lives in `<work_dir>/jobs/<id>/` (`job.json`, `bundle.zip`, `bundle/`, `run/`, `logs/<step>.log`) and is
never deleted by the bridge. After a restart, jobs that were queued or running are queued again.

## Laptop runbook (Windows + NVIDIA GPU, same Wi-Fi as the phone)

1. Install Docker Desktop. In `dev-server/` set three variables (cmd: `set NAME=value`; PowerShell:
   `$env:NAME = "value"`): `CK_ACCESS_KEY=<long random key>`, `BRIDGE_URL=http://host.docker.internal:8082`
   and **`MINIO_ROOT_PASSWORD=<another long random password>`**: the repo default (`capturekit-dev-only`)
   is public, so never keep it on a laptop that scans are stored on. Then `docker compose up -d --build`.
   MinIO (9000/9001) is published on 127.0.0.1 only; only tusd (1080) and the verifier (8081) face the LAN.
2. Windows firewall: allow inbound TCP 1080 (tus), 8081 (verifier) and 8082 (bridge) on the **private**
   network profile only.
3. `copy bridge\bridge.example.json bridge.json`; edit `work_dir`, `recon_dir` (the roomscan-recon checkout),
   `python` (that checkout's venv python, `.venv/Scripts/python.exe`: not `uv run`, which may re-sync and drop
   the GPU torch build) and leave `access_key` empty to read it from `CK_ACCESS_KEY`. Use forward slashes.
   The `recon` step is disabled by default (reconstruction needs roomscan-recon T-003 on Windows).
4. Start the bridge from a shell prepared like roomscan-recon's `run_gpu.bat` (its GPU steps need the MSVC
   and CUDA toolchain): in cmd, `call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"`
   (or open "x64 Native Tools Command Prompt"), `set "PATH=<CUDA>\bin;%PATH%"` (e.g.
   `C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA\v12.8\bin`), then
   `<recon venv python> bridge\bridge.py --config bridge.json`. Steps inherit this environment.
   The bridge refuses to start without a key (`--no-key` is for `smoke.sh` only).
5. From another PC or the phone: tus endpoint `http://<laptop LAN IP>:1080/files/`, status
   `http://<laptop>:8082/jobs/<bundle_id>`. Check: `ckit upload scan.zip --server http://<laptop>:1080/files/
   --verifier http://<laptop>:8081 --key <key> --consent`, then
   `curl -H "X-CK-Key: <key>" http://<laptop>:8082/jobs/<bundle_id>`.

Until roomscan-recon reads CaptureKit 1.4.0 bundles (a CaptureKit-bundle adapter, task T-028 there), a real
run ends `failed` with roomscan-recon's own error line as `reason`; that is expected. Nothing here converts
bundles.

**Away from home Wi-Fi:** install Tailscale on the laptop and the phone and use the laptop's tailnet address
(`100.x.y.z`) instead of the LAN IP; the ports stay the same and nothing is exposed to the internet.

`bridge/fake_recon.py` is a test-only stand-in for roomscan-recon (tests, `smoke.sh`); never configure it on the laptop.
