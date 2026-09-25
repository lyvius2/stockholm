# 개발 계획 — 단계별 착수 순서와 완료 기준

작성: 2026-09-25 / 상태: **[확정 2026-09-25, 단계 구성은 PROJECT.md 12장]** — 각 단계의 세부 순서·완료 기준·읽을 문서는 이 문서가 기준. 진행 상태는 [HANDOFF.md](HANDOFF.md) / 관련: [PROJECT.md](../PROJECT.md) 12장, [CLAUDE.md](../CLAUDE.md), [INDEX.md](INDEX.md)(문서 지도)

## 1. 원칙

- **핵심부터, 돈이 걸린 순서의 역순으로.** `core`(순수 도메인, TDD) → 어댑터(학습 테스트) → 화면 → 자동화. 실제 주문은 2단계 끝에서 사용자가 지정한 최소 금액으로 딱 한 번 검증한다.
- **단계마다 "쓸 수 있는 것"이 하나 생긴다.** 1단계 끝: 데몬이 뜨고 마법사가 돈다. 2단계 끝: 수동 매매가 된다(첫 사용 가능 버전). 3단계 끝: 정보가 쌓인다. 4단계 끝: 토론한다. 6단계 끝: 모의 실행이 돈다.
- **단계 안의 순서는 의존 관계 순.** 번호 목록이 착수 순서다. 같은 번호 안의 항목은 병렬 가능.
- **한 작업의 리듬**: 읽을 문서 → 테스트 먼저(`core`) 또는 학습 테스트(어댑터) → 구현 → `./gradlew build` 통과 → HANDOFF 갱신 → 커밋(사용자 요청 시). 브랜치는 `feat/…`·`fix/…`, 커밋은 Conventional Commits.
- **완료 기준(DoD)은 테스트로 표현**한다. 사람이 눈으로 확인하는 항목은 "수동 확인"이라고 적는다.
- 외부 API는 **학습 테스트**(실제 호출, 읽기 전용, 결과를 픽스처로 저장)로 규격을 확정한 뒤 WireMock 통합 테스트로 고정한다. 학습 테스트는 `@Tag("learning")`으로 분리해 CI에서는 돌리지 않는다.

## 2. 0단계 — 착수 전 확인 (반나절)

| 항목 | 상태 | 비고 |
|---|---|---|
| GraalVM for JDK 25(배포판 Community 제안), Node 22, Gradle wrapper, Xcode CLT | 설치 확인 | Apple Silicon(arm64 빌드). 첫날 Kotlin `jvmTarget=25`·Gradle 실행 지원·jlink 동작 확인, 안 맞으면 21로 임시 후퇴 |
| 키 확보: DART, KRX, Massive, 공공데이터포털, 네이버, FRED, 토스(본인), 금융결제원 | 대부분 확보(FRED 2026-09-25) | 값은 Keychain·환경 변수로만. 저장소·대화에 넣지 않는다 |
| 토스 허용 IP 등록(집 회선) | 사용자 | 2단계 전 |
| Figma·화면 설계서 최신 확인 | v34 | F19 플러그인 재실행 대조 대기 |
| 미결 결정 | HANDOFF "미결 항목" | ETF 국내 데이터 제공자, F17 화면 |

## 3. 1단계 — 리포 골격 (목표 1~2주)

**목표**: 빌드·테스트·경계 검증이 도는 단일 Spring Boot 프로젝트와 Electron 셸. 데몬이 `127.0.0.1`에 뜨고, 마법사(F19)가 끝까지 돌며, 로그인 모달 뒤에 빈 메인이 열린다.
**읽을 문서**: CLAUDE.md 전체, [DIRECTORY_STRUCTURE.md](DIRECTORY_STRUCTURE.md), [TECH_STACK.md](TECH_STACK.md), [CORE_DOMAIN.md](CORE_DOMAIN.md) 1~3·9장, [DB_SCHEMA.md](DB_SCHEMA.md) 3·4·5장, [FIRST_RUN_DESIGN.md](FIRST_RUN_DESIGN.md), [KEY_MANAGEMENT.md](KEY_MANAGEMENT.md) 5장.

착수 순서:

