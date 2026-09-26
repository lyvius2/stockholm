# 디렉터리 구조 (기술 설계 1) — Kotlin · Hexagonal(계층별 패키지)

> 문서 지도: [docs/INDEX.md](INDEX.md) · 기준 문서: [PROJECT.md](../PROJECT.md) · 작업 규칙: [CLAUDE.md](../CLAUDE.md) · 개발 순서: [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md)

작성: 2026-09-25 / 개정: 2026-09-26 — Kotlin·Spring Boot 4.x·루트 `banghak.stock`·Hexagonal 반영. **도메인별 패키지 분할은 사용자 결정으로 취소(2026-09-26)**, 계층별 패키지(`core` = domain + port, `engine`/`relay` = application + adapter + config)로 간다 / 상태: **[확정 골격, 세부 [제안]]** / 관련: [TECH_STACK.md](TECH_STACK.md), [PROJECT.md](../PROJECT.md) 11.1, [CORE_DOMAIN.md](CORE_DOMAIN.md), [DB_SCHEMA.md](DB_SCHEMA.md)

## 1. 원칙

1. **헥사고날, 계층별 패키지.** 안쪽 `core`(도메인 + 포트)는 프레임워크를 모르고, 바깥 `engine`/`relay`(usecase 구현·어댑터·설정)가 `core`에 의존한다. 의존 방향은 `adapter → application → core.usecase / core.port → core.domain`뿐이다(ArchUnit).
2. **`core` 안은 개념(concept)별 하위 패키지**(`money`, `trading`, `guardrail` …)로 나누되, 이는 이름 공간일 뿐 모듈 경계가 아니다. 포트는 `core.port`(out)·`core.usecase`(in) 한곳.
3. **어댑터는 외부 시스템 이름으로**(`adapter/out/toss`, `adapter/out/dart`), **진입 어댑터는 방식 이름으로**(`adapter/in/web`, `ws`, `scheduler`).
4. **프로필 경계**: `engine`(데몬)과 `relay`(서버)는 서로를 참조하지 않고 `core`·`shared`만 본다. 프로필 없는 빈은 `shared`에만.
5. **생성물은 커밋하지 않는다**(`protocol/` → `generated`). 비밀·로컬 산출물은 `.gitignore`.
6. **테스트는 대상과 같은 패키지에.** 학습 테스트는 `learning/`에 `@Tag("learning")`.

## 2. 저장소 루트

```
stockholm/
├─ AGENTS.md  CLAUDE.md  PROJECT.md  README.md
├─ .gitignore  .editorconfig  .github/workflows/ci.yml
├─ backend/        # 단일 Spring Boot 4.x 프로젝트, Kotlin (3장)
├─ desktop/        # Electron + React + TypeScript (4장)
├─ protocol/       # JSON Schema와 타입 생성 (5장)
├─ scripts/        # dev.sh  dist.sh  learning-tests.sh  db-dump.sh
├─ docs/           # 설계 문서(INDEX.md 색인), screens/
├─ figma-plugin/   # (gitignored)
└─ docs/external/  # (gitignored)
```

## 3. `backend/`

