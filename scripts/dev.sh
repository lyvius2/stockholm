#!/usr/bin/env bash
# 데몬(engine) + Electron 개발 실행. 실제 구현은 Gradle `dev` 작업(backend/build.gradle.kts)에 있음.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec "$ROOT/backend/gradlew" -p "$ROOT/backend" dev "$@"
