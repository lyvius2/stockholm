# HANDOFF.md — 세션 인수인계 (Cowork → Claude Code)

> 문서 지도: [docs/INDEX.md](INDEX.md) · 기준 문서: [PROJECT.md](../PROJECT.md) · 작업 규칙: [CLAUDE.md](../CLAUDE.md) · 개발 순서: [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md)

갱신: 2026-09-24. 설계 단계(Cowork, claude.ai 프로젝트 "주식 거래 프로그램 프로젝트")를 마치고 개발 단계로 넘어간다. **이 저장소의 문서가 유일한 기준이다.** claude.ai 프로젝트의 `claude/*.md`는 같은 내용의 사본이며, 앞으로는 저장소를 먼저 고친다.

## 지금 상태

- 설계 문서 완료: [`PROJECT.md`](../PROJECT.md)(기준), [`CLAUDE.md`](../CLAUDE.md)(작업 규칙), [`docs/CORE_DOMAIN.md`](CORE_DOMAIN.md), [`docs/DEBATE_DESIGN.md`](DEBATE_DESIGN.md), [`docs/RAG_DESIGN.md`](RAG_DESIGN.md), [`docs/LLM_ROUTING.md`](LLM_ROUTING.md), [`docs/EXTERNAL_APIS.md`](EXTERNAL_APIS.md), [`docs/KEY_MANAGEMENT.md`](KEY_MANAGEMENT.md), [`docs/MIROFISH_EXPERIMENT_GUIDE.md`](MIROFISH_EXPERIMENT_GUIDE.md).
- 화면 설계: Cowork 아티팩트 "Stockholm 화면 설계" Version 17(HTML 목업), Figma 파일 `sZLAmrVRCfMgkfF7sS1uCx`. Figma 반영은 `figma-plugin/`(개발 플러그인 v2)으로 하며, **실행 결과 대조는 아직 안 됨**.
- 코드는 아직 없다. `backend/`, `desktop/`, `protocol/`은 미생성.
- **F13 연기금종목(국민연금 해외투자 현황) 설계 추가·확정(2026-09-24)**: [`docs/NPS_HOLDINGS_DESIGN.md`](NPS_HOLDINGS_DESIGN.md)(두 출처 SEC 13F 분기 + 공공데이터포털 연간, 모달·갱신 푸시·저장·포트·출처별 이용 조건), [`PROJECT.md`](../PROJECT.md) 9장 F13·8장 포트 표·11.3 상단 바 개정안, [`docs/EXTERNAL_APIS.md`](EXTERNAL_APIS.md) 2.8, [`docs/CORE_DOMAIN.md`](CORE_DOMAIN.md) pension 패키지·포트. 화면 설계 아티팩트 v26(Version 29, [`docs/screens/MAIN_SCREEN_DESIGN.html`](screens/MAIN_SCREEN_DESIGN.html) 사본)에 모달·🏛️ 버튼·토스트·주석 반영. `figma-plugin/` v3 `nps` 항목은 반영·확인 완료(2026-09-24).
- **F14 주문 관리 설계 추가·확정(2026-09-24)**: [`docs/ORDER_MANAGEMENT_DESIGN.md`](ORDER_MANAGEMENT_DESIGN.md)(3번 영역 세 탭, 정정 모달, 상태 매핑, `TradingPort.amend/cancel/closedOrders`, 예외), [`PROJECT.md`](../PROJECT.md) 9장 F14·8.1 정정·취소 규격·11.3 3번 영역, [`docs/CORE_DOMAIN.md`](CORE_DOMAIN.md) OrderStatus·OrderAmendment·BrokerOrder 체인, [`docs/EXTERNAL_APIS.md`](EXTERNAL_APIS.md) 1.1 항목 9. 화면 설계서 v18에 목업(미체결 탭·정정 모달·취소 확인). `figma-plugin/` `orders` 항목 반영 완료(2026-09-25).
- **F15 종목 정보 서랍 설계 추가(2026-09-24, [제안]; 서랍 순서 |Main|종목 정보|급등락|과 좌우 배타 규칙은 [확정])**: [`docs/STOCK_INFO_DESIGN.md`](STOCK_INFO_DESIGN.md)(탭 다섯, DART·EDGAR 출처, 갱신·캐시, `FundamentalsPort`, 저장, 예외), [`PROJECT.md`](../PROJECT.md) 9장 F15·11.3 서랍 네 개·2번 영역 버튼, [`docs/CORE_DOMAIN.md`](CORE_DOMAIN.md) 포트. 화면 설계서 v22에 목업. `figma-plugin/` v3.5 `info` 항목 + **섹션 정리(`organize`: F13·F14·F15·테마를 01 메인 화면의 섹션 네 개로 분리 — 무료 요금제 페이지 3장 제한)** — **Figma 반영 실행은 아직 안 함**.
- **Massive(구 Polygon.io) 키 확보·용도 확정(2026-09-25)**: 무료 등급(분당 5회·EOD·2년)으로 미국 배당 캘린더(F15 배당 탭 결손 해결)·SIC 업종·CIK·관련 종목·뉴스·공매도 잔고를 받는다. [`docs/EXTERNAL_APIS.md`](EXTERNAL_APIS.md) 2.9, PROJECT 8장 표. 남은 확인: 약관의 뉴스 저장·표시 조건, 관련 종목 엔드포인트 경로.
- **EDGAR 어댑터 규격 추가(2026-09-24)**: [`docs/EDGAR_DESIGN.md`](EDGAR_DESIGN.md) — data.sec.gov(submissions·companyfacts·frames) 실측 형식, 감시 알고리즘, 분기 값은 `frame`으로 고르는 규칙, 서식→조치 표, 저장·오류·테스트. `www.sec.gov`(티커 파일·Archives·Atom)는 연락처 UA로 재확인 필요.
- **F13 근거 자료 확정(2026-09-24)**: 국민연금 보유는 토론 개요·빠른 토론 수치 블록·미러피시 시드·F5 2단 입력에 들어간다([`PROJECT.md`](../PROJECT.md) F5·F13, [`docs/DEBATE_DESIGN.md`](DEBATE_DESIGN.md) 3.2, [`docs/RAG_DESIGN.md`](RAG_DESIGN.md) 4.3).

