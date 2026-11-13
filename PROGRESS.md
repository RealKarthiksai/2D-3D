# PROGRESS

## Done
- Scaffolding v0.0.1: builds + CI green (Linux ctest, ruff+pytest, Android Gradle, macOS swift test). (362a839)
- Agent setup: AGENTS.md, CLAUDE.md, docs/phases/00–09.

## Decisions
- capturekit/docs/decisions/0001-jpeg-keyframes-not-video.md

## Commands
- Bootstrap: `cd capturekit && ./scripts/bootstrap.sh`
- C++: `cmake -S capturekit/core -B capturekit/build/core && cmake --build capturekit/build/core && ctest --test-dir capturekit/build/core`
- Python: `cd capturekit/tools && uv run pytest -q && uv run ruff check`
- Android: `cd capturekit/android && ./gradlew assembleDebug testDebugUnitTest`
- Dev server: `cd capturekit/dev-server && docker compose up -d && ./smoke_test.sh`

## Gotchas
- Spec docs live in docs/program-spec/room-scanner-program/, not docs/program-spec/.
- iOS builds need macOS; this Linux machine can't run swift test.

## Next
- Phase 0: conventions.md + RawRecorders + golden recordings.
