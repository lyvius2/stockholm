# HANDOFF.md — 세션 인수인계 (Cowork → Claude Code)

> 문서 지도: [docs/INDEX.md](INDEX.md) · 기준 문서: [PROJECT.md](../PROJECT.md) · 작업 규칙: [CLAUDE.md](../CLAUDE.md) · 개발 순서: [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md)

갱신: 2026-09-26(리포 골격 생성). 설계 단계(Cowork, claude.ai 프로젝트 "주식 거래 프로그램 프로젝트")를 마치고 개발 단계로 넘어간다. **이 저장소의 문서가 유일한 기준이다.** claude.ai 프로젝트의 `claude/*.md`는 같은 내용의 사본이며, 앞으로는 저장소를 먼저 고친다.

## 지금 상태

- 설계 문서 완료: [`PROJECT.md`](../PROJECT.md)(기준), [`CLAUDE.md`](../CLAUDE.md)(작업 규칙), [`docs/CORE_DOMAIN.md`](CORE_DOMAIN.md), [`docs/DEBATE_DESIGN.md`](DEBATE_DESIGN.md), [`docs/RAG_DESIGN.md`](RAG_DESIGN.md), [`docs/LLM_ROUTING.md`](LLM_ROUTING.md), [`docs/EXTERNAL_APIS.md`](EXTERNAL_APIS.md), [`docs/KEY_MANAGEMENT.md`](KEY_MANAGEMENT.md), [`docs/MIROFISH_EXPERIMENT_GUIDE.md`](MIROFISH_EXPERIMENT_GUIDE.md).
- 화면 설계: Cowork 아티팩트 "Stockholm 화면 설계" Version 17(HTML 목업), Figma 파일 `sZLAmrVRCfMgkfF7sS1uCx`. Figma 반영은 `figma-plugin/`(개발 플러그인 v2)으로 하며, **실행 결과 대조는 아직 안 됨**.
- **리포 골격 생성(2026-09-26, 1단계 착수)**: `README.md`(shields.io 배지), `backend/`(Gradle 9.8 래퍼 · Kotlin 2.4.20 · Spring Boot 4.1.1 · GraalVM JDK 25 toolchain `vendor = GRAAL_VM` · Spotless+ktfmt kotlinlang · 버전 카탈로그 `gradle/libs.versions.toml`), 계층 골격 `core(domain 18개념·usecase·port)` / `engine(application 14·adapter/in 3·adapter/out 18·config)` / `relay` / `shared(config·web·crypto·time·util·protocol)`은 `.gitkeep` 자리, `shared/config`에 `ProfileGuard`(프로필 없으면 기동 실패)·`StockholmProperties`(data-dir)·`ClockConfig`·`VirtualThreadConfig`·`ConcurrencyLimits`+`SemaphoreConfig`(llm 4·collector 8, 낮출 수만)·`HttpProperties`+`OkHttpConfig`(로깅 인터셉터 없음)·`RetrofitFactory`(Jackson 2 컨버터)·`JacksonConfig`(BigDecimal → 문자열)·`CacheConfig`(Caffeine, yml spec)·`ZoneIds`, `engine/config/EngineConfig`·`relay/config/RelayConfig`(@Profile), `application.yml`(가상 스레드, actuator health만, 127.0.0.1)·`application-engine.yml`(2609)·`application-relay.yml`(포트 B2 보류). 경계 테스트 `architecture/`: `CoreHasNoFrameworkTest`·`HexagonalDependencyTest`·`EngineRelayIsolationTest`·`ProfileBeansTest`(engine/relay 빈은 정확히 그 @Profile, shared는 프로필 없음)·`NoCyclesTest`(slices) + `ProfileContextTest`(engine/relay/둘 다 기동, relay만 켜면 engine 빈 0, 프로필 없으면 실패) + `LocalApiSmokeTest`(헬스 UP). **`./gradlew build` 녹색.** `desktop/`(Electron 44 · electron-vite 5 · Vite 7 · React 19.3 · TS 5.9 strict · TanStack Query · Zustand · Vitest 5 · ESLint 10 flat + Prettier): main(`contextIsolation`·`sandbox`·외부 링크는 브라우저로, `daemon.ts` 헬스 폴링) · preload(CJS, `daemon.status`·`theme.current`만 노출, 타입은 `bridge.ts`) · renderer(App: 상단 바 + 데몬 연결 상태, 색 토큰·얇은 스크롤바 css는 화면 설계서 값 그대로) · `tests/App.test.tsx`. **typecheck·lint·format·test·build 녹색.** `protocol/`(README·`schemas/common/envelope.schema.json`·`generate.sh` quicktype), `scripts/`(dev·dist 뼈대·learning-tests·db-dump), `.editorconfig`, `.github/workflows/ci.yml`(GraalVM 25 CE + Node 22), `.gitignore` 보강. CLAUDE.md 명령어 절 실제 명령으로 갱신.
- **1단계 첫날 확인 결과(2026-09-26)**: GraalVM CE 25.0.2(sdkman) toolchain 자동 감지됨, Kotlin 2.4.20 `jvmTarget=25` 컴파일됨, Gradle 9.8 + 설정 캐시 정상 → **21 후퇴 불필요**. 데몬 jar를 `-Xmx384m`으로 띄우면 기동 1.8초, 유휴 RSS **292MB**(목표 400MB 이하, 골격 상태 기준). `jlink`는 dist.sh에서 아직 실행 안 해봄.
- **골격에서 내린 결정·주의(2026-09-26)**: ① Boot 4.1에서 `spring-boot-starter-aop`가 **`spring-boot-starter-aspectj`**로 바뀜(Resilience4j 애너테이션용). ② Boot 4는 Jackson 3(`tools.jackson`)이고 Retrofit 컨버터는 Jackson 2라 `RetrofitFactory`가 별도 `ObjectMapper`(jackson-bom 2.22.3)를 가짐. 로컬 API 직렬화는 Spring의 Jackson 3. ③ **Spring Modulith 제외(사용자 결정 2026-09-26)**: Kotlin에 패키지 애너테이션이 없어 `package-info.java`(Java 소스 루트)가 필요했음 → 경계는 ArchUnit만으로 강제하고 순환 검사는 `NoCyclesTest`(slices)로 대신함. `src/main/java` 없음, core 규칙에 예외 없음. ④ `archunit.properties`에 `failOnEmptyShould=false`(빈 패키지 규칙 통과). ⑤ TypeScript는 7.0이 나왔지만 typescript-eslint가 <6.1만 지원해 **5.9.3** 고정, electron-vite 5가 Vite 8 미지원이라 **Vite 7**. ⑥ 로컬 Node 22.17.1은 jsdom 의존성의 engine 조건(22.22+)에 경고만 남김 → Node 22 LTS 최신으로 올리길 권함. ⑦ 샌드박스 렌더러는 CommonJS preload만 받아 electron-vite preload 출력을 `.cjs`로 고정. ⑧ **`./gradlew dev`**(사용자 요청 2026-09-26): `DevTask`가 `ExecOperations`로 `npm run dev`(별도 스레드)와 데몬 `javaexec`를 함께 띄움. 진짜 터미널 Ctrl+C로 둘 다 내려가는 것을 pty 실험으로 확인(백그라운드 잡에 `kill -INT`는 무시되므로 그걸로 검증하면 안 됨). `scripts/dev.sh`는 이 작업의 래퍼. ⑨ `npm install`이 Electron 바이너리를 내려받지 않은 채 끝나 "Electron uninstall" 오류가 났음 → `node node_modules/electron/install.js`로 해결, README 시작하기에 안내.
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

