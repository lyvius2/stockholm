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
  dir="$(basename "$(dirname "$schema")")"
  # 밑줄로 시작하는 파일은 $defs 만 있는 공용 정의라 타입을 만들지 않음(다른 스키마가 $ref 로 씀)
  case "$name" in _*) continue ;; esac
  pascal="$(echo "$name" | perl -pe 's/(^|-)([a-z])/\u$2/g')"
  # Kotlin 은 데몬이 payload 로 쓰는 common·events 만 만듦. api 응답은 컨트롤러 DTO 가 원본이라 TS 만 생성함.
  # quicktype 의 Kotlin 출력은 파일마다 mapper·enum 을 다시 선언하므로 스키마 폴더별 하위 패키지에 둠.
  case "$dir" in
    common|events)
      mkdir -p "$KOTLIN_OUT/$dir"
      $QUICKTYPE --src-lang schema --src "$schema" --lang kotlin --framework jackson --acronym-style original \
        --package "banghak.stock.shared.protocol.$dir" --top-level "$pascal" --out "$KOTLIN_OUT/$dir/$pascal.kt"
      # quicktype의 Jackson 템플릿이 2.12에서 사라진 PropertyNamingStrategy 상수를 쓰므로 새 이름으로 바꿈
      sed -i '' -e 's/PropertyNamingStrategy\.LOWER_CAMEL_CASE/PropertyNamingStrategies.LOWER_CAMEL_CASE/g' \
        -e 's/^import com\.fasterxml\.jackson\.databind\.PropertyNamingStrategy$/import com.fasterxml.jackson.databind.PropertyNamingStrategies/' \
        "$KOTLIN_OUT/$dir/$pascal.kt"
      # 생성 코드의 deprecated 호출이 -Werror 빌드를 막지 않게 파일 단위로 억제함
      sed -i '' -e "s/^package banghak.stock.shared.protocol.$dir$/@file:Suppress(\"DEPRECATION\")\n\npackage banghak.stock.shared.protocol.$dir/" "$KOTLIN_OUT/$dir/$pascal.kt"
      ;;
  esac
  # date-time 을 Date 로 만들면 런타임(JSON 문자열)과 어긋나므로 문자열 그대로 둠
  $QUICKTYPE --src-lang schema --src "$schema" --lang typescript --just-types --acronym-style original \
    --no-date-times --top-level "$pascal" --out "$TS_OUT/$name.ts"
  echo "generated: $pascal"
done
