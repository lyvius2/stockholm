# HANDOFF.md — 세션 인수인계 (Cowork → Claude Code)

갱신: 2026-09-24. 설계 단계(Cowork, claude.ai 프로젝트 "주식 거래 프로그램 프로젝트")를 마치고 개발 단계로 넘어간다. **이 저장소의 문서가 유일한 기준이다.** claude.ai 프로젝트의 `claude/*.md`는 같은 내용의 사본이며, 앞으로는 저장소를 먼저 고친다.

## 지금 상태

- 설계 문서 완료: `PROJECT.md`(기준), `CLAUDE.md`(작업 규칙), `docs/CORE_DOMAIN.md`, `docs/DEBATE_DESIGN.md`, `docs/RAG_DESIGN.md`, `docs/LLM_ROUTING.md`, `docs/EXTERNAL_APIS.md`, `docs/KEY_MANAGEMENT.md`, `docs/MIROFISH_EXPERIMENT_GUIDE.md`.
- 화면 설계: Cowork 아티팩트 "Stockholm 화면 설계" Version 17(HTML 목업), Figma 파일 `sZLAmrVRCfMgkfF7sS1uCx`. Figma 반영은 `figma-plugin/`(개발 플러그인 v2)으로 하며, **실행 결과 대조는 아직 안 됨**.
- 코드는 아직 없다. `backend/`, `desktop/`, `protocol/`은 미생성.
- **F13 연기금종목(국민연금 해외투자 현황) 설계 추가·확정(2026-09-24)**: `docs/NPS_HOLDINGS_DESIGN.md`(두 출처 SEC 13F 분기 + 공공데이터포털 연간, 모달·갱신 푸시·저장·포트·출처별 이용 조건), `PROJECT.md` 9장 F13·8장 포트 표·11.3 상단 바 개정안, `docs/EXTERNAL_APIS.md` 2.8, `docs/CORE_DOMAIN.md` pension 패키지·포트. 화면 설계 아티팩트 v21(Version 24, `docs/screens/MAIN_SCREEN_DESIGN.html` 사본)에 모달·🏛️ 버튼·토스트·주석 반영. `figma-plugin/` v3 `nps` 항목은 반영·확인 완료(2026-09-24).
- **F14 주문 관리 설계 추가·확정(2026-09-24)**: `docs/ORDER_MANAGEMENT_DESIGN.md`(3번 영역 세 탭, 정정 모달, 상태 매핑, `TradingPort.amend/cancel/closedOrders`, 예외), `PROJECT.md` 9장 F14·8.1 정정·취소 규격·11.3 3번 영역, `docs/CORE_DOMAIN.md` OrderStatus·OrderAmendment·BrokerOrder 체인, `docs/EXTERNAL_APIS.md` 1.1 항목 9. 화면 설계서 v18에 목업(미체결 탭·정정 모달·취소 확인). `figma-plugin/` v3.2 `orders` 항목 추가 — **Figma 반영 실행은 아직 안 함**.
- **F13 근거 자료 확정(2026-09-24)**: 국민연금 보유는 토론 개요·빠른 토론 수치 블록·미러피시 시드·F5 2단 입력에 들어간다(`PROJECT.md` F5·F13, `docs/DEBATE_DESIGN.md` 3.2, `docs/RAG_DESIGN.md` 4.3).

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
- **F13 연기금종목**: 2026-09-24 결정 완료(버튼 이름·위치·⌘N, 캐시 TTL 24시간, 매핑, 알림 기본값, 토론 투입). 남은 것은 개발 단계 배치(제안: 3단계)뿐.
- **F14 주문 관리**: 2026-09-24 결정 완료(탭·모달, 자동 주문도 사람이 정정 가능 + 한도 초과 시 확인 창, clientOrderId 새로 발급, 정정 수량은 잔량까지). 남은 것: 체결 알림 기본값(제안: 앱 안 켬·Slack 끔).

## 외부 확인 필요 (착수 전)

- 토스: 디바이스별 client 발급 가능 여부, 밖의 Mac IP 허용 해법.
- 금융결제원 키 종류(테스트베드/운영), 자산 조회 범위.
- 미러피시 사전 실험(`docs/MIROFISH_EXPERIMENT_GUIDE.md`): 품질·비용·API 형태, 관리형 설치 전제 (a)~(e), 여론 절 활용 여부.
- StockTwits 사전 실험: 공식 MCP 서버로 3~5종목 응답 확인 → `CommunityPort` DTO. 약관상 원문 저장 범위.
- 네이버 검색 API: 기존 키 유예 종료(2027-06-30) 전 API HUB 이관·유료화 재확인.
- 공공데이터포털(국민연금 해외주식): `perPage` 상한, 일일 트래픽 한도, `Authorization` 헤더 접두, `uddi:df8671d8…_20201006`의 기준 시점, 토스 미국 종목 마스터의 영문 회사명·ISIN 제공 여부.
- SEC EDGAR 13F: 접수별 정보표 XML 파일명(index.json), User-Agent 연락처 이메일을 admin 공유 설정에 두는 안.
- 토스 정정·취소: 정정 API `quantity`가 새 잔량인지 새 총 주문 수량인지(화면은 잔량 입력, 어댑터가 변환), `PENDING_CANCEL` 중 재연결 시 최종 상태 확정 방법.

## Figma 후속

`figma-plugin/README.md` 참조. v3에 F13 항목(🏛️ 버튼·모달·토스트·표지 8장)이 추가됐고 아직 실행 전이다. 반영 실행 후 로그·스크린샷 대조 → 어긋난 곳은 `code.js`의 `ID` 표와 `TEXT_EDITS`만 고쳐 재실행. 파일명 "제목 없음" → Stockholm은 수동.

## 문서 갱신 규칙

결정이 바뀌면 코드보다 `PROJECT.md`를 먼저 고친다([확정]/[제안]/[확인 필요] 표기 유지). 이 파일은 "지금 상태"와 "다음 작업"만 유지하고, 끝난 항목은 지운다.
