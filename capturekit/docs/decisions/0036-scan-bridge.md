# 0036 Scan bridge: one-zip upload, access key, host-side job runner (issue #10 stage 2)

Status: accepted. A finished scan goes up as one `archive=1` tus upload; the verifier checks the whole zip
(sha256 + `verify_archive`) and serves it back. A host process `dev-server/bridge/bridge.py` (stdlib only, it
needs roomscan-recon's venv and GPU) pulls it, extracts it and runs roomscan-recon only as an external
process; there is no API inside roomscan-recon and no bundle converter in this repo (adapter = roomscan-recon
T-028). One shared access key `X-CK-Key` (tusd `pre-create` hook + every verifier/bridge endpoint); empty key =
auth off for CI. The room summary is copied or computed only from roomscan-recon's room.json/quality.json.