1. `backend/` Gradle(Kotlin DSL) + **Kotlin 2.x + Spring Boot 4.x** + GraalVM JDK 25 toolchain(`vendor = GRAAL_VM`) + Spotless(ktfmt). 루트 `banghak.stock`, 헥사고날 계층 골격 `core(domain·usecase·port)` / `engine(application·adapter·config)` / `relay` / `shared`(DIRECTORY_STRUCTURE 3장, 빈 패키지는 자리만). 프로필 `engine`/`relay`. `shared/config`에 가상 스레드 Executor·`Semaphore` 빈·OkHttp/Retrofit 공통·Resilience4j 레지스트리·CacheConfig(Caffeine, Valkey/Redis 전환) 뼈대. 로컬 API `127.0.0.1:2609`.
2. **경계 검증 테스트를 코드보다 먼저**: ArchUnit(헥사고날 의존 방향, `core` 프레임워크 import 금지, 진입 어댑터는 usecase만, `engine`↔`relay` 상호 참조 금지, `@Profile` 필수, Retrofit 인터페이스 호출마다 fallback 존재, 주문 API는 `adapter.out.toss.TossOrderClient`에만), Spring Modulith 모듈 검증, 프로필별 컨텍스트 기동 3종 + `relay`만 켰을 때 주문·증권사·LLM 빈 부재. 이 테스트가 빨간 상태로 시작한다.
3. `core` 값 객체 TDD: `Money`(HALF_EVEN, 통화 scale), `Quantity`(KR 정수·US 6자리), `Symbol`, `UserId`/`DeviceId`(ULID), `ClientOrderId`(36자·결정적 생성 26자), `Percent`, `ExchangeRate`. 경계값 테스트 전부.
4. `core` 이벤트 로그: `EventEnvelope`, `DomainEvent` sealed 목록(빈 record라도 전부), `EventStore` 포트 + 메모리 fake, `(user, device)`별 seq·`LOCAL/USER/FAMILY` 범위 표.
5. 영속성 기반: SQLite JDBC + Hibernate + jOOQ(코드 생성 없음, 같은 DataSource·트랜잭션) + Flyway, 자리표시자(`${decimal}`…), `AttributeConverter`(BigDecimal↔TEXT, Instant↔ISO-8601), 연결 초기화 SQL(WAL·`foreign_keys=ON`·busy_timeout). **V1 마이그레이션**: DB_SCHEMA 4·5장 + `stock_master`·`stock_warning`·`exchange_rate`·`market_calendar`. 마이그레이션 골든 테스트, 정밀도 테스트(17자리), FK PRAGMA 테스트.
6. `SecretStorePort` + macOS Keychain 어댑터(`security` CLI 또는 JNA, 쓰기 전용) + 메모리 fake. 표식 값이 어떤 출력에도 없는지 테스트.
7. 데몬 기동: 로컬 토큰 파일, 헬스 엔드포인트, `/setup/state`·`/setup/admin`·`/setup/keys/{kind}`·`/setup/toss`·`/setup/complete`, `COMPLETE` 전 403 필터. `CredentialVerifier` 포트 + 종류별 검증 어댑터는 **형식 검사 수준으로 먼저**(실제 호출은 2·3단계에서 어댑터가 생기며 교체).
8. 사용자·세션: Argon2id, TOTP(RFC 6238 직접 구현 + 테스트 벡터, `Clock` 주입), 복구 코드, 로그인 잠금, 세션 토큰(디바이스 바인딩), step-up 표시. QR 생성 의존성(ZXing) 결정. `/me/password`·`/me/totp`·`/me/recovery-codes`·`/admin/members`·`/admin/credentials`(F21 API, 화면은 2단계).
9. `desktop/` Electron + React + TS(`strict`, `contextIsolation` 켬): 데몬 기동·접속, 마법사 4단계 화면, 로그인 모달(바깥 흐림), 빈 메인 네 영역과 두 줄 상단 바 골격, 테마 토큰·얇은 스크롤바. 데이터 접근 계층 한 겹(로컬 모드).
10. `protocol/` JSON Schema 자리 + Java·TS 타입 생성 파이프라인(이벤트 payload 스키마 1개로 시작).
11. `scripts/dist.sh` 뼈대(bootJar → jlink → electron-builder → dmg), 메모리 실측(`-Xmx384m`, 상주 400MB 이하 기록).
12. CLAUDE.md "명령어" 절을 실제 명령으로 갱신. HANDOFF 갱신.