- **2026-09-25 세션 마감 시점**: 결정 일괄(F1 예외·9묶음·F17·가족 전제·F15 보류·F14 Slack 켬·F13 3단계·테마·10장 [제안] 확정), F16 자산 조회([`docs/ASSET_DESIGN.md`](ASSET_DESIGN.md))·F18 보유주식 평가금액 패널([`docs/PORTFOLIO_PANEL_DESIGN.md`](PORTFOLIO_PANEL_DESIGN.md))·DART([`docs/DART_DESIGN.md`](DART_DESIGN.md))·KRX([`docs/KRX_DESIGN.md`](KRX_DESIGN.md)) 설계 문서 추가, 화면 설계서 v30(Version 33: 두 줄 상단 바·연기금 모달 860·F16·F18·얇은 테마 스크롤바)까지 반영. `figma-plugin/` v3.7 `eval`(F18) 항목은 크래시 수정 후 **사용자 재실행·로그 확인 대기**. 이번 세션 변경은 사용자가 `ab788c8`(스크롤바)까지 커밋함. 남은 미커밋은 이 HANDOFF 갱신뿐.

- **F19 최초 구동 마법사·시작 종목 설계 추가(2026-09-25, [확정])**: [`docs/FIRST_RUN_DESIGN.md`](FIRST_RUN_DESIGN.md)(네 단계 화면, `SetupState` 상태 기계, 키별 읽기 전용 검증 표, 조회 제한 모드, `StartStockResolver` ⑴직전 종목→⑵평가금액 최대 보유→⑶기본 종목 삼성전자 005930/엔비디아 NVDA(설정 기본 시장), `lastViewedStock` 디바운스 저장, 테스트), [`PROJECT.md`](../PROJECT.md) F19·5장, [`docs/KEY_MANAGEMENT.md`](KEY_MANAGEMENT.md) 3장 머리말. 화면 설계서 v31(Version 34)에 마법사 목업(단계 클릭 전환)·시작 종목 표. `figma-plugin/` `firstrun` 항목: v3.8 실행 결과 카드·로그인 모달이 130px에서 잘림(자식을 채운 뒤 부모에 붙인 프레임의 감싸기가 고정으로 바뀜) → v3.10에서 `rehug()`(붙인 뒤 감싸기 재적용, 고정 축은 `fixW`/`fixB`) 도입. **재실행·대조 대기**. **확정(2026-09-25)**: 토스 키 "나중에" 허용 + 조회 제한 모드(D17·KEY_MANAGEMENT 반영), 로그인 모달은 매 실행·로그아웃 뒤 메인 위에 뜨며 유일하게 바깥을 흐림(화면 설계서 v32 Version 35 목업, 플러그인 프레임). TOTP 포함(RFC 6238 직접 구현, QR은 데몬 생성, ZXing 후보)·평가금액 기준·기본 종목 두 상수도 확정(2026-09-25). 남은 확인: 토스 보유 조회 평가금액 필드명, ZXing 의존성 채택. 1단계 리포 골격의 Flyway V1에 `installation`·`credential_meta`가 들어간다.