```
backend/
├─ build.gradle.kts  settings.gradle.kts  gradle.properties  gradle/libs.versions.toml  gradle/wrapper/
└─ src/
   ├─ main/kotlin/banghak/stock/
   │  ├─ StockholmApplication.kt              # @SpringBootApplication, 프로필 없으면 기동 실패
   │  │
   │  ├─ core/                                # ── 안쪽. Spring·JPA·Jackson·HTTP 클라이언트 import 금지
   │  │  ├─ domain/                           #   값 객체·엔티티·규칙·도메인 이벤트 (data class · value class · sealed interface)
   │  │  │  ├─ money/        Money  Currency  ExchangeRate  Percent  Quantity  RoundingRules
   │  │  │  ├─ market/       Market  Symbol  StockFlags  Candle  CandleInterval  IndexCode  IndexQuote  MarketSession  TradingCalendar
   │  │  │  ├─ identity/     UserId  DeviceId  Role  Ulid(순수 인코더, 난수·시계는 밖에서 받음)
   │  │  │  ├─ trading/      OrderIntent  OrderSide  OrderKind  TimeInForce  OrderOrigin  OrderTrigger  ClientOrderId  BrokerOrder  OrderStatus
   │  │  │  │                OrderAmendment  Fill  DuplicateIntent  StartStockResolver  StartStock
   │  │  │  ├─ portfolio/    Lot  LotId  BuyOrigin(있음)  LotDisposal  FifoMatcher  Position  PortfolioSnapshot  DepositBalance  ProfitLoss  RealizedPnl
   │  │  │  ├─ guardrail/    GuardrailLimits  GuardrailRule  rules/(11개)  AutoBuyExposure  GuardrailVerdict  Violation  AutoExposureOverCapNotice
   │  │  │  ├─ automation/   StrategyId  ExecutionStage  AutomationScope  AutomationSetting  KillSwitch  KillReason  AutoSellPermission  ApprovalRequest  SimulatedOrder  SurgeSignal
   │  │  │  ├─ eventlog/     EventEnvelope  DomainEvent(sealed, 이벤트 36개는 같은 파일)  SyncScope  SyncScopes(종류→범위 표)
   │  │  │  ├─ lease/        LeaseState
   │  │  │  ├─ debate/       PersonaDefinition  PersonaId  DebateSession  DebateTheme  Utterance  Intervention  InterventionKind  Verdict  Conclusion  CitationRef
   │  │  │  ├─ knowledge/    KnowledgeDocument  DocType  Scope  Grade  SearchQuery(asOf 필수)  SearchHit  SentimentSnapshot
   │  │  │  ├─ pension/      PensionDataset  PensionHolding  PensionChange
   │  │  │  ├─ recommend/    Recommendation  RecommendationId  ScreeningResult
   │  │  │  ├─ report/       MarketReport  Forecast
   │  │  │  ├─ learning/     PredictionOutcome  PersonaWeight  Retrospective
   │  │  │  ├─ account/      SecretScope  SecretKey  SecretValue(있음)  SetupState  CredentialKind  CredentialCheck  UserSettingKey  NotificationPolicy
   │  │  │  ├─ notification/ Notification  NotificationKind
   │  │  │  ├─ llm/          LlmPurpose  LlmCategory  Sensitivity  LlmRequest  LlmResponse   # 제공자·모델 이름 없음
   │  │  │  └─ error/        DomainException  InvalidValueException  CurrencyMismatchException  SecretMissingException  SecretStoreFailureException  (GuardrailViolationException  BrokerUnavailableException  OrderRejectedException …)
   │  │  ├─ usecase/                          #   port.in — 진입 어댑터가 부르는 usecase 인터페이스 (Command/Query)
   │  │  │                   PlaceOrderUseCase  AmendOrderUseCase  QueryTradeHistoryUseCase  ResolveStartStockUseCase  EvaluateGuardrailUseCase
   │  │  │                   RunQuickDebateUseCase  RecommendUseCase  GenerateReportUseCase  SetupWizardUseCase  LoginUseCase  ManageMembersUseCase
   │  │  │                   ConfigureLlmRoutesUseCase  QueryPortfolioUseCase  IndexTickerUseCase  …
   │  │  └─ port/                             #   port.out — 외부 세계 인터페이스
   │  │                      TradingPort  MarketDataPort  MarketCalendarPort  RealtimeFeedPort  DisclosurePort  NewsPort  CommunityPort  FundamentalsPort
   │  │                      PensionHoldingsPort  MacroIndicatorPort  LlmPort  EmbeddingPort  SimulationPort  NotifierPort  SecretStorePort
   │  │                      CredentialVerifier  IdentityPort  AssetPort  UserSettingsPort  EventStore  LeaseHolder  (Clock은 java.time.Clock)
   │  │
   │  ├─ engine/                              # ── @Profile("engine") 클라이언트 데몬 (바깥)
   │  │  ├─ application/                      #   usecase 구현 = service (@Service, @Transactional). 개념별 하위 패키지
   │  │  │  ├─ account/      SetupService  UserService  PasswordHasher  TotpService  RecoveryCodes  SessionService  StepUpService  CredentialService  MemberAdminService  RegistrationCodes
   │  │  │  ├─ market/       StockMasterSync  StockWarningCache  CandleAggregator  IndexTickerService  RankingService
   │  │  │  ├─ trading/      OrderService  AmendService  TradeHistoryService  StartStockService
   │  │  │  ├─ portfolio/    PortfolioProjection  RealizedPnlCalculator  PortfolioPanelService
   │  │  │  ├─ guardrail/    GuardrailEvaluator(EvaluateGuardrailUseCase 구현, 규칙 조립)
   │  │  │  ├─ automation/   AutomationExecutor  ExecutionStageMachine  ApprovalService  SurgeDetector  AutoSellDecider  KillSwitchService
   │  │  │  ├─ research/     DisclosureWatcher  NewsCollector  FinancialStatementBuilder  DividendService  IndustryProfileService  PensionChangeDetector  PensionSymbolMapper
   │  │  │  │                IngestPipeline  Deduplicator  HybridSearchService  RetentionJob  SentimentSnapshotJob
   │  │  │  ├─ debate/       QuickDebateEngine  Moderator  MaterialAssembler  DebateSessionService  OutlookDigestService  IndustryDigestService  RelatedSymbolsService
   │  │  │  ├─ recommend/    Screener  RecommendationService
   │  │  │  ├─ report/       DailyReportService  WeeklyReportService
   │  │  │  ├─ learning/     OutcomeEvaluator  PersonaWeighting  RetrospectiveWriter
   │  │  │  ├─ llm/          RouteTable  LlmCategoryMap  Presets  FallbackPolicy  PersonalDataGate  UsageLedger  BudgetGuard  PromptTemplates
   │  │  │  ├─ notify/       NotificationInbox  NotificationRouter  QuietHours
   │  │  │  └─ sync/         SyncAgent  EventReplayer  LeaseClient  KeyEnvelope      # 8단계
   │  │  ├─ adapter/
   │  │  │  ├─ in/
   │  │  │  │  ├─ web/       controller + dto (protocol 타입 변환). setup  session  me  admin  stocks  orders  history  portfolio  watchlist
   │  │  │  │  │             debate  research  pension  stockinfo  ranking  community  notifications  llm  settings
   │  │  │  │  ├─ ws/        LocalWebSocket(시세·체결·토론·알림 EVENT)
   │  │  │  │  └─ scheduler/ Jobs(Clock 기반: 06:30 NPS · 07:00 KRX · 5분 지수 · 뉴스 · 보존 정리)
   │  │  │  └─ out/
   │  │  │     ├─ toss/      TossAuth  TossErrorMapper  dto/  TossOrderClient(Retrofit, 주문 — 유일)  TossMarketClient  TossAccountClient
   │  │  │     │             TossCalendarClient  TossIndexClient  TossRealtimeAdapter(WebSocket)  TossTradingAdapter  TossMarketDataAdapter  TossCredentialVerifier  fallback/
   │  │  │     ├─ krx/  dart/  edgar/  naver/  rss/  massive/  odcloud/  fred/  ecos/  kftc/  slack/  macos/
   │  │  │     ├─ llm/       OpenAiProvider  AnthropicProvider  DeepSeekProvider  OllamaProvider  ProviderRegistry  OllamaEmbeddingAdapter
   │  │  │     ├─ lucene/    LuceneIndex  NoriAnalyzerConfig  HybridSearcher  IndexStatusTracker
   │  │  │     ├─ mirofish/  MirofishInstaller  MirofishProcess  MirofishAdapter
   │  │  │     ├─ keychain/  KeychainSecretStore(`security` CLI, SecretStorePort+SecretReader)  SecretReader  KeychainProperties   (MemorySecretStore는 test/support/fakes)
   │  │  │     └─ persistence/  entity/(표 이름 1:1)  repository/(JPA, UserId 필수, 16자 초과 → @Query)  jooq/(Join·Bulk)  projection/  converter/  SqliteConfig(풀 2개·Flyway·EMF·jOOQ)  DbProperties  ReadWriteRoutingDataSource  JpaEventStore
   │  │  └─ config/                          #   Kotlin @Configuration: Retrofit 인터페이스 빈(엔드포인트별), Resilience4j 인스턴스, Semaphore 상한, 어댑터 조립, @Profile("engine")
   │  │                      TossHttpConfig  DartHttpConfig  EdgarHttpConfig  …  ResilienceInstancesConfig  ConcurrencyLimitsConfig  EngineConfig
   │  │
   │  ├─ relay/                               # ── @Profile("relay") 서버 (7단계 전에는 뼈대). 같은 골격
   │  │  ├─ application/  (member  auth  device  routing  lease  slack)
   │  │  ├─ adapter/in/(ws  web)  adapter/out/(messaging(브로커, 제품 보류)  slack  persistence)
   │  │  └─ config/       RelayConfig  RelayFlywayConfig(별도 DataSource, MySQL 옵션)
   │  │
   │  └─ shared/                              # ── 프로필 없는 공용. 도메인 규칙 없음
   │     ├─ config/   VirtualThreadConfig  SemaphoreConfig(공통 상한)  OkHttpConfig(공통 OkHttpClient: 타임아웃·연결 풀·마스킹 인터셉터)  RetrofitConfig(Jackson 컨버터 공통)  ResilienceConfig(레지스트리)
   │     │            CacheConfig(Valkey/Redis ↔ Caffeine 전환)  JacksonConfig(BigDecimal 문자열)  StockholmProperties(port 2609)  ProfileGuard  ClockConfig
   │     ├─ web/      LocalTokenFilter  SessionAuth  StepUpAuth  SetupGate  ErrorAdvice
   │     ├─ protocol/ (generated)
   │     ├─ crypto/   Ulid  Envelope  KeyWrap
   │     ├─ time/     ZoneIds
   │     └─ util/     BigDecimal 확장(HALF_EVEN)  마스킹  Result 확장
   │
   ├─ main/resources/
   │  ├─ application.yml  application-engine.yml(server.port=2609, address=127.0.0.1)  application-relay.yml   # 비밀값 없음
   │  ├─ db/engine/common/V1__…sql …  db/engine/sqlite/  db/relay/common/  db/relay/sqlite/  db/relay/mysql/
   │  ├─ prompts/  llm/presets.yml  llm/categories.yml  market/index-sets.yml  market/default-symbols.yml  market/industry-map.yml
   │  └─ static/(relay 원격 웹 UI)
   │
   ├─ test/kotlin/banghak/stock/
   │  ├─ architecture/   CoreHasNoFrameworkTest  HexagonalDependencyTest(adapter→application→usecase/port→domain)  NoCyclesTest
   │  │                  EngineRelayIsolationTest  ProfileBeansTest  RepositoryUserScopedTest  NoModelNamesOutsideLlmTest
   │  │                  GuardrailNotBypassableTest  OrderApiOnlyInTossOrderClientTest  FallbackRequiredTest
   │  ├─ core/…  engine/…  relay/…   (대상과 같은 패키지. 단위: Spring 없음 / 어댑터: WireMock / persistence: 골든·정밀도·FK)
   │  ├─ support/        fakes/(FakeTradingPort  FakeLlmPort  MemoryEventStore  MemorySecretStore  FixedClock)  fixtures/
   │  └─ learning/       @Tag("learning") Toss·Dart·Edgar·Krx·Fred·Massive·Naver·Odcloud LearningTest
   └─ test/resources/    wiremock/{toss,dart,edgar,krx,massive,fred,naver,odcloud}/  schema/golden-v*.sql  totp-rfc6238-vectors.json
```

