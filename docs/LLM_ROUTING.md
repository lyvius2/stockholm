# 목적별 다중 LLM 설계

> 문서 지도: [docs/README.md](README.md) · 기준 문서: [PROJECT.md](../PROJECT.md) · 작업 규칙: [CLAUDE.md](../CLAUDE.md) · 개발 순서: [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md)

작성: 2026-09-20 / 상태: 초안 v1
관련: [`PROJECT.md`](../PROJECT.md) 8장·9장·10.4, [`docs/RAG_DESIGN.md`](RAG_DESIGN.md), [`docs/EXTERNAL_APIS.md`](EXTERNAL_APIS.md) 1.4

## 1. 확정 사항과 이 문서의 범위

- **LLM은 목적별로 나눠 여러 개를 쓴다.** [확정]
- **지원 제공자: OpenAI, Claude(Anthropic), Ollama(로컬), DeepSeek.** [확정]
- 이 문서는 목적의 분류, 제공자별 배치 기준, 라우팅·폴백·예산·개인정보 처리 방식을 정한다. 아래 배치안과 세부 규칙은 [제안]이다.

**모델 이름은 코드에 쓰지 않는다.** 모델은 몇 달마다 바뀌므로 코드는 "목적"만 알고, 목적 → 제공자·모델의 매핑은 전부 사용자 설정(동기화 대상)에 둔다.

## 2. 제공자별 특성과 역할

| 제공자 | 강점 | 약점·주의 | 맡기기 좋은 일 |
|---|---|---|---|
| **Claude** | 긴 문맥에서의 추론과 종합, 지시 준수, 한국어 장문 품질 | 비용이 높은 편 | 토론 사회자(종합·결론), 시장 리포트, 회고 작성 |
| **OpenAI** | JSON 스키마를 엄격히 지키는 구조화 출력, 범용성, 임베딩 API | 비용 중상 | 구조화 추출(결론 → 스키마), 자동 매도 판단처럼 형식 오류가 허용되지 않는 곳, 페르소나 일부 |
| **DeepSeek** | 매우 낮은 단가, 추론 모델 제공 | 응답 지연·가용성 편차, 추론 모델은 구조화 출력·도구 호출에 제약이 있을 수 있음, 데이터 처리 위치·정책이 다른 제공자와 다름 | 호출 수가 많은 페르소나 발언, 추천 2단의 대량 분석, 뉴스 대량 요약 |
| **Ollama** | 비용 0, 자료가 밖으로 나가지 않음, 네트워크 왕복이 없어 짧은 작업에 빠름 | 모델 크기 한계(32GB Mac mini에서 8B~14B급이 현실적), 밖의 Mac에서는 안 떠 있을 수 있음 | 급등 재료 확인, 뉴스 분류·태깅·중복 판정, 임베딩, 개인 데이터가 들어간 작업 |

Spring AI가 네 제공자 모두의 채팅 모델 구현을 제공한다(DeepSeek는 전용 스타터 `spring-ai-starter-model-deepseek`가 있고, OpenAI 호환 엔드포인트로도 붙는다). [확인함]

## 3. 목적 분류

코드에는 아래 enum만 존재한다. 각 목적은 요구 특성이 다르다.

| 목적 (`LlmPurpose`) | 쓰이는 곳 | 핵심 요구 | 지연 예산 | 개인 데이터 |
|---|---|---|---|---|
| `PERSONA_TURN` | F6 페르소나 발언 (라운드 × 인원) | 다양성, 호출 수 많음 → 단가 | 30~60초 | 없음(기본) |
| `DEBATE_MODERATOR` | F6 사회자: 종합, 결론, 반대 논거 정리 | 추론·종합 품질 | 60초 | 없음(기본) |
| `STRUCTURED_EXTRACTION` | 자유 서술 → `Verdict` 등 스키마 변환 | **형식 정확성** | 15초 | 없음 |
| `RECOMMENDATION_ANALYSIS` | F5 2단 후보 분석 (수십 종목) | 단가, 병렬성 | 60초 | 없음 |
| `MARKET_REPORT` | F9 일간·주간 리포트 | 장문 품질 | 120초 | 보유·관심 종목 포함 시 있음 |
| `SELL_DECISION` | F8 자동 매도 판단 | 신중함, 형식 정확성, 재현성 | 30초 | **있음**(보유 단가·수량) |
| `SURGE_CATALYST_CHECK` | F7 급등 재료 확인 | **속도** | 3~5초 | 없음 |
| `DOCUMENT_DIGEST` | 공시·뉴스 요약, 분류, 태깅, 악재 유형 판정 보조 | 대량 처리 단가 | 20초 | 없음 |
| `RETROSPECTIVE` | F10 회고 작성 | 분석 품질 | 120초 | **있음**(매매 결과) |
| `USER_CHAT` | 종목 질의응답 | 대화 품질, 스트리밍 | 첫 토큰 2초 | 상황에 따라 |
| `TRANSLATE` | F12 커뮤니티 게시글·댓글 영→한 번역 (제목은 배치, 본문은 펼칠 때) | 투자 은어 처리, 대량 단가 | 10초(배치 30건) | 없음(공개 글) |