- **F23 상단 바 지수 티커 설계 추가(2026-09-25, [제안])**: [MARKET_INDEX_TICKER_DESIGN.md](MARKET_INDEX_TICKER_DESIGN.md) — 1행 Stockholm 오른쪽 지수 3개(KR 장 KOSPI·KOSDAQ·NIKKEI 225 / US 장 DJIA·NASDAQ·S&P 500), `이름 | 지수 | ▲ 등락 (+%)` 포맷, 5분 갱신·왼쪽→오른쪽 슬라이딩(아래→위는 화면이 어색해 변경), 세트 선택 규칙(장 밖 종가 칩), 출처 확인(2026-09-25 openapi.json): 토스 시장 지표는 KOSPI·KOSDAQ·국채만 → 미국 장중은 토스 ETF 프록시(SPY·QQQ·DIA), 종가는 FRED/Massive Basic, Nikkei는 FRED 전일 종가 — **무료 구성으로 확정(2026-09-25)**, 유료 지수 API 안 씀. FRED 키는 공유 키(선택) — **확보됨(2026-09-25)**, EXTERNAL_APIS 2.5에 엔드포인트·시리즈·이용 조건 기록, `market_index_quote` 캐시(DB_SCHEMA 6장·V2). PROJECT F23·11.3, EXTERNAL_APIS 1.1, INDEX, DEVELOPMENT_PLAN 2단계. 화면 설계서 v42(Version 47) 목업(8초마다 좌→우 슬라이딩, 6번째마다 세트 전환). `figma-plugin/` v3.17 `ticker` 항목(네 헤더 1행). 결정 대기: 장 밖 규칙, 해외 지수 출처, 클릭 없음.