**완료 기준**
- `./gradlew build` 녹색(경계 테스트 포함). `npm run lint`·`vitest` 녹색.
- 빈 SQLite에 V1 적용 → 골든 스키마 일치. 표식 비밀값 검색 0건.
- 수동 확인: 마법사 ①~④ 완주 → `COMPLETE` → 로그인 모달 → 빈 메인. 앱을 중간에 껐다 켜면 같은 단계에서 이어짐.
- dmg가 만들어지고 다른 Mac에서 뜬다(서명 없음).

## 4. 2단계 — 토스 연동과 수동 매매 (목표 4~6주) → 첫 사용 가능 버전

**목표**: F1 매수·매도, F3 차트, F4 종목 검색·관심종목, F11 급등락, F14 주문 관리, F16 자산 조회, F18 평가금액, F19 시작 종목, F20 거래내역(손익·체결·매매), F23 지수 티커. 승인 기반 소액 실주문 1회 검증.
**읽을 문서**: [EXTERNAL_APIS.md](EXTERNAL_APIS.md) 1.1·1.2, PROJECT 8.1·F1·F3·F4·F11·11.3, [ORDER_MANAGEMENT_DESIGN.md](ORDER_MANAGEMENT_DESIGN.md), [PORTFOLIO_PANEL_DESIGN.md](PORTFOLIO_PANEL_DESIGN.md), [TRADE_HISTORY_DESIGN.md](TRADE_HISTORY_DESIGN.md), [ASSET_DESIGN.md](ASSET_DESIGN.md), [FIRST_RUN_DESIGN.md](FIRST_RUN_DESIGN.md) 6장, [KRX_DESIGN.md](KRX_DESIGN.md), [DB_SCHEMA.md](DB_SCHEMA.md) 6·7장.

착수 순서:

1. **토스 학습 테스트**(읽기 전용): 토큰 발급, 계좌 목록, 보유·예수금·매수 가능 금액(평가금액·원화 환산·D+1/D+2 필드 확인), 종목 정보·경고, 캔들(1분·일), 현재가 다건, 랭킹, 시장 달력, 환율, 시장 지표(지수 — 해외 지수 범위 확인), WebSocket(trade·orderbook·`personal:order`). 응답을 픽스처로 저장, WireMock에 적재. **주문 엔드포인트는 학습 테스트에서도 부르지 않는다.**
2. `core` 주문·보유 TDD: `OrderIntent`·`OrderKind`·`TimeInForce`·`OrderOrigin`·`OrderTrigger`, `BrokerOrder`(정정 체인·`canAmend/canCancel/remaining`), `OrderStatus` 매핑·`UNKNOWN`, `Lot`·`Position`·`ProfitLoss`, `PortfolioSnapshot`·`DepositBalance`, `StartStockResolver`(⑴⑵⑶, 2초 상한, fake 포트). 수동 주문 가드레일(`MarketOrderScope`, 고액 확인, 반대 방향 미체결 409 처리).
3. V2 마이그레이션: `candle`, `broker_order`, `lot`, `lot_disposal`, `portfolio_cache`, `notification`, KRX 세 표, `dart_corp`·`edgar_entity`·`us_ticker_ref`.
4. 토스 어댑터: `TradingPort`(주문·정정·취소·조회, `clientOrderId` 멱등·10분 규칙·타임아웃 시 조회 후 결정), `MarketDataPort`, `MarketCalendarPort`, `RealtimeFeedPort`(재연결·`OPEN` 재동기·LOSSY 보정), 그룹별 rate limiter, 403(IP)·429 도메인 예외. 종목 마스터 동기화(토스 + KRX + DART 기업개황 → `stock_master`), `stock_warning` 짧은 TTL.
5. 데몬 서비스: 분봉 집계(1분→3·5·10·30·60·주·월·년), 이동평균·거래량 평균, 로컬 WebSocket(초당 4회 묶음), 주문 서비스(모달 → 가드레일 → 주문 → `personal:order` → `broker_order`·`lot` projection), 정정·취소(체인, 한도 초과 확인 창은 6단계 전까지 항상 통과), 주문 내역 커서 적재, lot 선입선출 매칭(`lot_disposal`)과 실현손익·환차손익 계산(F20 손익 탭), F18 계산(`BigDecimal`), F19 시작 종목·`lastViewedStock` 디바운스, 급등락 재정렬(1d 랭킹 100 → 현재가 다건).
6. `CredentialVerifier` 토스·KRX·Massive·DART·네이버·공공데이터포털·Slack 실제 검증 호출로 교체(형식 검사 → 실호출).
7. 금융결제원: 학습 테스트(테스트베드/운영 확인) → `IdentityPort`·`AssetPort` 어댑터(동의 브라우저·`127.0.0.1` 콜백·토큰 Keychain) → 자산 모달. 조회 결과는 저장하지 않는다.
8. 화면: 네 영역(차트 두 모드·기간 탭, 가격·호가·상태 칩·ⓘ 버튼 자리, 3번 영역 세 탭·주문 모달·정정 모달·취소 확인, 토론 영역은 자리만), 서랍(관심종목·급등락, 좌우 배타 규칙), 종목 검색 팝오버(초성), 사용자 드롭다운, 평가금액 패널, 거래내역 패널(세 탭·기간 단위, F18과 40:60), F21 세 모달(회원정보 변경·알림 설정·회원 관리/공유 키), 조회 제한 모드 배너, 사유 토스트, 설정 저장·복원(비율·토글·서랍 폭).
9. **실주문 검증(사용자 지시 시에만)**: 사용자가 지정한 종목·1주·최소 금액으로 지정가 매수 → 정정 → 취소 → 소량 매도. 각 단계의 `personal:order` 이벤트와 projection 일치 확인. 이 절차를 문서로 남긴다.

