# HANDOFF.md — 세션 인수인계 (Cowork → Claude Code)

갱신: 2026-09-24. 설계 단계(Cowork, claude.ai 프로젝트 "주식 거래 프로그램 프로젝트")를 마치고 개발 단계로 넘어간다. **이 저장소의 문서가 유일한 기준이다.** claude.ai 프로젝트의 `claude/*.md`는 같은 내용의 사본이며, 앞으로는 저장소를 먼저 고친다.

## 지금 상태

- 설계 문서 완료: `PROJECT.md`(기준), `CLAUDE.md`(작업 규칙), `docs/CORE_DOMAIN.md`, `docs/DEBATE_DESIGN.md`, `docs/RAG_DESIGN.md`, `docs/LLM_ROUTING.md`, `docs/EXTERNAL_APIS.md`, `docs/KEY_MANAGEMENT.md`, `docs/MIROFISH_EXPERIMENT_GUIDE.md`.
- 화면 설계: Cowork 아티팩트 "Stockholm 화면 설계" Version 17(HTML 목업), Figma 파일 `sZLAmrVRCfMgkfF7sS1uCx`. Figma 반영은 `figma-plugin/`(개발 플러그인 v2)으로 하며, **실행 결과 대조는 아직 안 됨**.
- 코드는 아직 없다. `backend/`, `desktop/`, `protocol/`은 미생성.
- **F13 연기금종목(국민연금 해외투자 현황) 설계 추가·확정(2026-09-24)**: `docs/NPS_HOLDINGS_DESIGN.md`(두 출처 SEC 13F 분기 + 공공데이터포털 연간, 모달·갱신 푸시·저장·포트·출처별 이용 조건), `PROJECT.md` 9장 F13·8장 포트 표·11.3 상단 바 개정안, `docs/EXTERNAL_APIS.md` 2.8, `docs/CORE_DOMAIN.md` pension 패키지·포트. 화면 설계 아티팩트 v26(Version 29, `docs/screens/MAIN_SCREEN_DESIGN.html` 사본)에 모달·🏛️ 버튼·토스트·주석 반영. `figma-plugin/` v3 `nps` 항목은 반영·확인 완료(2026-09-24).
- **F14 주문 관리 설계 추가·확정(2026-09-24)**: `docs/ORDER_MANAGEMENT_DESIGN.md`(3번 영역 세 탭, 정정 모달, 상태 매핑, `TradingPort.amend/cancel/closedOrders`, 예외), `PROJECT.md` 9장 F14·8.1 정정·취소 규격·11.3 3번 영역, `docs/CORE_DOMAIN.md` OrderStatus·OrderAmendment·BrokerOrder 체인, `docs/EXTERNAL_APIS.md` 1.1 항목 9. 화면 설계서 v18에 목업(미체결 탭·정정 모달·취소 확인). `figma-plugin/` `orders` 항목 반영 완료(2026-09-25).
- **F15 종목 정보 서랍 설계 추가(2026-09-24, [제안]; 서랍 순서 |Main|종목 정보|급등락|과 좌우 배타 규칙은 [확정])**: `docs/STOCK_INFO_DESIGN.md`(탭 다섯, DART·EDGAR 출처, 갱신·캐시, `FundamentalsPort`, 저장, 예외), `PROJECT.md` 9장 F15·11.3 서랍 네 개·2번 영역 버튼, `docs/CORE_DOMAIN.md` 포트. 화면 설계서 v22에 목업. `figma-plugin/` v3.5 `info` 항목 + **섹션 정리(`organize`: F13·F14·F15·테마를 01 메인 화면의 섹션 네 개로 분리 — 무료 요금제 페이지 3장 제한)** — **Figma 반영 실행은 아직 안 함**.
- **Massive(구 Polygon.io) 키 확보·용도 확정(2026-09-25)**: 무료 등급(분당 5회·EOD·2년)으로 미국 배당 캘린더(F15 배당 탭 결손 해결)·SIC 업종·CIK·관련 종목·뉴스·공매도 잔고를 받는다. `docs/EXTERNAL_APIS.md` 2.9, PROJECT 8장 표. 남은 확인: 약관의 뉴스 저장·표시 조건, 관련 종목 엔드포인트 경로.
- **EDGAR 어댑터 규격 추가(2026-09-24)**: `docs/EDGAR_DESIGN.md` — data.sec.gov(submissions·companyfacts·frames) 실측 형식, 감시 알고리즘, 분기 값은 `frame`으로 고르는 규칙, 서식→조치 표, 저장·오류·테스트. `www.sec.gov`(티커 파일·Archives·Atom)는 연락처 UA로 재확인 필요.
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