- **디렉터리 구조 설계(2026-09-25, [제안])**: [DIRECTORY_STRUCTURE.md](DIRECTORY_STRUCTURE.md) — 루트, `backend/`(core 16 패키지·port 19개, engine 어댑터·서비스·persistence·localapi, relay 뼈대, shared, resources, test 배치·학습 테스트), `desktop/`(main·preload·renderer layout·features 19개·data 한 겹·generated), `protocol/`, `scripts/`, 기능 F1~F23 → 위치 표, 1단계에서 채우는 범위. PROJECT 11.1·CLAUDE 문서 절·INDEX·DEVELOPMENT_PLAN 1단계 연결. 결정 대기: 단일 모듈 유지, protocol 생성 도구.

- **기술 스택 정리(2026-09-25, [제안])**: [TECH_STACK.md](TECH_STACK.md) — 런타임·빌드(JDK 21·Gradle 9·Node 22·jlink·electron-builder), 백엔드(Spring Boot 최신·Modulith·ArchUnit·MVC+가상 스레드·JDK HttpClient·Jackson·JPA/Hibernate·xerial SQLite·Flyway·Lucene+Nori·Ollama bge-m3·Spring AI·Argon2·TOTP 직접·ZXing·Keychain `security` CLI·Rome·jsoup·JUnit5/AssertJ/WireMock/Testcontainers), 데스크톱(Electron·Vite·React 19·TS strict·TanStack Query+Zustand·Lightweight Charts·CSS 토큰·ESLint/Prettier·Vitest), protocol 생성 도구, 외부 서비스 접근 방식, 쓰지 않기로 한 것(WebFlux·Security 전체·Lombok·MapStruct·Redux·Tailwind·SDK들). 결정 대기: Boot 3.5 vs 4.x, MySQL 드라이버, 생성 도구, ZXing, Playwright.

- **relay 메시지 브로커 도입(2026-09-25, 사용자 결정)**: store-and-forward를 DB 메일박스 대신 내구 스트림이 담당. 제품은 **NATS JetStream 제안**(대안 Valkey Streams) — **선택은 relay 서버(7단계) 착수 전까지 보류**. `relay_mailbox`·`relay_mailbox_delivery` 삭제 → `relay_stream_cursor`. DB_SCHEMA 11장, TECH_STACK 6·7·8장, PROJECT 6장, DIRECTORY_STRUCTURE `relay/messaging`. Redis Pub/Sub·CDC는 부적합으로 기록.