- **DB 스키마 설계 추가(2026-09-25, [확정])**: [`docs/DB_SCHEMA.md`](DB_SCHEMA.md) — 표 93개(engine 81 + relay 12)를 성격 네 가지(이벤트 로그·projection·캐시·상태)로 분류, mermaid 관계도 8장, 형식 자리표시자(`${decimal}`=TEXT, `${instant}`=ISO-8601), 물리 FK 기준(부모 안정·CASCADE 타당·적재 순서 보장·같은 DataSource)과 미적용 목록, 인덱스, Flyway 버전 계획(V1~V8, relay는 별도 파일·이력), 테스트. **결정 완료(2026-09-25)**: BigDecimal TEXT 저장, relay TOTP는 클라이언트 검증(PROJECT 5장), 보존 기간(PROJECT 6장) 승인. 정합성 항목(seq 단위, `OrderOrigin`, 승인·제외·메모 이벤트, `LOCAL` 범위, ack→notification, 재무 표 통합, 이벤트 이름 통일)은 CORE_DOMAIN·NPS·DART·STOCK_INFO·EDGAR·FIRST_RUN에 반영 완료. 남은 것: 메일박스 30일 [제안], 정정 주문 clientOrderId 승계 [확인 필요].

- **문서 지도·개발 계획 추가(2026-09-25)**: [INDEX.md](INDEX.md)(읽는 순서, 주제·기능·단계별 색인, 문서 규약), [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md)(0~9단계 착수 순서·읽을 문서·완료 기준·병렬 규칙). 설계 문서 20개와 PROJECT·CLAUDE의 문서 참조 180곳을 상대 경로 링크로 바꾸고, 각 설계 문서 머리에 문서 지도 줄을 넣었다. 링크 대상 존재 검사 통과. `AGENTS.md`(다른 에이전트용 입구, 규칙 없이 CLAUDE.md를 가리킴) 추가.

- **F20 거래내역 패널 설계 추가(2026-09-25, [확정])**: [TRADE_HISTORY_DESIGN.md](TRADE_HISTORY_DESIGN.md) — 2행 📒 버튼(💰 오른쪽, ⌘H), 오른쪽 위 슬라이딩 폭 60%(F18과 40:60 나란히), 탭 손익(F2)·체결내역(F14 주문 내역 흡수)·매매내역, 기간 일별·월별·분기별·직접, lot 선입선출 매칭(`lot_disposal`, FIFO [확정])과 매매손익·환차손익 정의. PROJECT F20·F2·F14·11.3, DB_SCHEMA 7장·V2, CORE_DOMAIN 5장, ORDER_MANAGEMENT 3.5, INDEX, DEVELOPMENT_PLAN 2단계 반영. 화면 설계서 v35(Version 38) 목업(💰·📒 함께 열기). `figma-plugin/` `history` 항목은 **02 주문 모달 · 서랍 · 검색 · 토론 페이지**에 그린다(사용자 지정). v3.11 실행 결과: 손익·체결내역 정상, 제목 텍스트가 프레임 이름과 겹침·매매내역 탭 없음 → v3.12에서 제목 제거·매매내역 장 추가. v3.12 실행은 헤더의 옛 📒 버튼 삭제 중 `does not exist`로 중단돼 아무것도 안 그려짐 → v3.13 `removeAll()`로 수정(재실행 대기). 결정됨(2026-09-25): z-order 메인 < 패널 < 검색·사용자 메뉴 < 모달(F18도 같음, PORTFOLIO_PANEL 3장 개정), 묶음 날짜 국내 KST·미국 ET(화면 안내), 양도세 토글은 대상 금액·예상 부과액만, CSV 없음, 실현손익 원천은 우리 DB(`lot_disposal`). 구현 단계 확인: 토스 매도 시 환율 필드(없으면 별도 환율 API), 실현손익 API(있으면 대조만). 화면 설계서 v36(Version 40).