`PERSONA_TURN`은 **페르소나별로 다른 경로를 지정**할 수 있다(가치투자자=Claude, 모멘텀=DeepSeek, 리스크 매니저=OpenAI 식). 임베딩은 채팅과 성격이 달라 별도 포트(`EmbeddingPort`)로 둔다([`docs/RAG_DESIGN.md`](RAG_DESIGN.md)).

## 4. 기본 배치안 (프리셋)

사용자는 목적별 경로를 직접 고칠 수 있고, 아래 프리셋 중 하나로 시작한다. 각 칸은 "1순위 → 폴백".

| 목적 | 균형(기본) | 품질 우선 | 비용 최소 | 로컬 전용 |
|---|---|---|---|---|
| `PERSONA_TURN` | 페르소나별 혼합(Claude·OpenAI·DeepSeek) | Claude·OpenAI 혼합 | DeepSeek → Ollama | Ollama |
| `DEBATE_MODERATOR` | Claude → OpenAI | Claude 상위 모델 → OpenAI | DeepSeek 추론 모델 → Claude | Ollama |
| `STRUCTURED_EXTRACTION` | OpenAI → Claude | OpenAI → Claude | OpenAI 소형 → Ollama | Ollama |
| `RECOMMENDATION_ANALYSIS` | DeepSeek → OpenAI 소형 | Claude → OpenAI | DeepSeek → Ollama | Ollama |
| `MARKET_REPORT` | Claude → OpenAI | Claude 상위 모델 | DeepSeek → Claude | Ollama |
| `SELL_DECISION` | OpenAI → Claude → **판단 보류** | Claude → OpenAI → 판단 보류 | OpenAI 소형 → 판단 보류 | Ollama → 판단 보류 |
| `SURGE_CATALYST_CHECK` | Ollama → OpenAI 소형 → **매수 안 함** | OpenAI 소형 → Ollama → 매수 안 함 | Ollama → 매수 안 함 | Ollama → 매수 안 함 |
| `DOCUMENT_DIGEST` | Ollama → DeepSeek | OpenAI 소형 → Ollama | Ollama → DeepSeek | Ollama |
| `RETROSPECTIVE` | Claude → OpenAI | Claude 상위 모델 | DeepSeek → Claude | Ollama |
| `USER_CHAT` | Claude → OpenAI | Claude → OpenAI | DeepSeek → Ollama | Ollama |
| `TRANSLATE` | Ollama → DeepSeek | OpenAI 소형 → Ollama | Ollama → DeepSeek | Ollama |

키가 등록되지 않은 제공자는 경로에서 자동으로 빠진다. 한 제공자만 등록해도 전 기능이 동작해야 한다.

## 5. 여러 모델을 섞는 것의 이득과 함정

**이득.** 같은 모델에 페르소나만 다르게 입히면 발언이 비슷한 방향으로 쏠리기 쉽다. 제공자를 섞으면 학습 데이터와 성향이 달라 **오류가 덜 상관**되고, 토론이 실제로 갈린다. 이것이 다중 LLM의 가장 큰 실익이다.

**함정 1 — 학습(F10)의 교란.** 페르소나별 적중률에는 "페르소나의 관점"과 "그 뒤의 모델 능력"이 섞인다. 그래서 (1) 모든 발언·결론·추천 레코드에 `provider`, `model`, 프롬프트 템플릿 버전, 주요 파라미터를 저장하고, (2) 페르소나 성적은 **(페르소나, 모델) 쌍 단위**로 집계하며, (3) 모델을 바꾸면 해당 쌍의 보정 가중치는 초기값에서 다시 시작한다.

**함정 2 — 추론 모델과 구조화 출력.** 추론 특화 모델은 JSON 스키마 강제나 도구 호출에 제약이 있을 수 있다. 그래서 **"생각하는 호출"과 "형식을 맞추는 호출"을 분리**한다. 페르소나·사회자는 자유 서술로 답하고, 별도의 `STRUCTURED_EXTRACTION` 호출이 스키마로 옮긴다. 제공자마다 구조화 방식이 달라도(OpenAI의 엄격한 JSON 스키마, Claude의 도구 기반 출력, DeepSeek의 JSON 모드, Ollama의 format 지정) 상위 로직은 영향을 받지 않는다.