- **usecase(port.in)는 `core/usecase`, 외부 포트(port.out)는 `core/port`** 한곳. 진입 어댑터(controller·ws·scheduler)는 usecase만 부르고, application이 구현한다.
- **주문 API 호출은 `engine/adapter/out/toss/TossOrderClient` 하나뿐**(절대 규칙 6, ArchUnit `OrderApiOnlyInTossOrderClientTest`).
- 모든 자동·수동 주문은 `EvaluateGuardrailUseCase`를 거친다(`GuardrailNotBypassableTest`).
- HTTP 인터페이스: 외부 API의 **엔드포인트 그룹마다** Retrofit2 인터페이스 하나(`Toss*Client`, `Dart*Client` …, `@GET`·`@POST`) + `engine/config`에서 `Retrofit.create()`로 빈 생성(공통 `OkHttpClient` 공유). 호출마다 Resilience4j `@CircuitBreaker(fallbackMethod)` 필수 — 주문 fallback = 주문하지 않음, 조회 fallback = 마지막 캐시 + "지연".
- 리포지터리: 메서드 이름 16자 초과 → `@Query`(JPQL). Join·Bulk INSERT/UPDATE → `adapter/out/persistence/jooq/*Query`(코드 생성 없이 `DSL.table/field`).
- 병렬: 가상 스레드 Executor + `SemaphoreConfig`·`ConcurrencyLimitsConfig`의 상한(토스 MARKET_DATA 15, LLM 4, 수집 배치 8). 코루틴 없음.