- **F21 계정·알림·회원 관리 설계 추가(2026-09-25, [제안])**: [ACCOUNT_SETTINGS_DESIGN.md](ACCOUNT_SETTINGS_DESIGN.md) — 사용자 메뉴 세 모달(회원정보 변경 4탭, 알림 설정 채널×항목 표·방해 금지, 회원 관리·공유 키 admin step-up), `app_user`에 `email`·`slack_user_id`·`auto_stop_on_logout`(DB_SCHEMA 4장), 로컬 API `/me/*`·`/admin/*`. PROJECT F21·11.3, KEY_MANAGEMENT 4장·ASSET 2장 포인터, INDEX, DEVELOPMENT_PLAN 1·2단계. 화면 설계서 v37(Version 41) 목업(사용자 메뉴에서 열림). `figma-plugin/` v3.14 `account` 항목(02 페이지). **결정 대기**: 별도 로그인 ID 없이 표시 이름으로, 이메일 선택, admin 이관, 패스키 자리만.

- **F22 LLM 경로 설정 설계 추가(2026-09-25, [제안] → 같은 날 단순화)**: [LLM_ROUTE_SETTINGS_DESIGN.md](LLM_ROUTE_SETTINGS_DESIGN.md) — admin 모달(폭 760, step-up), **카테고리 카드 5장(토론 · 리포트·요약 · 거래 판단 · 추천 · 번역)** 마다 주·대체 LLM, 등록·검증 제공자만 선택, 프리셋은 5장 일괄, 거래 판단 실패 정책 고정, "고급" 토글에 목적별·페르소나별·디바이스·모델 직접 입력. 저장은 목적별 `llm_route`로 펼침 + `shared_setting` 카테고리 값 + `llm_route_history`. PROJECT F22, INDEX, LLM_ROUTING 3.1 카테고리 열. 화면 설계서 v39(Version 44) 목업(고급 토글 동작). `figma-plugin/` v3.16 `llmroute` 항목(02 페이지, 카드 5장). **결정 대기**: 카테고리 구성·이름, 구성원 읽기 전용, 고급 범위.

- **모달 단일 규칙(2026-09-25, [확정])**: 어떤 모달이든 열리면 다른 모달(과 확인 창)은 닫힌다. PROJECT 11.3, ACCOUNT_SETTINGS 1장, 화면 설계서 v40(Version 45)의 `showOnlyModal()` 공통 헬퍼.

- **F23 상단 바 지수 티커 설계 추가(2026-09-25, [제안])**: [MARKET_INDEX_TICKER_DESIGN.md](MARKET_INDEX_TICKER_DESIGN.md) — 1행 Stockholm 오른쪽 지수 3개(KR 장 KOSPI·KOSDAQ·NIKKEI 225 / US 장 DJIA·NASDAQ·S&P 500), `이름 | 지수 | ▲ 등락 (+%)` 포맷, 5분 갱신·왼쪽→오른쪽 슬라이딩(아래→위는 화면이 어색해 변경), 세트 선택 규칙(장 밖 종가 칩), 출처 확인(2026-09-25 openapi.json): 토스 시장 지표는 KOSPI·KOSDAQ·국채만 → 미국 장중은 토스 ETF 프록시(SPY·QQQ·DIA), 종가는 FRED/Massive Basic, Nikkei는 FRED 전일 종가 — **무료 구성으로 확정(2026-09-25)**, 유료 지수 API 안 씀. FRED 키는 공유 키(선택), `market_index_quote` 캐시(DB_SCHEMA 6장·V2). PROJECT F23·11.3, EXTERNAL_APIS 1.1, INDEX, DEVELOPMENT_PLAN 2단계. 화면 설계서 v42(Version 47) 목업(8초마다 좌→우 슬라이딩, 6번째마다 세트 전환). `figma-plugin/` v3.17 `ticker` 항목(네 헤더 1행). 결정 대기: 장 밖 규칙, 해외 지수 출처, 클릭 없음.

## 다음 작업: 12장 1단계 "리포 골격"

세부 순서와 완료 기준은 [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md) 3장. 아래는 요약.