**함정 3 — 어떤 제공자도 형식을 100% 보장하지 않는다.** 모든 구조화 응답은 우리 코드에서 스키마 검증을 하고, 실패 시 오류 내용을 붙여 1회만 재요청한다. 그래도 실패하면 그 목적의 실패 정책(7장)을 따른다.

## 6. 구조

```
core.port
  LlmPort            complete(LlmRequest) / stream(LlmRequest)
  LlmRequest         purpose, personaId?, messages, responseSchema?, sensitivity, asOf
  LlmResponse        text | structured, provider, model, usage(tokens), latency, routeTrace

engine.llm
  LlmRouter          purpose(+personaId) → 경로 해석 → 호출 → 폴백 → 기록
  RouteTable         사용자 설정에서 로드 (동기화 대상, 디바이스별 덮어쓰기 가능)
  ProviderRegistry   제공자별 ChatModel 생성·보관·헬스 체크
  provider/
    ClaudeProvider   OpenAiProvider   DeepSeekProvider   OllamaProvider
  SchemaGuard        응답 스키마 검증, 1회 복구 재요청
  BudgetGuard        사용자별 일일 예산 검사
  UsageLedger        호출 기록 (JPA)
```

- 상위 코드(토론 엔진, 추천, 리포트)는 `LlmPort`와 `LlmPurpose`만 안다. 제공자 이름이 `engine.llm` 밖에 나타나면 안 된다(ArchUnit으로 검사).
- **API 키는 Keychain에 있고 실행 중에 등록·교체된다.** Spring AI의 자동 설정은 `application.yml`의 키를 전제로 하므로 쓰지 않는다. `ProviderRegistry`가 키 등록 시점에 각 제공자의 `ChatModel`을 빌더로 직접 만들고 교체한다. **LLM 키는 admin이 입력해 가족이 공유한다(PROJECT.md D17).** 따라서 제공자는 사용자별이 아니라 설치 단위로 보관한다. 호출 기록과 개인정보 규칙은 여전히 `userId` 단위로 적용한다.
- Ollama는 키가 없는 대신 주소(`http://127.0.0.1:11434` 기본)와 설치된 모델 목록을 헬스 체크로 확인한다.
- 같은 라운드의 페르소나 발언은 가상 스레드로 병렬 호출한다. 제공자별 동시 호출 수 상한과 rate limiter를 둔다.
- **프롬프트 캐싱을 고려한 프롬프트 구조.** 여러 라운드 토론에서는 "페르소나 정의 + 시드 자료"가 매번 반복된다. 이 부분을 프롬프트 앞쪽에 고정된 형태로 두면 제공자의 프롬프트 캐싱으로 비용과 지연이 크게 준다. 프롬프트 빌더는 "고정 접두부 / 가변 부분"을 구분해 조립한다.
- 목적별 기본 파라미터: 추출·매도 판단은 temperature 낮게, 페르소나는 중간, 타임아웃은 3장의 지연 예산.

## 7. 폴백과 실패 정책

폴백은 경로에 적힌 순서대로 시도한다. 전환 사유는 타임아웃, 5xx, 429(Retry-After가 지연 예산을 넘는 경우), 스키마 복구 실패다. 4xx 인증 오류는 폴백하되 사용자에게 키 점검 알림을 보낸다.

**돈이 걸린 목적은 실패 시 안전한 쪽으로 멈춘다.**

| 목적 | 모든 경로 실패 시 |
|---|---|
| `SURGE_CATALYST_CHECK` | **매수하지 않는다** |
| `SELL_DECISION` | **판단 보류**(매도하지 않음) + Slack 알림. 토스에 걸어 둔 조건주문(손절)은 LLM과 무관하게 작동 |
| `STRUCTURED_EXTRACTION` | 결론을 "구조화 실패"로 기록, 자동화 입력으로 쓰지 않음 |
| `PERSONA_TURN` | 해당 페르소나를 이번 토론에서 제외하고 그 사실을 결론에 명시. 과반이 빠지면 토론 무효 |
| `TRANSLATE` | 원문을 그대로 보여주고 "번역 안 됨" 표시. 재시도는 사용자가 펼칠 때 |
| 그 외 | 재시도 큐에 넣고 사용자에게 지연 안내 |

**폴백은 개인정보 규칙을 넘지 못한다.** 개인 데이터가 든 요청이 "개인 데이터 허용" 제공자에서 모두 실패하면, 허용되지 않은 제공자로 넘어가지 않고 실패 처리한다.

## 8. 개인정보 등급