## 4. `desktop/`

```
desktop/
├─ package.json  tsconfig*.json  vite.config.ts  electron-builder.yml  eslint.config.js  .prettierrc
├─ src/
│  ├─ main/       index.ts(생명주기·트레이)  daemon.ts(jlink JRE+bootJar 기동, 127.0.0.1:2609 헬스 대기, 로컬 토큰)  theme.ts  notifications.ts  menu.ts  shortcuts.ts
│  ├─ preload/    index.ts(contextBridge 최소 API)
│  ├─ renderer/
│  │  ├─ app/     App.tsx  routes.tsx(setup | login | main)  Providers.tsx
│  │  ├─ theme/   tokens.css  dark.css  scrollbar.css
│  │  ├─ layout/  TopBar/(Row1 Row2 IndexTicker)  Splitters/  Drawers/(좌우 배타)  Panels/(40:60)  Modals/(showOnlyModal)  StatusBar/  Toasts/
│  │  ├─ features/ setup  login  chart  price  order  debate  watchlist  ranking  community  stockinfo  pension  portfolio  history
│  │  │            account  llmroute  asset  ticker  search  notifications  settings   (각 components/ hooks/ index.ts)
│  │  ├─ data/    client/(Client  LocalClient  RemoteClient)  api/  stream/  store/  settings/
│  │  ├─ shared/  ui/  format/(decimal 문자열, KST·ET)  hooks/
│  │  └─ generated/ (gitignored)
│  └─ web/        원격 모드 진입점(7단계)
├─ resources/     아이콘, extraResources 자리
└─ tests/         vitest + Testing Library
```