**완료 기준**
- 토스 WireMock 통합 테스트: 주문·정정·취소·타임아웃·409·403·429 경로 전부. 주문 경로 단위 테스트는 fake `TradingPort`만.
- 화면 테스트(Testing Library): 모달 잠금(가드레일 미통과), 정정 수량 상한 = 잔량, 서랍 배타, 시작 종목 토스트, 로그아웃 후 DOM에 직전 사용자 데이터 없음.
- 수동 확인: 장중 1시간 실시간 갱신에 끊김·지연 표시 동작. 메모리 400MB 이하.
- 실주문 검증 1회 완료 기록.

## 5. 3단계 — 데이터 수집과 RAG (목표 4주)

**목표**: 공시·뉴스·수급·연기금·재무·배당이 쌓이고 검색된다. F12 커뮤니티 서랍(링크아웃·뉴스·수급·메모), F13 연기금 모달·푸시, F15 종목 정보 서랍.
**읽을 문서**: [RAG_DESIGN.md](RAG_DESIGN.md), [DART_DESIGN.md](DART_DESIGN.md), [EDGAR_DESIGN.md](EDGAR_DESIGN.md), [KRX_DESIGN.md](KRX_DESIGN.md), [NPS_HOLDINGS_DESIGN.md](NPS_HOLDINGS_DESIGN.md), [STOCK_INFO_DESIGN.md](STOCK_INFO_DESIGN.md), [EXTERNAL_APIS.md](EXTERNAL_APIS.md) 2·3장, [DB_SCHEMA.md](DB_SCHEMA.md) 9장.

착수 순서:

1. 학습 테스트: DART(공시검색·기업개황·전체 재무제표·배당·원문 zip), EDGAR(submissions·companyfacts·frames·13F index, 연락처 UA), KRX 8종(전송 방식·값 형식·갱신 시각), Massive(배당·개요·뉴스·공매도, 분당 5회), 공공데이터포털(연도별 uddi·컬럼 드리프트), RSS·네이버. 픽스처 저장.
2. V3 마이그레이션(DB_SCHEMA 9장 전부).
3. `knowledge_document` 수집 파이프라인: 소스 어댑터(`DisclosurePort`·`NewsPort`·`FundamentalsPort`·`PensionHoldingsPort`) → 정규화(`publishedAt`/`firstSeenAt`/`backfilled`, SimHash 중복, `supersedes`) → 저장 → 색인 큐. `source=naver` 일괄 삭제 경로.
4. Lucene: Nori BM25 + HNSW(Ollama `bge-m3`, int8), `asOf` 필수 필터, scope·`userId` 강제 필터, 인덱스 메타(모델·차원 불일치 시 기동 중단), 재색인 명령. 검색 평가 세트 30건.
5. 배치·감시: 공시 감시(DART 10분·EDGAR), 종목 마스터 일 1회, KRX 07:00, NPS 06:30(cache-aside 24h), 뉴스 5~10분, 재무·배당 갱신, 수급 스냅샷(장 마감 후), 보존 정리(커뮤니티 90일·뉴스 2년). 스케줄은 `Clock`·시장 달력 기반.
6. F13: 13F·연간 적재, CUSIP·회사명 매핑, 변화 감지 → `notification` 푸시(배지·토스트·macOS 알림, Slack은 사용자 설정), 모달 네 탭.
7. F15: 재무제표(`financial_fact` → `financial_statement`), 배당 탭(주기·직전 배당·평균 수익률·1년 추이·표), 산업군(업종·순위·업종 대비), 관련 종목(`related_symbol` 자동 + 사용자 편집 상한 14), 공시 탭. ETF는 "구성종목 미제공" 안내.
8. F12: 커뮤니티 서랍(링크아웃 버튼, 뉴스 목록 RSS+네이버 URL 중복 제거, 수급 요약 카드, 가족 메모). StockTwits는 링크아웃만.
9. 악재 공시 → `auto_buy_exclusion(DISCLOSURE)` 자동 추가(해제는 사람만).

