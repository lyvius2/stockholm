#!/usr/bin/env bash
# 로컬 SQLite 스키마·데이터 덤프(디버그용). 덤프에는 매매 기록이 들어가므로 공유하지 말 것. 비밀값은 DB에 없음(Keychain).
set -euo pipefail
DATA_DIR="${STOCKHOLM_DATA_DIR:-$HOME/Library/Application Support/Stockholm}"
DB="${1:-$DATA_DIR/engine.db}"
OUT="${2:-$(mktemp -t stockholm-dump).sql}"
sqlite3 "$DB" .dump > "$OUT"
echo "dumped: $OUT"