## 5. `protocol/`

```
protocol/
├─ schemas/common/  events/  api/  relay/
├─ generate.sh      quicktype: Kotlin → backend/build/generated,  TS → desktop/src/renderer/generated [확정 2026-09-26]
└─ README.md        금액 문자열 · 시각 ISO-8601 UTC · enum = Kotlin enum 이름
```

## 6. `scripts/`

`dev.sh` · `dist.sh` · `learning-tests.sh` · `db-dump.sh` (내용은 이전과 같음).

## 7. 기능 → 위치 대응

| 기능 | core.domain | engine.application | adapter.in.web | adapter.out | desktop feature |
|---|---|---|---|---|---|
| F1·F14 주문·정정·취소 | trading, guardrail | trading, guardrail | orders | toss(TossOrderClient), persistence | order |
| F2·F20 손익·거래내역 | portfolio | trading(조회), portfolio(계산) | history | persistence(jooq) | history |
| F3 차트 | market | market | stocks | toss, krx | chart |
| F4 종목·관심종목 | market | market | stocks, watchlist | toss, krx, dart, persistence | search, watchlist, price |
| F5 추천 | recommend | recommend, llm | research | llm, lucene | debate |
| F6 토론 | debate, knowledge | debate, llm, research | debate | llm, lucene, mirofish | debate |
| F7·F8 자동화 | automation, guardrail | automation, llm | admin | toss, slack | settings, notifications |
| F9 리포트 | report | report, research | research | fred, ecos, llm | debate |
| F10·F17 학습·통계 | learning | learning | research, history | persistence | settings, history |
| F11 급등락 | market | market | ranking | toss | ranking |
| F12 커뮤니티 | knowledge | research | community | rss, naver, massive, lucene | community |
| F13 연기금 | pension | research, notify | pension | edgar, odcloud | pension |
| F15 종목 정보 | knowledge | research | stockinfo | dart, edgar, massive | stockinfo |
| F16 자산 조회 | — | account | me | kftc | asset |
| F18 평가금액 | portfolio | portfolio | portfolio | toss | portfolio |
| F19 마법사·로그인·시작 종목 | account, trading | account, trading | setup, session | keychain, 검증 어댑터들 | setup, login |
| F21 계정·알림·회원 관리 | account, notification | account, notify | me, admin | keychain, slack, macos | account |
| F22 LLM 경로 | llm | llm | llm | llm | llmroute |
| F23 지수 티커 | market | market | stocks | toss, fred | ticker |
| 캐시(Valkey/Redis 또는 Caffeine) | — | — | admin(주소 등록) | shared.config.CacheConfig | — |