**완료 기준**
- 각 어댑터 WireMock 테스트(정상·정정·컬럼 변형·429·차단). `asOf` 필터가 미래 문서를 절대 돌려주지 않는 테스트. `userId` 필터 강제 테스트.
- 재색인 후 검색 결과 동일. 평가 세트 재현율@10 기록.
- 수동 확인: 관심 종목 20개 기준 하루 수집 후 F12·F13·F15 화면이 찬다. 공공데이터포털·EDGAR 한도 안.

## 6. 4단계 — 토론·추천·리포트 (목표 4~6주)

**목표**: F6 토론 세 테마(빠른 토론, 메신저 UI, 개입, 저장·재개), 개요·전망 요약, F5 추천 2단 깔때기, F9 일간·주간 리포트. 주문 미연결.
**읽을 문서**: [LLM_ROUTING.md](LLM_ROUTING.md), [DEBATE_DESIGN.md](DEBATE_DESIGN.md), [MIROFISH_EXPERIMENT_GUIDE.md](MIROFISH_EXPERIMENT_GUIDE.md), [DB_SCHEMA.md](DB_SCHEMA.md) 10장.

착수 순서:

1. `LlmPort`(Spring AI) + 제공자 어댑터 4종(OpenAI·Claude·DeepSeek·Ollama), `ProviderRegistry`(Keychain 키), `RouteTable`(프리셋·디바이스 덮어쓰기, 실행 시 VERIFIED 제공자만), F22 LLM 경로 모달(admin, 등록 제공자만 선택, 검증·이력), 폴백·실패 정책(`SELL_DECISION`은 판단 보류, `SURGE_CATALYST_CHECK`는 매수 안 함), 개인정보 등급 필터, `UsageLedger`·예산(80% 알림). 제공자·모델 이름은 `engine.llm` 밖에 없다는 ArchUnit.
2. V4 마이그레이션.
3. `core` 토론 모델: `PersonaDefinition`(버전), `DebateSession` 애그리거트(`apply(event)`), `Utterance`·`Intervention`·`Verdict`, 사회자 등급 강제(`[S]/[N]/[C]`), 재개 규칙(`firstSeenAt > 마지막 결론`).
4. 빠른 토론 엔진: 자료 조립(RAG `asOf` + 수치 스냅샷 블록 + 국민연금 줄), 페르소나 턴(페르소나별 경로), 라운드·개입 처리, 결론 구조화 → 스키마 검증, 실시간 스트림(로컬 WebSocket).
5. 개요·전망 요약(주 1회 배치 + 최초 열람 생성), 업종 요약.
6. F5 스크리닝(코드) → 후보 → 약식 토론 → `recommendation` 저장. F9 리포트 생성(장 전 배치, Slack 요약).
7. 화면: 4번 영역 상태 흐름(개요 → 테마 선택 → 진행 중 메신저 UI → 기록·재개), 추천 화면, 리포트 화면, 페르소나 편집.
8. 미러피시 **사전 실험**(별도, MIROFISH 가이드): API 단독 구동·포트·경로·Zep 한도. 결과를 PROJECT D13에 반영. 어댑터 구현은 9단계.

**완료 기준**
- 토론 단위 테스트는 fake `LlmPort`(대본)로. 결론 스키마 검증 실패 시 `Verdict` 미생성 테스트. 재개 시 이전 결론 이후 자료만 투입되는 테스트.
- 예산 초과·전 제공자 실패 시 정책 테스트. 프롬프트·응답 전문이 원장에 없는 테스트.
- 수동 확인: 종목 5개로 세 테마 각 1회, 개입 5종, 재개 1회. 1분 안팎.

