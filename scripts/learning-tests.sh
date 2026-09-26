#!/usr/bin/env bash
# 외부 API 학습 테스트(@Tag("learning")). 키는 macOS Keychain 의 `stockholm-dev` 항목에서 꺼내 이 프로세스의 환경 변수로만 넘김.
#
# 키 넣기(값은 프롬프트로 입력, 셸 히스토리에 남지 않음):
#   security add-generic-password -a stockholm-dev -s DART_API_KEY -U -w
# 키 지우기:
#   security delete-generic-password -a stockholm-dev -s DART_API_KEY
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ACCOUNT="stockholm-dev"
NAMES=(TOSS_CLIENT_ID TOSS_CLIENT_SECRET DART_API_KEY FRED_API_KEY KRX_API_KEY MASSIVE_API_KEY NAVER_CLIENT_ID NAVER_CLIENT_SECRET ODCLOUD_API_KEY OPENAI_API_KEY ANTHROPIC_API_KEY DEEPSEEK_API_KEY)

for name in "${NAMES[@]}"; do
  if value="$(security find-generic-password -a "$ACCOUNT" -s "$name" -w 2>/dev/null)"; then
    export "STOCKHOLM_TEST_$name=$value"
    echo "키 주입: $name"
  fi
done
unset value

cd "$ROOT/backend" && ./gradlew learningTest "$@"