## 미결 항목 (2026-09-25 정리)

### A. 사용자 결정 (설계·코드에 영향)

**2026-09-25 대부분 결정됨.** 결정 내용은 `PROJECT.md` 13장·각 설계 문서에 반영. 남은 것:
1. **F15 ETF 구성종목의 국내 데이터 제공자**(구현 보류 해제 조건).
2. **F17 매매 통계 · F2 기간별 손익 화면 설계**(승인됨, 화면 설계서에 목업 필요).
3. 토스 명세 기반 UI/UX 추가안 9묶음의 세부(승인됨, 화면 설계서로 옮길 때 항목별 반영).
4. F18 평가금액 패널: 토스 API의 예수금 D+1/D+2·담보비율 필드 [확인 필요](유지·숨김 기본값은 결정됨).

### B. 외부 확인 — **2026-09-25 결정: 구현 때 학습 테스트·문의로 확인한다.** 금융결제원은 전체 자산 조회로 확정(F16, `docs/ASSET_DESIGN.md`).

1. **토스**: 디바이스별 client 발급 가능 여부, 밖의 Mac IP 허용 해법, 공용 시세 수집에 admin 키를 쓰는 것이 약관상 문제 없는지, 종목 정보에 업종·GICS·ISIN·영문명이 있는지(F15 산업군·F13 매핑), 정정 API `quantity`가 새 잔량인지 새 총 주문 수량인지, `PENDING_CANCEL` 중 재연결 시 최종 상태 확정 방법.
2. **금융결제원**: 키 종류(테스트베드/운영), 자산 조회 범위.
3. **미러피시** 사전 실험(`docs/MIROFISH_EXPERIMENT_GUIDE.md`): 품질·비용·API 형태, 관리형 설치 전제 (a)~(e), 중간 발언 노출, 시드 문서 여론 절 활용.
4. **네이버 검색 API**: 기존 키 유예 종료(2027-06-30) 전 API HUB 이관·유료화 재확인.
5. **공공데이터포털(국민연금)**: `perPage` 상한, 일일 트래픽 한도, `Authorization` 헤더 접두, `uddi:df8671d8…_20201006`의 기준 시점.
6. **SEC EDGAR**: 접수별 정보표·문서 파일명(`index.json`), `www.sec.gov` 계열(티커 파일·Archives·Atom) 연락처 UA로 재확인, submissions `ETag` 지원, 6-K 분류 방법, 2022년 이전 13F `value` 단위 전환 시점.
7. **Massive**: 약관의 뉴스 저장·표시 조건과 재배포 범위, 관련 종목 엔드포인트 경로.
8. **StockTwits**(4번 결정에 따라): 엔터프라이즈 개인 자격 문의 결과.
9. **Reddit**: 없음(링크아웃 확정). Data API 신청은 원문이 꼭 필요할 때만.

### C. 구현 착수 시 학습 테스트로 확정 (키는 환경 변수·Keychain으로만)

1. **KRX Open API**: 전송 방식(JSON POST/GET), 숫자의 쉼표 여부, `ISU_CD` 단축코드 여부, 전일 데이터가 열리는 시각, 호출 한도.
2. **DART**: 엔드포인트명·파라미터 대조, 재무제표 계정 표준 매핑(제조·금융 샘플), 배당 결정 공시 필드.
3. **EDGAR**: 분기 값 `frame` 선택 규칙, company facts 태그 대체 순서.
4. **Massive**: 분당 5회 한도 동작, dividends `frequency` 코드, news `insights` 형식.
5. **토스**: 캔들·주문·정정 학습 테스트는 사용자 지시와 최소 금액으로만(절대 규칙 1).

## Figma 후속

`figma-plugin/README.md` 참조. v3에 F13 항목(🏛️ 버튼·모달·토스트·표지 8장)이 추가됐고 아직 실행 전이다. 반영 실행 후 로그·스크린샷 대조 → 어긋난 곳은 `code.js`의 `ID` 표와 `TEXT_EDITS`만 고쳐 재실행. 파일명 "제목 없음" → Stockholm은 수동.

## 문서 갱신 규칙

결정이 바뀌면 코드보다 `PROJECT.md`를 먼저 고친다([확정]/[제안]/[확인 필요] 표기 유지). 이 파일은 "지금 상태"와 "다음 작업"만 유지하고, 끝난 항목은 지운다.