## 7. 5단계 — 기록·대조와 학습 기반 (목표 2주)

**목표**: 추천·결론·리포트 전망을 1·5·20일 뒤 실제와 대조, 페르소나 성적표, 회고, F17 매매 통계·F2 기간별 손익 화면.
**읽을 문서**: PROJECT F2·F10·F17, [DEBATE_DESIGN.md](DEBATE_DESIGN.md) 6장, [DB_SCHEMA.md](DB_SCHEMA.md) 10장(`prediction_outcome`·`persona_weight*`·`retrospective`).

1. V5 마이그레이션. 2. 대조 배치(`Clock`·시장 달력, 국면 태깅). 3. (페르소나, 모델) 집계와 가중치(최소 표본, 이력). 4. 회고 생성(`RETROSPECTIVE`) → RAG USER/FAMILY. 5. F2·F17 화면 설계(목업 먼저, 후속 [확정 2026-09-25]) → 구현. 6. 성적표 화면.

**완료 기준**: 고정 시계로 1·5·20일 경계 테스트, 최소 표본 미만이면 가중치 불변 테스트, FAMILY 회고에 금액·수량 없음 테스트. 수동 확인: 30일치 기록으로 화면 채움.

## 8. 6단계 — 가드레일과 자동화 (모의 실행 → 승인 후 실행) (목표 4~6주)

**목표**: F7·F8을 **모의 실행**으로 켜고, 검증 뒤 **승인 후 실행**을 연다. 완전 자동은 9단계. Slack 알림(클라이언트 직접 발송).
**읽을 문서**: PROJECT 10장 전체, [CORE_DOMAIN.md](CORE_DOMAIN.md) 6·7장, [DB_SCHEMA.md](DB_SCHEMA.md) 8장, CLAUDE.md 절대 규칙 3·4.

착수 순서:

1. **`core` 가드레일 TDD 완결**(경계값 전부: 1000만원·168시간·90%·50%·8종목·분당 5회·예수금 하한·시장별 창·미국 개장 직후/마감 직전 10분·상승률 상한·제외 필터·스냅샷 나이): `GuardrailLimits.lowerTo`, `AutoBuyExposure`, `AutoSellPermission`, `KillSwitch`, `DuplicateIntent`(체인 한 건), `AutoExposureOverCapNotice`(사람 정정의 유일한 예외).
2. V6 마이그레이션.
3. 실행 단계 기계(`ExecutionStage`): 모의 실행(주문 없이 `simulated_order` 기록·가상 손익), 승인 후 실행(`approval_request` 만료·step-up 승인), 킬 스위치(수동·연속 손실·일일 손실). 자동화는 lease 보유 디바이스만(단독 모드 `AlwaysHeldLease`).
4. 트리거: (a) 추천 → 빠른 토론 → 분할 매수, (b) 급등 탐지기(규칙) + `SURGE_CATALYST_CHECK`. 자동 매도 판단(`SELL_DECISION`, 순손익 기준, 90% 상한, 손절 옵션).
5. 주문 직전 3중 방어(lease·결정적 `clientOrderId`·미체결/당일 체결 조회), 결과 불명 시 조회 후 결정, 자동 매수 직후 OCO 옵션(기본 꺼짐).
6. Slack `NotifierPort`(봇 토큰 공유 키, 사용자별 DM, 야간 방해 금지, 수량·금액 포함 설정), 알림·승인 센터 화면, 자동화 설정 화면(단계 상향에 모의 성적 표시).
7. 모의 실행을 **최소 4주** 돌린 뒤 사용자 판단으로 승인 후 실행 개방(시장·방향별).

**완료 기준**
- 가드레일 규칙 100% 경계값 테스트. "우회 플래그 없음" ArchUnit(가드레일 호출을 건너뛰는 주문 경로 부재). 한도 상수는 설정으로 낮출 수만 있음 테스트.
- 모의 실행 4주 성적표. 승인 요청 만료·거부·승인 경로 테스트. 킬 스위치 즉시 정지 테스트.
- 실주문은 승인 후 실행 단계에서만, 사용자가 승인한 건만.

## 9. 7단계 — relay 서버 (목표 4주)

