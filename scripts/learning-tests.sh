#!/usr/bin/env bash
# 외부 API 학습 테스트(@Tag("learning")). 키는 환경 변수로만 넘김. 주문 엔드포인트는 어떤 학습 테스트도 부르지 않음.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/backend" && ./gradlew learningTest "$@"