## 8. 1단계에서 채우는 것

`shared` 골격(config·web·crypto·time), `core/domain`의 `money`·`identity`·`account`·`eventlog`(TDD), `core/usecase`·`core/port`의 1단계 인터페이스(SetupWizard·Login·SecretStore·CredentialVerifier·EventStore), `engine/application/account`, `engine/adapter/out/keychain`·`persistence`(V1), `engine/adapter/in/web`(setup·session·me), `architecture` 테스트 전부, `desktop`의 main·preload·layout·setup·login, `protocol/schemas/common·events`. 나머지 패키지는 빈 채로 두어 ArchUnit이 전체 구조를 검사한다.

## 9. 결정 (2026-09-26)

1. **공용 값 객체**(`Money`·`Quantity`·`Symbol`·`UserId` …)는 `core/domain/{money,market,identity}` 한곳에 두고 모든 계층이 참조한다(사용자 결정 (a)).
2. **`research`는 한 묶음으로 유지**(공시·뉴스·재무·연기금·거시·RAG). 커지면 `application/research/` 아래 하위 패키지로만 나눈다.
3. **테스트 스택 = JUnit 5 + AssertJ**(+ ArchUnit·WireMock). mock은 손으로 만든 fake, 필요할 때만 MockK.
4. **ktfmt 스타일 = kotlinlang**(공식 코딩 컨벤션, 들여쓰기 4칸) [확정 2026-09-26].
5. **protocol 타입 생성 = quicktype**(JSON Schema → Kotlin·TypeScript를 한 도구로, npm) [확정 2026-09-26]. Gradle 단일 모듈 유지.
6. relay 포트·WebSocket 경로는 7단계로 연기.
7. **캐시 대상** = 종목 검색·현재가 스냅샷·환율·지수·RAG 검색 결과·요약만. 비밀·계좌·주문·lot은 캐시하지 않는다.

## Changes

| 날짜 | 변경 |
|---|---|
| 2026-09-26 | Modulith 제거(NoCyclesTest), `error/` 패키지·`Ulid`·`StrategyId` 위치 반영 |
| 2026-09-27 | persistence·keychain 실제 클래스 반영, 예외 이름 접미어 |
