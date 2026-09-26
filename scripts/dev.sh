#!/usr/bin/env bash
# 개발 실행: 데몬(engine 프로필)을 띄우고 Electron 개발 서버를 연다. 둘 다 이 셸을 끄면 함께 내려감.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

(cd "$ROOT/backend" && ./gradlew bootRun --args='--spring.profiles.active=engine' --quiet) &
DAEMON_PID=$!
trap 'kill "$DAEMON_PID" 2>/dev/null || true' EXIT

npm --prefix "$ROOT/desktop" run dev