- **백엔드 스펙 확정(2026-09-26, 사용자 결정)**: Spring Boot 4.x · **Kotlin**(코루틴 금지, 가상 스레드 + `Semaphore` 빈) · JPA + jOOQ(코드 생성 없음, Join·Bulk만, 리포지터리 메서드 16자 초과 시 JPQL) · `RestClient` HTTP 인터페이스를 엔드포인트별 빈으로(Feign 방식) · Resilience4j(fallback 필수) · 로컬 API 포트 2609 · 캐시는 등록된 Valkey/Redis 연결 성공 시 사용, 아니면 로컬(Caffeine) · **Hexagonal, 루트 `banghak.stock`** — 도메인별 패키지 분할은 **같은 날 취소** → 계층별 `core(domain·usecase·port)` / `engine`·`relay`(application·adapter·config) / `shared`. 반영: CLAUDE.md 기술 기준·경계·형식·TDD 문구, PROJECT D3·11.1(트리·경계 규칙·스택 줄), DIRECTORY_STRUCTURE 개정(계층 골격, usecase/port 위치, 기능→위치 표), TECH_STACK 0장(확정 스펙)·행 갱신, CORE_DOMAIN 머리말(Java 예시 → Kotlin 구현), FIRST_RUN ②·검증 표에 캐시 서버, ACCOUNT 5.2 공유 설정, DEVELOPMENT_PLAN 1단계, 패키지명 일괄 치환. **2026-09-26 결정**: 공용 값 객체는 `core/domain/{money,market,identity}` 한곳, `research` 유지, 테스트 JUnit 5 + AssertJ, 캐시 대상 제안 채택, relay 포트는 연기. ktfmt kotlinlang 스타일·protocol 생성 quicktype도 확정. **같은 날 추가 결정: GraalVM for JDK 25 LTS(JVM 모드, 21에서 상향, 첫날 Kotlin·Gradle·jlink 확인; 배포판 Community 제안 — GFTC 재배포 조건 때문) · 외부 HTTP는 Retrofit2 + OkHttp(RestClient HTTP 인터페이스 대체, 토스 WebSocket도 OkHttp)**. 백엔드 스펙 결정 사항은 모두 닫힘(relay 포트·브로커 제품만 7단계로 연기).

## 다음 작업: 1단계 나머지 (DEVELOPMENT_PLAN 3장 기준)

골격(3장 1번)·CLAUDE 명령어(12번)는 끝났고 2·9·10·11번은 부분 완료. 남은 순서:

1. ~~경계 테스트 나머지(2번)~~ **완료(2026-09-26)**: `OrderApiOnlyInTossOrderClientTest`(주문 경로 `orders`를 선언한 Retrofit 메서드는 `TossOrderClient`뿐, 참조는 toss 어댑터·engine.config만, 타 증권사 어댑터에 주문 엔드포인트 없음) · `GuardrailNotBypassableTest`(`TradingPort.place*` 호출은 `engine.application.trading`만, 호출자는 `EvaluateGuardrailUseCase`에 의존, 진입 어댑터는 `TradingPort` 참조 금지) · `FallbackRequiredTest`(Retrofit 인터페이스를 부르는 메서드는 `@CircuitBreaker(fallbackMethod)`이고 그 메서드가 같은 클래스에 있어야 함) · `NoModelNamesOutsideLlmTest`(제공자 이름 클래스·Spring AI import는 llm 어댑터만, 모델 이름 문자열은 소스 스캔) · `RepositoryUserScopedTest`(엔티티에 `userId` 필드가 있는 리포지터리의 모든 선언 메서드는 이름에 `UserId` 또는 `@Query`에 `:userId`). 도우미 `RetrofitEndpoints`. 지금은 대상 코드가 없어 비어 있는 채로 통과함.
2. ~~`core` 값 객체 TDD(3번)~~ **완료(2026-09-26)**: `core/domain/{error,money,market,identity,automation,trading}` — `DomainException`·`InvalidValue`·`CurrencyMismatch`, `Currency`(scale)·`RoundingRules`(HALF_EVEN, 비율 6자리)·`Money`(생성자는 자릿수 엄격, `of`가 반올림)·`ExchangeRate`·`Percent`(value class, 자릿수 정규화), `Market`(통화·시간대·수량 자릿수)·`Symbol`(KR 6자리 숫자·US 대문자 1~5자, 기본 종목 상수), `Ulid`(순수 Crockford base32 인코더, 시계·난수는 밖에서)·`UserId`·`DeviceId`·`Role`, `StrategyId`, `Quantity`(0 이상·소수 6자리·시장별 검증·정규화)·`ClientOrderId`(36자 검증, 결정적 SHA-256→base32 26자, ULID 수동 키). 테스트 41건. 결정: US 티커는 `.` 없는 1~5자만(BRK.B 같은 형식은 토스 코드 확인 후 확장).
3. ~~이벤트 로그(4번)~~ **완료(2026-09-26)**: `core/domain/eventlog` — `SyncScope`, `DomainEvent`(sealed, CORE_DOMAIN 9장의 36개 전부. 토론·페르소나·승인 ID는 그 패키지가 생길 때까지 String, payload 필드는 [제안]), `SyncScopes`(종류→범위 표, exhaustive `when`), `EventEnvelope`(type = 클래스 이름, `supersedes` LWW: occurredAt 늦은 쪽 → deviceId 큰 쪽). `core/port/EventStore`(append는 마지막 seq 반환, replay는 디바이스 지정 시 seq 순·미지정 시 발생 시각 순, `replayForSync`는 LOCAL 제외). 함께 생긴 타입: `LotId`·`BuyOrigin`, `OrderSide`·`OrderKind`·`TimeInForce`·`OrderOrigin`·`OrderStatus`, `ExecutionStage`. 테스트: `support/fakes/MemoryEventStore` + `EventStoreContractTest`(open class, 영속 구현이 상속해 같은 계약 검증: (사용자,디바이스)별 seq 1부터, 같은 디바이스 두 사용자 각자 1부터, 빈 append, afterSeq, 사용자 격리, LOCAL 미출력) + `EventEnvelopeTest`. 총 81건.
   **코드 리뷰 반영(2026-09-27, Spring AI Ops 리뷰 #1790466081000)**: ULID 첫 글자 `0~7`·시각 0..2^48-1 검증(`UserId`·`DeviceId`·`LotId` 패턴도), `RetrofitEndpoints`가 `@HTTP(path=)`도 읽음, fallback은 시그니처(반환 타입 동일, 인자 동일 또는 끝에 Throwable)까지 검사, `TradingPort` 구현체 직접 참조 금지 규칙, `EventEnvelope` 불변식(seq ≥ 1, type = payload 종류), 계약 테스트는 `@BeforeEach`에서 저장소 생성, 이벤트 이름 36개 골든 테스트(`EventTypesGoldenTest`, 이름 변경 = 마이그레이션 신호), 규칙 자기 검증(`ArchitectureRulesSelfTest` + `architecture/fixtures`). 받아들이지 않은 것: 이벤트 type의 별도 ID·스키마 버전(V1 `event_log` 설계 때 재검토), 리포지터리 규칙의 의미 검증(영속성 통합 테스트가 담당, KDoc에 한계 명시). **8단계 메모**: 동기화 전송 경로는 `replayForSync`만 부르도록 ArchUnit 규칙 추가, `UserSettingChanged` 등 자유 문자열 값에 표식 비밀값 검색 테스트 추가. 테스트 88건.
   **다음**: 영속성 V1(5번, JPA·jOOQ·Flyway 스타터는 카탈로그에 이미 있음) → Keychain(6번, 개발 키 보관 방식은 사용자 결정 대기: Keychain 권고 vs `.env`) → 데몬 기동·`/setup/*`·로컬 토큰(7번) → 사용자·세션·TOTP(8번).
4. desktop(9번) 나머지: 데몬 자식 프로세스 기동(`daemon.ts` TODO), 트레이 상주, 마법사 4단계·로그인 모달·네 영역 골격, 데이터 접근 계층. protocol(10번): `generate.sh`는 동작 확인됨(봉투 스키마 → `Envelope.kt`(Jackson 애너테이션)·`envelope.ts`, `body`는 객체로 고정하고 SYNC는 `{ciphertext}`), 다음은 이벤트 payload 스키마 1개와 생성 타입의 실제 사용. dist(11번): `dist.sh`의 jlink 모듈 목록을 jdeps로 확정, dmg 실제 산출.

착수 전에 [`CLAUDE.md`](../CLAUDE.md) 절대 규칙과 [`docs/CORE_DOMAIN.md`](CORE_DOMAIN.md)를 읽는다. 아직 커밋하지 않았다(사용자 요청 시 `chore: 리포 골격` 등으로).

## 미결 항목 (2026-09-26 정리)

### A. 사용자 결정 — **2026-09-26 A1~A10 제안대로 확정**

- 확정: F21(표시 이름 로그인·이메일 선택·admin 이관·패스키 자리만), F22(카테고리 5개·구성원 읽기 전용·고급 범위), F23(장 밖 규칙·클릭 없음), ZXing, Playwright는 2단계 끝. 각 설계 문서 결정 절에 반영.
- 시점이 오면 하는 것: A11 F17·F2 화면 목업(5단계 착수 전), A12 F15 ETF 구성종목 국내 제공자(나타나면), A13 토스 UI/UX 9묶음 세부(2단계 화면 작업 때).

### B. 7단계(relay) 전까지 연기

| # | 항목 |
|---|---|
| B1 | 메시지 브로커 제품: NATS JetStream(제안) vs Valkey Streams |
| B2 | relay 포트·WebSocket 경로 |
| B3 | MySQL 드라이버(GPL+FOSS exception) vs MariaDB Connector/J(LGPL) |

### C. 구현 단계 학습 테스트로 확인 (키는 환경 변수·Keychain)

1. **토스** — 2026-09-26 규격(v1.2.17) 확인으로 닫힘: 보유 평가금액 필드(거래 통화, 원화 환산은 환율로), 예수금 D+1/D+2·담보비율 없음(F18 수정), 매도 시 환율 없음(환율 캐시로), 실현손익 API 없음(우리 DB), ISIN·영문명 있음·업종 없음, 지수 등락은 캔들로 계산(EXTERNAL_APIS 1.1.1). **남은 것**: 정정 `quantity` 의미(잔량 vs 총량), 정정·취소 주문의 `clientOrderId` 승계, `market-indicators` 갱신 주기·지연, `PENDING_CANCEL` 중 재연결 시 상태 확정, 디바이스별 client 발급 가능 여부, `koreanMarketDetail` 세부 필드.
2. **KRX**: 전송 방식·숫자 형식·`ISU_CD`·전일 데이터 열리는 시각·한도.
3. **DART**: 엔드포인트·파라미터 대조, 재무 계정 매핑(제조·금융), 배당 결정 필드, 분기 배당 표현.
4. **EDGAR**: 접수별 파일명(`index.json`), `frame` 선택 규칙, 태그 대체 순서, `www.sec.gov` UA 재확인, `ETag`, 6-K 분류, 2022년 이전 13F `value` 단위.
5. **Massive**: 분당 5회 동작, dividends `frequency`, news `insights`, 관련 종목 경로, Indices Basic 포함 지수.
6. **FRED**: 결측 `"."` 처리, 양도세용 결제일 기준환율 출처(없으면 체결일 근사).
7. **공공데이터포털**: `perPage` 상한, 일일 한도, `Authorization` 접두, 데이터셋 기준 시점.
8. **1단계 첫날**: JDK 25에서 Kotlin `jvmTarget`·Gradle·Spring AI·Resilience4j 짝 버전 확인(Modulith 제외).

### D. 외부 확인·문의 (약관·계약)

1. 토스: 밖의 Mac IP 허용 해법, 공용 시세 수집에 admin 키 사용의 약관 적합성.
2. 금융결제원: 키 종류(테스트베드/운영), 자산 조회 범위.
3. 미러피시 사전 실험([MIROFISH_EXPERIMENT_GUIDE](MIROFISH_EXPERIMENT_GUIDE.md)).
4. 네이버 검색 API: API HUB 이관·유예 종료(2027-06-30) 전 재확인.
5. Massive 약관: 뉴스 저장·표시·재배포 범위.
6. StockTwits 엔터프라이즈 개인 자격 문의 결과(사용자 발송).

## Figma 후속

`figma-plugin/README.md` 참조. v3에 F13 항목(🏛️ 버튼·모달·토스트·표지 8장)이 추가됐고 아직 실행 전이다. 반영 실행 후 로그·스크린샷 대조 → 어긋난 곳은 `code.js`의 `ID` 표와 `TEXT_EDITS`만 고쳐 재실행. 파일명 "제목 없음" → Stockholm은 수동.

## 문서 갱신 규칙

결정이 바뀌면 코드보다 [`PROJECT.md`](../PROJECT.md)를 먼저 고친다([확정]/[제안]/[확인 필요] 표기 유지). 이 파일은 "지금 상태"와 "다음 작업"만 유지하고, 끝난 항목은 지운다.
