# protocol — 데몬·셸·relay가 주고받는 메시지의 단일 정의

JSON Schema(draft 2020-12)가 원본이고, 타입은 `generate.sh`가 quicktype으로 만든다. 생성물은 커밋하지 않는다(`backend/build/generated/protocol/kotlin`, `desktop/src/renderer/generated`).

- **TypeScript**는 모든 스키마에서 만들고 렌더러는 이것만 쓴다(손으로 중복 정의하지 않는다).
- **Kotlin**은 `common`·`events`(데몬이 payload로 직렬화하는 것)만 만들고 폴더별 하위 패키지(`banghak.stock.shared.protocol.common` …)에 둔다. `api` 응답은 컨트롤러 DTO(Kotlin)가 원본이고 스키마는 그것을 옮겨 적은 것이라, 스키마를 바꾸면 DTO도 같이 고친다(테스트 `AuthApiTest`·`SetupApiTest`가 응답 형태를 검사함).

## 규약

- 금액·수량·환율은 **문자열**(decimal). 화면은 표시만 하고 계산은 데몬이 한다.
- 시각은 ISO-8601 UTC 문자열(`2026-09-26T00:00:00Z`).
- enum 값은 Kotlin enum 이름과 같은 대문자 스네이크(`COMMAND`, `AUTO_BUY`).
- 비밀값(키·토큰·계좌번호)은 어떤 스키마에도 두지 않는다. 봉투의 `body`는 항상 객체이며, relay를 지나는 `SYNC`는 `{ "ciphertext": "<base64>" }` 한 필드만 갖는다(코드 생성기가 무형 값을 받지 못해 객체로 고정, 2026-09-26).

## 폴더

| 폴더 | 내용 |
|---|---|
| `schemas/common/` | 봉투(`envelope`)와 공용 값 정의 `_values`(밑줄 시작 = `$defs` 만 있어 타입을 만들지 않고 `$ref` 로만 씀) |
| `schemas/events/` | 이벤트 로그 payload(동기화 대상) |
| `schemas/api/` | 로컬 REST 요청·응답. 응답은 컨트롤러 DTO 가 원본이고 `engine/adapter/in/web` 의 `*ApiTest` 가 스키마 적합성을 검사함. 경로 대응은 아래 표 |
| `schemas/stream/` | 로컬 WebSocket(`/ws`) 메시지. 화면 → `stream-client-message`(subscribe: 보는 종목 전체 선언), 데몬 → `stream-server-message`(quote·orderBook·liveCandle·feedState, 배열로 묶어 보냄). Kotlin 은 어댑터 DTO(`engine/adapter/in/ws/StreamMessages.kt`)가 원본이고 테스트 `LocalStreamHandlerTest` 가 스키마 적합성을 검사함 |
| `schemas/relay/` | relay 경유 메시지(7단계) |

## 로컬 REST 경로와 스키마

모든 경로는 로컬 토큰 + 세션이 필요하고(`/setup/*`·`/session/login`·`/session/users`·`/session/register`·헬스·`/assets/consent/callback` 제외), 오류 본문은 `api-error`.

| 경로 | 요청 | 응답 |
|---|---|---|
| `GET /setup/state` 등 마법사 | — | `api-setup-state`·`api-credential-check`·`api-totp-enrollment` |
| `POST /session/login`, `GET /session/users`, `GET /me` | — | `api-login`·`api-user` |
| `GET /session/start-stock` | — | `api-start-stock` |
| `PUT /session/last-viewed-stock` | `api-last-viewed-stock` | — |
| `GET /market/chart?market&code&resolution&before&count` | — | `api-chart` |
| `GET /market/movers?market` | — | `api-mover-board` |
| `GET /market/index-ticker` | — | `api-index-ticker`(스트림 `indexTicker` 와 같은 모양) |
| `GET /portfolio/valuation` | — | `api-portfolio-valuation` |
| `GET /orders/ticket?market&code` | — | `api-order-ticket` |
| `POST /orders` | `api-order-request` | `api-order-placement` |
| `POST /orders/{brokerOrderId}/amend` | `api-order-amendment` | `api-order-placement` |
| `POST /orders/{brokerOrderId}/cancel` | — | `api-cancel-placement` |
| `GET /conditional-orders?scope&market&code&cursor` | — | `api-conditional-orders` |
| `POST /conditional-orders`, `POST /conditional-orders/{id}/amend` | `api-conditional-order-request` | `api-conditional-order-placement` |
| `POST /conditional-orders/{id}/cancel` | — | `api-conditional-cancel` |
| `GET /assets` | — | `api-asset-snapshot` |
| `GET /assets/consent` · `DELETE /assets/consent` | — | `api-asset-consent` · — |
| `POST /assets/consent` | — | `api-asset-consent-start` |
| `GET /assets/consent/callback?code&state` | 브라우저(세션 없음, state 로 확인) | HTML |

오류 상태: 잘못된 값 400 · 세션 401 · 가드레일 위반 422(`violations`) · 확인 필요 428(`notes`) · 증권사 거부 422(`tickSize`·`nearestPrices`) · 증권사·시세 못 받음 503.

## 생성

```bash
./protocol/generate.sh          # Kotlin + TypeScript 동시 생성
```

## Changes

| 날짜 | 변경 |
|---|---|
| 2026-09-27 | `schemas/api/*` 7개 추가(setup-state·credential-kind·credential-check·user·login·totp-enrollment·error). Kotlin 생성 범위를 common·events로 한정, TS 이름은 `--acronym-style original` |
| 2026-10-01 | `schemas/stream/` 추가(로컬 WebSocket 메시지) |
| 2026-10-05 | TS 생성에 `--no-date-times`: `instant` 가 `Date` 가 아닌 `string` 으로 나옴(JSON 런타임과 일치) |
| 2026-10-06 | `api-asset-snapshot`·`api-asset-consent`·`api-asset-consent-start` 추가(F16), 콜백 경로 표 |
| 2026-10-05 | `schemas/api/` 15개 추가(차트·급등락·지수·평가·시작 종목·주문·조건주문), `api-error` 에 주문 경로 상세 필드. 로컬 REST 경로 표 |