[`PROJECT.md`](../PROJECT.md) 12장 1단계 순서대로. 착수 전에 [`CLAUDE.md`](../CLAUDE.md)의 절대 규칙과 [`docs/CORE_DOMAIN.md`](CORE_DOMAIN.md)를 읽는다.

1. `backend/` 단일 Spring Boot 프로젝트(Gradle Kotlin DSL, JDK 21 toolchain, 루트 패키지 `banghak.stockholm`, 프로필 `engine`/`relay`), Spotless(google-java-format).
2. 경계 검증 테스트: Spring Modulith 모듈 검증 + ArchUnit(`core`는 프레임워크 import 금지, `engine`↔`relay` 상호 참조 금지, 프로필별 컨텍스트 기동 3종 + `relay`만 켰을 때 주문·증권사·LLM 빈 부재).
3. `core` 값 객체부터 TDD: `Money`, `Quantity`(국내 정수·미국 소수 6자리), `StockCode`, `ClientOrderId`(36자·10분), `AutoBuyExposure`(168시간 경계값). 한도 상수는 `core`에 한곳.
4. 데몬 기동: `127.0.0.1` 바인딩, 로컬 토큰 파일, 헬스 엔드포인트. SQLite(WAL) + Flyway V1.
5. `desktop/` Electron + React + TS 골격(`contextIsolation` 켬), 데몬 기동·접속만.
6. `protocol/` JSON Schema 자리와 양쪽 타입 생성 파이프라인.
7. dmg 산출 스크립트 뼈대(`scripts/dist.sh`), 메모리 실측(`-Xmx384m`).

끝나면 [`CLAUDE.md`](../CLAUDE.md)의 "명령어" 절을 실제 명령으로 갱신한다.

## 미결 항목 (2026-09-25 정리)

### A. 사용자 결정 (설계·코드에 영향)

**2026-09-25 대부분 결정됨.** 결정 내용은 [`PROJECT.md`](../PROJECT.md) 13장·각 설계 문서에 반영. 남은 것:
0. ~~F19 최초 구동 마법사 결정~~ 모두 결정됨(2026-09-25). 구현 때 확인: 토스 보유 조회 평가금액 필드, QR 생성 의존성(ZXing).
0-1. ~~DB 스키마 결정~~ 승인됨(2026-09-25). 구현 때 확인: 정정 주문 clientOrderId 승계(학습 테스트), relay 메일박스 30일.
1. **F15 ETF 구성종목의 국내 데이터 제공자**(구현 보류 해제 조건).
2. **F17 매매 통계 · F2 기간별 손익 화면 설계**(승인됨, 화면 설계서에 목업 필요).
3. 토스 명세 기반 UI/UX 추가안 9묶음의 세부(승인됨, 화면 설계서로 옮길 때 항목별 반영).
4. F18 평가금액 패널: 토스 API의 예수금 D+1/D+2·담보비율 필드 [확인 필요](유지·숨김 기본값은 결정됨).

### B. 외부 확인 — **2026-09-25 결정: 구현 때 학습 테스트·문의로 확인한다.** 금융결제원은 전체 자산 조회로 확정(F16, [`docs/ASSET_DESIGN.md`](ASSET_DESIGN.md)).

1. **토스**: 디바이스별 client 발급 가능 여부, 밖의 Mac IP 허용 해법, 공용 시세 수집에 admin 키를 쓰는 것이 약관상 문제 없는지, 종목 정보에 업종·GICS·ISIN·영문명이 있는지(F15 산업군·F13 매핑), 정정 API `quantity`가 새 잔량인지 새 총 주문 수량인지, `PENDING_CANCEL` 중 재연결 시 최종 상태 확정 방법.
2. **금융결제원**: 키 종류(테스트베드/운영), 자산 조회 범위.
3. **미러피시** 사전 실험([`docs/MIROFISH_EXPERIMENT_GUIDE.md`](MIROFISH_EXPERIMENT_GUIDE.md)): 품질·비용·API 형태, 관리형 설치 전제 (a)~(e), 중간 발언 노출, 시드 문서 여론 절 활용.
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

결정이 바뀌면 코드보다 [`PROJECT.md`](../PROJECT.md)를 먼저 고친다([확정]/[제안]/[확인 필요] 표기 유지). 이 파일은 "지금 상태"와 "다음 작업"만 유지하고, 끝난 항목은 지운다.