**목표**: 회원·디바이스·인증, 원격 컨트롤(웹 UI 원격 모드), 서버 경유 Slack. `relay` 프로필, 별도 DB.
**읽을 문서**: PROJECT 3·5·7장·D2·D4·D11·D12, [DB_SCHEMA.md](DB_SCHEMA.md) 11장, [KEY_MANAGEMENT.md](KEY_MANAGEMENT.md) 3.3.

1. `db/relay/` V1(11장 표), MySQL 프로필(Testcontainers). 2. WebSocket(TLS) 봉투 라우팅(COMMAND·EVENT·QUERY·SNAPSHOT·NOTIFY), nonce·만료, 디바이스 챌린지 서명. 3. 회원 등록부 반영(4명 검증), 디바이스 등록 요청·승인(기존 디바이스가 승인·키 포장), 세션·step-up 중계, **TOTP는 클라이언트가 검증**(relay 중계). 4. 원격 웹 모드(같은 React 앱, 데이터 접근 계층만 교체, step-up 추가). 5. Slack 서버 발송(앱 꺼져도 체결 알림). 6. Mac mini 겸용(`engine,relay`) 기동 테스트, 배치(Tailscale/Cloudflare Tunnel). 7. 토스 허용 IP 문제(밖의 Mac)의 해법 결정.

**완료 기준**: `relay`만 켰을 때 주문·증권사·LLM 빈 0개(이미 1단계 테스트). 서버 DB에 평문 업무 데이터·키·시드 없음(표식 검색). 원격에서 주문 승인 1회(step-up).

## 10. 8단계 — 멀티 클라이언트 동기화·lease·가족 공유 (목표 4주)

**읽을 문서**: PROJECT 6장, [CORE_DOMAIN.md](CORE_DOMAIN.md) 9·10장, [DB_SCHEMA.md](DB_SCHEMA.md) 5·8·11장.

1. V8(`lease`·`sync_cursor` 활성화). 2. 동기화 에이전트(`USER`·`FAMILY` 범위만, 커서 교환, 암호문 봉투, 메일박스 store-and-forward), projection 재생기(LWW·보수적 한도). 3. 키 전달(새 디바이스 공개키로 포장), 가족 공유 키 교체. 4. lease(갱신·상실 시 자동화 자진 중단·강제 인수·펜싱 번호), 토스 토큰 공유(사용자 동기화 키). 5. 학습 기록 가족 공유(금액·수량 제외 검사기).

**완료 기준**: 두 디바이스 시뮬레이션(프로세스 2개)에서 이벤트 교환·재생 일치, lease 경합 시 이중 주문 0건(fake 브로커), `LOCAL` 이벤트 미전송, 가족 공유 payload에 금액 패턴 0건.

## 11. 9단계 — 미러피시 어댑터·완전 자동 검토

1. 사전 실험 결과에 따라 `SimulationPort` 어댑터(관리형 설치: `uv`·Python·고정 커밋, 자식 프로세스, 외부 주소 모드), 심층 토론 백그라운드·알림, 시드 문서(여론·수급 절). 2. 완전 자동 단계 개방 여부는 6단계 성적과 사용자 판단으로. 개방하더라도 시장·방향별로 따로.

## 12. 단계 간 병렬과 순서 예외

- 3단계의 학습 테스트(1번)는 2단계 중에 미리 돌려 둘 수 있다(키가 있고 읽기 전용이라 위험 없음).
- 4단계의 `LlmPort`(1번)는 3단계 뒤에 바로 시작 가능하며, 3단계 F13·F15 화면과 병렬이다.
- 화면 작업은 각 단계의 데몬 API가 굳은 뒤 시작한다. 목업(화면 설계서)이 있으므로 API 계약을 먼저 `protocol/`에 쓰고 양쪽이 동시에 간다.
- **순서를 바꾸지 않는 것**: `core` 가드레일 TDD(6단계 1번)보다 자동화 실행기를 먼저 만들지 않는다. 실주문 검증(2단계 9번)보다 먼저 자동화를 켜지 않는다.

## 13. 진행 관리

- HANDOFF.md의 "지금 상태"에 **단계·번호**(예: "2단계 4번 진행 중, 토스 어댑터 `TradingPort.amend`까지")로 적는다.
- 단계가 끝나면 이 문서의 완료 기준을 하나씩 체크한 결과를 HANDOFF에 남기고 PROJECT.md 12장의 단계 표기를 갱신한다.
- 예상 기간은 1인 기준 참고값이다. 늘어나면 범위를 줄이지 말고 기간을 늘린다(가드레일·테스트를 빼지 않는다).