요청에는 `sensitivity`가 붙는다. `PUBLIC`은 공시·뉴스·시세 같은 공개 자료만 포함, `PERSONAL`은 보유 종목·수량·단가·매매 일지·회고 등 개인 자료를 포함한다.

- 제공자마다 **"개인 데이터 전송 허용"** 스위치를 사용자별로 둔다(키는 공유하지만 자기 자료를 어디로 보낼지는 본인이 정한다). Ollama는 기본 허용, 나머지 세 제공자는 **기본 꺼짐**이며 사용자가 각 제공자의 데이터 처리 정책을 확인하고 직접 켠다.
- `PERSONAL` 요청은 허용된 제공자로만 라우팅된다. 허용된 제공자가 하나도 없으면 해당 기능은 개인 자료를 뺀 축약 모드로 동작하거나(예: 시장 리포트에서 보유 종목 절 생략) 비활성화된다.
- 가능한 한 개인 자료를 프롬프트에 넣지 않는 쪽으로 설계한다. 예컨대 매도 판단에는 수량·계좌 정보 없이 "평균 단가 대비 수익률, 보유 일수"만 전달한다.
- 이것은 [`docs/RAG_DESIGN.md`](RAG_DESIGN.md) 12장 3번("개인 코퍼스의 외부 전달 방침")에 대한 답이기도 하다: **사용자별·제공자별 선택.**

## 9. 예산과 사용 기록

- `UsageLedger`에 모든 호출을 기록한다: 사용자, 목적, 페르소나, 제공자, 모델, 입력·출력·캐시 토큰, 추정 비용, 지연, 성공 여부, 폴백 경로. 프롬프트와 응답 전문은 토론 기록 등 업무 데이터로만 남기고 원장에는 넣지 않는다.
- 단가표는 코드가 아니라 **사용자가 고칠 수 있는 설정**이다(가격은 자주 바뀐다). 비용은 "추정"으로 표시한다.
- 키가 공유되므로 비용은 admin 계정으로 청구된다. admin이 **설치 전체 일일 예산과 구성원별 몫**(전체, 목적별)을 정한다. LLM 경로(목적 → 제공자·모델)도 admin이 관리하고, 구성원은 자기 페르소나에 admin이 열어 둔 제공자 중에서 고른다. 80% 도달 시 알림, 초과 시 **등급을 낮춰 계속 동작**한다: 심층 토론·추천 분석·대화를 먼저 멈추고, `SELL_DECISION`과 `SURGE_CATALYST_CHECK`용 예비 예산은 따로 떼어 둔다. 예산 초과로 안전 관련 판단이 멈추는 일은 없어야 한다.
- 화면: 목적별·제공자별 사용량과 비용, 지연, 실패율, 폴백 발생률. 모델을 바꿀 근거 자료가 된다.

## 10. 멀티 디바이스

경로 설정은 동기화되지만 **Ollama의 가용성은 디바이스마다 다르다.** 밖의 Mac에 Ollama가 없으면 라우터는 헬스 체크 결과에 따라 그 경로를 건너뛰고 폴백을 탄다(개인정보 규칙 범위 안에서). 디바이스별 경로 덮어쓰기를 허용해 "노트북에서는 `DOCUMENT_DIGEST`를 DeepSeek로" 같은 설정을 할 수 있게 한다.

## 11. 테스트

- 상위 로직의 단위 테스트는 **가짜 `LlmPort`**(목적별로 정해진 응답을 돌려주는 구현)를 쓴다. 테스트에서 실제 LLM API를 호출하지 않는다.
- 제공자 어댑터는 WireMock으로 계약 테스트: 정상, 스트리밍, 429, 타임아웃, 형식 깨진 JSON.
- 라우터 테스트: 폴백 순서, 키 미등록 제공자 제외, **개인정보 규칙이 폴백을 막는지**, 돈이 걸린 목적의 실패 정책, 예산 초과 시 등급 하향.
- 프롬프트 템플릿은 버전을 붙여 리소스로 관리하고, 템플릿 변경은 F10 집계의 구분 키가 된다.

## 12. 확인이 필요한 사항

1. 기본 프리셋을 "균형"으로 두는 안과 4장의 배치
2. 클라우드 제공자의 "개인 데이터 전송 허용"을 기본 꺼짐으로 두는 안 (8장)
3. ~~가족 구성원이 각자 LLM 키를 등록하는지~~ → **확정: admin이 최초 구동 시 입력한 키를 가족이 공유한다.** 상세 [`docs/KEY_MANAGEMENT.md`](KEY_MANAGEMENT.md)
4. 밖의 Mac에서의 Ollama 운용 여부 ([`docs/RAG_DESIGN.md`](RAG_DESIGN.md) 12장 1번과 동일)
