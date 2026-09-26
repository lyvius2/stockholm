#!/usr/bin/env bash
# JSON Schema → Kotlin(backend) + TypeScript(desktop) 타입 생성. 생성물은 커밋하지 않음.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCHEMAS="$ROOT/protocol/schemas"
KOTLIN_OUT="$ROOT/backend/build/generated/protocol/kotlin/banghak/stock/shared/protocol"
TS_OUT="$ROOT/desktop/src/renderer/generated"
QUICKTYPE="npx --yes quicktype@26"

mkdir -p "$KOTLIN_OUT" "$TS_OUT"
find "$KOTLIN_OUT" "$TS_OUT" -type f ! -name .gitkeep -delete

for schema in $(find "$SCHEMAS" -name '*.schema.json' | sort); do
  name="$(basename "$schema" .schema.json)"
  pascal="$(echo "$name" | perl -pe 's/(^|-)([a-z])/\u$2/g')"
  $QUICKTYPE --src-lang schema --src "$schema" --lang kotlin --framework jackson --acronym-style original \
    --package banghak.stock.shared.protocol --top-level "$pascal" --out "$KOTLIN_OUT/$pascal.kt"
  # quicktype의 Jackson 템플릿이 2.12에서 사라진 PropertyNamingStrategy 상수를 쓰므로 새 이름으로 바꿈
  sed -i '' -e 's/PropertyNamingStrategy\.LOWER_CAMEL_CASE/PropertyNamingStrategies.LOWER_CAMEL_CASE/g' \
    -e 's/^import com\.fasterxml\.jackson\.databind\.PropertyNamingStrategy$/import com.fasterxml.jackson.databind.PropertyNamingStrategies/' \
    "$KOTLIN_OUT/$pascal.kt"
  # 생성 코드의 deprecated 호출이 -Werror 빌드를 막지 않게 파일 단위로 억제함
  sed -i '' -e "s/^package banghak.stock.shared.protocol$/@file:Suppress(\"DEPRECATION\")\n\npackage banghak.stock.shared.protocol/" "$KOTLIN_OUT/$pascal.kt"
  $QUICKTYPE --src-lang schema --src "$schema" --lang typescript --just-types \
    --top-level "$pascal" --out "$TS_OUT/$name.ts"
  echo "generated: $pascal"
done
