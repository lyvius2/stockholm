# protocol — 데몬·셸·relay가 주고받는 메시지의 단일 정의

JSON Schema(draft 2020-12)가 원본이고, Kotlin·TypeScript 타입은 `generate.sh`가 quicktype으로 만든다. **양쪽 타입을 손으로 따로 쓰지 않는다.** 생성물은 커밋하지 않는다(`backend/build/generated/protocol/kotlin`, `desktop/src/renderer/generated`).

## 규약

- 금액·수량·환율은 **문자열**(decimal). 화면은 표시만 하고 계산은 데몬이 한다.
- 시각은 ISO-8601 UTC 문자열(`2026-09-26T00:00:00Z`).
- enum 값은 Kotlin enum 이름과 같은 대문자 스네이크(`COMMAND`, `AUTO_BUY`).
- 비밀값(키·토큰·계좌번호)은 어떤 스키마에도 두지 않는다. 봉투의 `body`는 항상 객체이며, relay를 지나는 `SYNC`는 `{ "ciphertext": "<base64>" }` 한 필드만 갖는다(코드 생성기가 무형 값을 받지 못해 객체로 고정, 2026-09-26).

## 폴더

| 폴더 | 내용 |
|---|---|
| `schemas/common/` | 봉투(`envelope`), 식별자, 금액 등 공통 정의 |
| `schemas/events/` | 이벤트 로그 payload(동기화 대상) |
| `schemas/api/` | 로컬 REST/WebSocket 요청·응답 |
| `schemas/relay/` | relay 경유 메시지(7단계) |

## 생성

```bash
./protocol/generate.sh          # Kotlin + TypeScript 동시 생성
```
