# HANDOFF.md — 세션 인수인계 (Cowork → Claude Code)

갱신: 2026-09-24. 설계 단계(Cowork, claude.ai 프로젝트 "주식 거래 프로그램 프로젝트")를 마치고 개발 단계로 넘어간다. **이 저장소의 문서가 유일한 기준이다.** claude.ai 프로젝트의 `claude/*.md`는 같은 내용의 사본이며, 앞으로는 저장소를 먼저 고친다.

## 지금 상태

- 설계 문서 완료: `PROJECT.md`(기준), `CLAUDE.md`(작업 규칙), `docs/CORE_DOMAIN.md`, `docs/DEBATE_DESIGN.md`, `docs/RAG_DESIGN.md`, `docs/LLM_ROUTING.md`, `docs/EXTERNAL_APIS.md`, `docs/KEY_MANAGEMENT.md`, `docs/MIROFISH_EXPERIMENT_GUIDE.md`.
- 화면 설계: Cowork 아티팩트 "Stockholm 화면 설계" Version 17(HTML 목업), Figma 파일 `sZLAmrVRCfMgkfF7sS1uCx`. Figma 반영은 `figma-plugin/`(개발 플러그인 v2)으로 하며, **실행 결과 대조는 아직 안 됨**.
- 코드는 아직 없다. `backend/`, `desktop/`, `protocol/`은 미생성.

## 다음 작업: 12장 1단계 "리포 골격"

`PROJECT.md` 12장 1단계 순서대로. 착수 전에 `CLAUDE.md`의 절대 규칙과 `docs/CORE_DOMAIN.md`를 읽는다.

1. `backend/` 단일 Spring Boot 프로젝트(Gradle Kotlin DSL, JDK 21 toolchain, 루트 패키지 `banghak.stockholm`, 프로필 `engine`/`relay`), Spotless(google-java-format).
2. 경계 검증 테스트: Spring Modulith 모듈 검증 + ArchUnit(`core`는 프레임워크 import 금지, `engine`↔`relay` 상호 참조 금지, 프로필별 컨텍스트 기동 3종 + `relay`만 켰을 때 주문·증권사·LLM 빈 부재).
3. `core` 값 객체부터 TDD: `Money`, `Quantity`(국내 정수·미국 소수 6자리), `StockCode`, `ClientOrderId`(36자·10분), `AutoBuyExposure`(168시간 경계값). 한도 상수는 `core`에 한곳.
4. 데몬 기동: `127.0.0.1` 바인딩, 로컬 토큰 파일, 헬스 엔드포인트. SQLite(WAL) + Flyway V1.
5. `desktop/` Electron + React + TS 골격(`contextIsolation` 켬), 데몬 기동·접속만.
6. `protocol/` JSON Schema 자리와 양쪽 타입 생성 파이프라인.
7. dmg 산출 스크립트 뼈대(`scripts/dist.sh`), 메모리 실측(`-Xmx384m`).

끝나면 `CLAUDE.md`의 "명령어" 절을 실제 명령으로 갱신한다.

## 사용자 결정 대기 (코드에 영향)

- 미국 소수점 보유분 매도(시장가 매도만 가능 → F1 "지정가만" 예외 여부), 금액(`orderAmount`) 매수 지원 여부, `OPG`/`CLS` 유효 조건의 주문 모달 노출 여부 — `PROJECT.md` 13장.
- 토스 명세 기반 UI/UX 추가안 9묶음(주문 모달 보강, 미체결·주문 관리, 조건주문 빌더, 종목 상태 칩, 세션 표시, 지표·수급, F11 조정, 운영 상태줄, 차트 보정) 중 [확정]으로 올릴 항목.
- 1인 사용 확정 시 인증·키 관리·동기화 범위 축소 여부(현재 문서는 가족 4명 전제).

## 외부 확인 필요 (착수 전)

- 토스: 디바이스별 client 발급 가능 여부, 밖의 Mac IP 허용 해법.
- 금융결제원 키 종류(테스트베드/운영), 자산 조회 범위.
- 미러피시 사전 실험(`docs/MIROFISH_EXPERIMENT_GUIDE.md`): 품질·비용·API 형태, 관리형 설치 전제 (a)~(e), 여론 절 활용 여부.
- StockTwits 사전 실험: 공식 MCP 서버로 3~5종목 응답 확인 → `CommunityPort` DTO. 약관상 원문 저장 범위.
- 네이버 검색 API: 기존 키 유예 종료(2027-06-30) 전 API HUB 이관·유료화 재확인.

## Figma 후속

`figma-plugin/README.md` 참조. 반영 실행 후 로그·스크린샷 대조 → 어긋난 곳은 `code.js`의 `ID` 표와 `TEXT_EDITS`만 고쳐 재실행. 파일명 "제목 없음" → Stockholm은 수동.

## 문서 갱신 규칙

결정이 바뀌면 코드보다 `PROJECT.md`를 먼저 고친다([확정]/[제안]/[확인 필요] 표기 유지). 이 파일은 "지금 상태"와 "다음 작업"만 유지하고, 끝난 항목은 지운다.
