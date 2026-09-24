# CORE_DOMAIN.md — `core` 도메인 모델 설계

> `backend/src/main/java/banghak/stockholm/core` 의 기준 문서. 프레임워크를 모르는 순수 Java 21 코드의 형태를 정한다.
> 여기 적힌 타입 이름과 시그니처가 이후 `engine`·`localapi`·`desktop`·`protocol/` 의 공통 어휘다.
> 표기: **[확정]** / **[제안]** / **[확인 필요]**. 근거 규칙은 `CLAUDE.md`(값 객체·BigDecimal·Clock 주입·포트 경계)와 `PROJECT.md` 6·8·10장.

- 최종 갱신: 2026-09-23
- 서버(relay)는 나중에 붙는다. 이 문서의 core는 **단독 모드에서 완결**되되, 이벤트 로그·userId 범위·lease 인터페이스 세 가지만 서버를 위해 미리 열어 둔다.

---

## 1. 설계 원칙

1. **core는 라이브러리다.** `java.base`·`java.time`만 쓴다. Spring·JPA·Jackson·HTTP·로깅 프레임워크를 import하지 않는다(ArchUnit으로 강제). 로깅이 필요하면 도메인 이벤트로 낸다.
2. **값 객체는 `record`, 생성 시 검증.** `compact constructor`에서 불변식을 확인하고 실패하면 `IllegalArgumentException` 계열의 도메인 예외를 던진다. `null`은 받지 않는다(`Objects.requireNonNull`).
3. **돈과 수량은 `BigDecimal`.** 반올림은 값 객체 안에서 통화별 규칙으로만 한다. `double`은 어디에도 없다.
4. **시간은 `java.time.Clock`을 주입.** core 안의 어떤 코드도 `Instant.now()`를 직접 부르지 않는다. 저장은 UTC `Instant`, 장 시간 판단은 `ZoneId` 명시.
5. **상태 변화는 이벤트로.** 동기화 대상 애그리거트는 `apply(event)`로 상태를 만들고, 명령은 이벤트 목록을 반환한다. 화면용 테이블은 `engine`의 projection이다.
6. **가드레일은 순수 함수.** 입력 스냅샷 → 판정. I/O 없음. 모든 규칙을 끝까지 돌려 위반을 전부 모은다(첫 위반에서 멈추지 않는다 — 사용자에게 이유를 다 보여주기 위해).
7. **fail-safe.** 판단에 필요한 정보가 없으면(`Optional.empty`, 오래된 스냅샷) 주문하지 않는 쪽으로 결론 낸다.

## 2. 패키지 구성

```
core/
├─ money/        Money, Currency, ExchangeRate, Percent, RoundingRules
├─ market/       Market, Symbol, StockCode, MarketSession, TradingCalendar(값), StockFlags
├─ identity/     UserId, DeviceId, Role
├─ trading/      OrderSide, OrderIntent, ClientOrderId, BrokerOrder, OrderStatus, Fill, OrderBook, Quote, Candle
├─ portfolio/    Lot, LotId, BuyOrigin, Position, PortfolioSnapshot, DepositBalance, ProfitLoss
├─ automation/   ExecutionStage, AutomationScope, AutomationSetting, KillSwitch, AutoSellPermission, AutoBuyExposure
├─ guardrail/    Guardrail, GuardrailContext, GuardrailVerdict, GuardrailLimits, 규칙 구현체들
├─ debate/       Persona, PersonaDefinition, DebateTheme, DebateSession, Round, Utterance, Intervention, Verdict
├─ pension/      PensionSource, PensionDataset, PensionHolding, PensionHoldingsSnapshot, PensionHoldingsDiff, Cusip, Isin [제안, F13 — docs/NPS_HOLDINGS_DESIGN.md]
├─ eventlog/     DomainEvent(sealed), EventEnvelope, EventSequence, EventStore(포트지만 여기 둠)
├─ lease/        AutomationLease, LeaseHolder(포트), AlwaysHeldLease(단독 모드 구현)
├─ port/         외부 세계 인터페이스 전부 (Section 10)
└─ error/        DomainException 계층 (Section 11)
```

`core` 하위 패키지끼리는 `money`·`market`·`identity` → `trading`·`portfolio` → `automation`·`guardrail`·`debate` 방향으로만 의존한다. `eventlog`는 모두가 쓰고, `port`는 위 타입들만 참조한다.

## 3. 기본 값 객체 [확정]

```java
package banghak.stockholm.core.money;

public enum Currency { KRW(0), USD(2);          // 표시·저장 소수 자릿수
  public final int scale; Currency(int s){ scale = s; } }

/** 금액. 통화가 다른 값끼리는 연산하지 않는다(예외). */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {
  public Money { requireNonNull(amount); requireNonNull(currency);
                 amount = amount.setScale(currency.scale, RoundingMode.HALF_EVEN); }
  public static Money of(String amount, Currency c) { ... }
  public static Money zero(Currency c) { ... }
  public Money plus(Money o)            // 통화 불일치 → CurrencyMismatch
  public Money minus(Money o)
  public Money times(BigDecimal factor)  // 결과는 통화 scale로 HALF_EVEN
  public Money times(Quantity q)
  public Percent ratioTo(Money base)     // this / base, base가 0이면 예외
  public boolean isPositive() / isZero() / isNegative()
  public Money convert(ExchangeRate rate) // rate.from == this.currency 검증
}

/** 환율. 시점을 함께 가진다 — 해외 노출액은 "매수 시점 환율"이 확정 규칙. */
public record ExchangeRate(Currency from, Currency to, BigDecimal rate, Instant asOf) {
  public ExchangeRate { rate > 0 검증; from != to 검증; }
}

/** 0~100이 아니라 비율(0.9 = 90%)로 저장. 표시 변환은 프론트. */
public record Percent(BigDecimal ratio) {
  public static final Percent ZERO, HUNDRED;
  public static Percent ofRatio(String r); public static Percent ofPercent(String p);
  public boolean isAtLeast(Percent o) / exceeds(Percent o)
  public Percent times(BigDecimal f)
}
```

```java
package banghak.stockholm.core.market;

public enum Market { KR(Currency.KRW, ZoneId.of("Asia/Seoul")), US(Currency.USD, ZoneId.of("America/New_York"));
  public final Currency currency; public final ZoneId zone; }

/** 종목 식별자. 코드 형식은 시장별로 검증한다(KR 6자리 숫자, US 티커 1~5자 영문). */
public record Symbol(Market market, String code) { ... }

/** 정규/프리/애프터/휴장. F7 거래 시간 규칙의 입력. */
public enum MarketSession { PRE, REGULAR, AFTER, CLOSED }

/** 종목 경고 플래그. F4 표시와 F7 제외 필터가 같은 값을 본다. */
public record StockFlags(boolean investmentWarning, boolean investmentRisk, boolean administrative,
                         boolean tradingHalted, Optional<LocalDate> listedAt) {
  public boolean isExcludedFromAutoBuy(LocalDate today, Period newListingWindow) { ... }
}
```

```java
package banghak.stockholm.core.identity;
public record UserId(String value) { /* "u_" 접두 + ULID */ }
public record DeviceId(String value) { /* "d_" 접두 + ULID */ }
public enum Role { ADMIN, MEMBER }
```

```java
package banghak.stockholm.core.trading;

/** 수량. 국내는 정수(scale 0). 미국은 소수점 6자리까지(토스: 소수점 수량은 시장가 매도에만 허용, 보유·매도가능수량에는 소수점이 올 수 있음) → BigDecimal, 시장별 scale로 검증. [확정 사실 2026-09-23] */
public record Quantity(BigDecimal value) {
  public Quantity { value > 0 또는 == 0 허용, 음수 금지 }
  public static Quantity of(long shares);
  public Quantity plus/minus(Quantity o); public Quantity times(Percent p, RoundingMode m);
  public boolean isGreaterThan(Quantity o);
}
```

**결정 근거.** `Money`가 통화를 품으면 "국내·해외 한도 각각"이 타입 수준에서 강제된다 — KRW 한도와 USD 노출액을 더하는 코드는 컴파일은 되지만 실행 즉시 `CurrencyMismatch`로 죽는다. 이 실패는 테스트에서 반드시 잡힌다.

## 4. 거래 (trading) [확정]

```java
public enum OrderSide { BUY, SELL }
public enum OrderKind { LIMIT }   // 시장가는 만들지 않는다. "즉시 주문"도 현재가 지정가(F1).

/** 주문 의도 — 아직 증권사에 보내지 않은 것. LLM/화면/자동화 어디서 왔든 이 타입으로 수렴한 뒤 가드레일을 통과해야 한다. */
public record OrderIntent(UserId userId, Symbol symbol, OrderSide side, Money limitPrice, Quantity quantity,
                          BuyOrigin origin, OrderTrigger trigger, Instant intendedAt) {
  public Money notional() { return limitPrice.times(quantity); }
}

/** 어디서 온 주문인가. 감사 로그·F10 학습·이중 주문 방어의 키. */
public sealed interface OrderTrigger permits ManualTrigger, ManualAmendTrigger, RecommendationTrigger, AutoBuyTrigger, AutoSellTrigger {}
public record ManualTrigger(DeviceId device) implements OrderTrigger {}
public record ManualAmendTrigger(DeviceId device, String amendedBrokerOrderId) implements OrderTrigger {}   // 사람의 정정(F14). 한도 규칙 대상 아님, 초과 시 확인 창
public record RecommendationTrigger(RecommendationId id) implements OrderTrigger {}
public record AutoBuyTrigger(StrategyId strategy, LocalDate tradingDay, int sequence) implements OrderTrigger {}
public record AutoSellTrigger(DebateSessionId basis, LocalDate tradingDay, int sequence) implements OrderTrigger {}

/** 멱등 키. 자동 주문은 (userId, 전략, 종목, 거래일, 회차)에서 결정적으로 만든다(8.1). 수동 주문은 ULID.
 *  토스 제약: 최대 36자, [A-Za-z0-9_-], 유효 10분. 10분이 지나면 같은 키도 새 주문 → 재시도 전 lookup 필수. */
public record ClientOrderId(String value) {
  public ClientOrderId { 1~36자, ^[A-Za-z0-9_-]+$ 검증 }
  public static ClientOrderId deterministic(UserId u, StrategyId s, Symbol sym, LocalDate day, int seq) { /* SHA-256 → base32 앞 26자 */ }
  public static ClientOrderId random();
}

public enum OrderStatus { PENDING, PARTIALLY_FILLED, PENDING_CANCEL, PENDING_AMEND, FILLED, CANCELLED, REJECTED,
                          CANCEL_REJECTED, AMEND_REJECTED, REPLACED, UNKNOWN }   // 토스 10개 상태 + UNKNOWN [제안 2026-09-24, F14]
/** UNKNOWN은 "결과를 모름(타임아웃)" 또는 토스의 미지 코드. 이 상태에서는 재시도 전에 반드시 조회한다(CLAUDE.md fail-safe). */

/** 정정 요청. 지정가만(시장가 전환 없음). 미국은 수량을 바꿀 수 없다(토스 규격). 둘 다 비면 InvalidValue. */
public record OrderAmendment(Optional<Money> newLimitPrice, Optional<Quantity> newQuantity) {}

public record BrokerOrder(ClientOrderId clientOrderId, String brokerOrderId, Optional<String> replacesBrokerOrderId,
                          OrderIntent intent, OrderStatus status, Quantity filledQuantity,
                          Optional<Money> averageFilledPrice, Instant updatedAt) {
  public Quantity remaining();  public boolean isOpen();
  public boolean canAmend();    // open이고 처리 중이 아님. 출처와 무관 — 자동 주문도 사람이 정정 가능(F14). 정정 가능 수량은 remaining()까지
  public boolean canCancel();   // open이고 처리 중이 아님
}
// 정정·취소는 토스가 새 brokerOrderId를 발급한다. replacesBrokerOrderId로 원주문→새 주문 체인을 잇는다.
public record Fill(String brokerOrderId, Symbol symbol, OrderSide side, Money price, Quantity quantity,
                   Money fee, Money tax, Instant executedAt) {}

public record Quote(Symbol symbol, Money last, Money prevClose, Quantity volume, Instant asOf) {}
public record OrderBook(Symbol symbol, List<Level> asks, List<Level> bids, Instant asOf) { public record Level(Money price, Quantity quantity) {} }
public record Candle(Symbol symbol, Duration interval, Instant openTime, Money open, Money high, Money low, Money close, Quantity volume) {}
```

## 5. 보유와 손익 (portfolio) [확정]

```java
public enum BuyOrigin { MANUAL, AI_RECOMMENDED, AUTO_BUY }

/** 매수 한 건. 부분 매도로 remaining이 줄어들 뿐, 매입 원가·환율·시각은 바뀌지 않는다. */
public record Lot(LotId id, UserId userId, Symbol symbol, Quantity boughtQuantity, Quantity remainingQuantity,
                  Money unitCost, Optional<ExchangeRate> fxAtBuy, Instant boughtAt, BuyOrigin origin) {
  public Money costBasis()                       // unitCost × remaining
  public Money costBasisInKrw()                  // fxAtBuy로 환산(KR은 그대로). fx 없으면 예외 — 해외 lot은 반드시 환율을 가진다
  public boolean isOpen()                        // remaining > 0
  public Duration heldFor(Instant now)
  public Lot afterSelling(Quantity q)            // 새 Lot 반환(불변)
}

public record Position(Symbol symbol, List<Lot> openLots) {
  public Quantity quantity(); public Money averageCost();
  public Quantity quantityFrom(BuyOrigin origin);
}

/** 가드레일이 보는 "그 순간의 계좌". 전부 증권사 API에서 온 값 + 로컬 lot 태깅. */
public record PortfolioSnapshot(UserId userId, Market market, List<Position> positions, DepositBalance deposit,
                                List<BrokerOrder> openOrders, List<Fill> todayFills, Instant asOf) {
  public boolean isStale(Instant now, Duration maxAge)   // 오래됐으면 가드레일은 REJECT(fail-safe)
}
public record DepositBalance(Money available, Money total, Instant asOf) {}

/** 손익. 실현/평가 구분, 수수료·세금 반영, 해외는 원화 환산 병기(F2). */
public record ProfitLoss(Money realized, Money unrealized, Money fees, Money taxes, Optional<Money> realizedKrw, Optional<Money> unrealizedKrw) {}
```

**매도 시 lot 소진 순서 [제안]**: 세무상 기본은 선입선출(FIFO). 자동 매도 한도(90%)는 lot이 아니라 종목 기준 수량으로 세므로 소진 순서와 무관하다. FIFO를 기본으로 두고 상수화한다.

## 6. 자동화 (automation) [확정]

```java
public enum ExecutionStage { SIMULATED, APPROVAL_REQUIRED, FULLY_AUTOMATIC }

/** 단계 설정 단위: 사용자 × 시장 × 매수/매도. 매도는 종목별로 더 좁힐 수 있다. */
public record AutomationScope(UserId userId, Market market, OrderSide side, Optional<Symbol> symbol) {}
public record AutomationSetting(AutomationScope scope, ExecutionStage stage, boolean enabled) {}

/** 킬 스위치: 사용자 단위. 켜지면 모든 자동화가 즉시 멈춘다. 자동 발동 사유를 기록한다. */
public record KillSwitch(UserId userId, boolean engaged, Optional<KillReason> reason, Instant changedAt) {}
public enum KillReason { USER, CONSECUTIVE_LOSSES, DAILY_LOSS_LIMIT, LEASE_LOST, BROKER_UNAVAILABLE }

/** 종목별 자동 매도 허용. baseQuantity는 허용 시점 보유 수량, 누적 자동 매도는 base의 90%를 넘지 못한다. */
public record AutoSellPermission(UserId userId, Symbol symbol, Quantity baseQuantity, Quantity autoSoldSoFar,
                                 boolean includeStopLoss, Instant grantedAt) {
  public Quantity remainingAutoSellable(Percent cap)   // base×cap − soldSoFar, 음수면 0
}

/** 자동 매수 노출액 — 순수 계산. 규칙(10.2)을 그대로 코드로 옮긴 것. */
public final class AutoBuyExposure {
  public static final Duration EXPOSURE_WINDOW = Duration.ofHours(168);
  /** origin == AUTO_BUY 이고 보유 168시간 미경과인 open lot의 원화 매입 원가 합 + 미체결 자동 매수 주문 금액(원화 환산). */
  public static Money of(Market market, List<Lot> lots, List<BrokerOrder> openOrders, Function<Money, Money> toKrw, Instant now) { ... }
}
```

## 7. 가드레일 (guardrail) [확정]

```java
/** 한도. 코드 상수가 상한이고 설정은 낮출 수만 있다 — 생성자에서 상한 초과를 거부한다. */
public record GuardrailLimits(Money totalExposureCap, Money dailyBuyCap, int maxConcurrentAutoBuySymbols,
                              Money depositFloor, Percent depositFloorMinRatio, Percent autoSellCap,
                              Percent maxIntradayRiseForEntry, int maxAutoOrdersPerMinute, Duration snapshotMaxAge) {
  public static GuardrailLimits defaults(Market m);   // 1000만원/1000만원/8/…/50%/90%/…
  public GuardrailLimits lowerTo(GuardrailLimits requested)  // 각 항목 min(). 올리는 값은 무시하고 LimitRaiseIgnored 이벤트
}

/** 가드레일이 보는 모든 입력. I/O는 이 객체를 만드는 engine의 일이다. */
public record GuardrailContext(OrderIntent intent, PortfolioSnapshot snapshot, GuardrailLimits limits,
                               StockFlags flags, Quote quote, MarketSession session, KillSwitch killSwitch,
                               Optional<AutoSellPermission> sellPermission, int autoOrdersInLastMinute, Instant now) {}

public sealed interface GuardrailVerdict permits Passed, Rejected {
  record Passed(List<String> notes) implements GuardrailVerdict {}
  record Rejected(List<Violation> violations) implements GuardrailVerdict {}
  record Violation(String rule, String reason, Optional<Money> limit, Optional<Money> actual) {}
}

/** 규칙 하나. 구현체는 상태가 없고 컨텍스트만 본다. */
public interface Guardrail {
  String name();
  Optional<Violation> check(GuardrailContext ctx);
  /** 이 규칙이 적용되는 주문인가(수동 주문에는 노출액 한도가 걸리지 않는다 등). */
  default boolean appliesTo(OrderIntent intent) { return true; }
}

/** 전부 돌리고 위반을 모은다. 순서는 결정적(이름순)이라 로그가 안정적이다. */
public final class GuardrailChain {
  public GuardrailChain(List<Guardrail> rules) {...}
  public GuardrailVerdict evaluate(GuardrailContext ctx);
}
```

**규칙 목록과 적용 대상**

| 규칙 클래스 | 대상 | 판정 |
|---|---|---|
| `KillSwitchEngaged` | 자동 주문 | 킬 스위치 켜짐 → 거부 |
| `SnapshotFreshness` | 모든 주문 | 스냅샷이 `snapshotMaxAge`보다 오래됨 → 거부(fail-safe) |
| `TotalExposureCap` | 자동 매수 | 현재 노출액 + 이번 주문 금액 > 1000만원(시장별) → 거부. **정확히 1000만원은 통과** |
| `DailyBuyCap` | 자동 매수 | 오늘 자동 매수 체결 + 미체결 + 이번 주문 > 1000만원 → 거부 |
| `ConcurrentSymbolCap` | 자동 매수 | 자동 매수 보유 종목 8개이고 새 종목 → 거부. 기존 종목 추가 매수는 통과 |
| `DepositFloor` | 자동 매수 | 주문 후 예수금 < 하한 → 거부. 하한 설정 자체는 설정 시점 예수금의 50% 이상이어야 저장됨(설정 검증) |
| `ExcludedStock` | 자동 매수 | 투자경고·위험·관리·거래정지·신규 상장 직후·사용자 제외 → 거부 |
| `IntradayRiseCap` | 자동 매수(급등 트리거) | 당일 상승률 > 상한 → 거부 |
| `TradingWindow` | 자동 주문 | 세션·KST 창 밖, 동시호가 구간 → 거부 |
| `OrdersPerMinute` | 자동 주문 | 분당 상한 초과 → 거부 |
| `DuplicateIntent` | 자동 주문 | 같은 `ClientOrderId` 또는 같은 종목·방향의 미체결 존재 → 거부 |
| `AutoSellQuota` | 자동 매도 | 허용 없음, 또는 누적 + 이번 수량 > 기준의 90% → 거부. **정확히 90%는 통과** |
| `StopLossPermitted` | 자동 매도 | 손실 매도인데 "수익 실현만" → 거부 |
| `StepUpRequired` | 수동(원격·고액) | 가드레일이 아니라 `localapi`의 인증 관심사. 여기서는 노트만 남긴다 |
| `AutoExposureOverCapNotice` | 사람의 정정(`ManualAmendTrigger`)으로 lot 출처가 `AUTO_BUY`인 주문 | 정정 후 노출액 > 한도 → **거부가 아니라 `Passed`에 확인 필요 노트**. 화면은 확인 창을 띄우고 승인이 있어야 제출. 자동매매 한도의 유일한 예외(PROJECT 10.1) |

수동 주문(F1)에는 `SnapshotFreshness`·`DuplicateIntent`(경고)·거래시간만 걸리고 한도 규칙은 걸리지 않는다. 화면의 "가드레일 판정"은 같은 `GuardrailChain`을 다른 규칙 집합으로 돌린 결과다.

## 8. 토론 (debate) — core에 두는 것만

상세는 `docs/DEBATE_DESIGN.md`. core에는 **저장·재개·검증에 필요한 구조**만 둔다. LLM 프롬프트, RAG, 미러피시 호출은 `engine.debate`.

```java
public enum DebateTheme { BUY, SELL, INDUSTRY }
public record PersonaDefinition(PersonaId id, int version, String emoji, String name, String role, String stance, String promptSnapshot) {}  // emoji: 화면 표시용, 사용자 편집
public record Utterance(PersonaId speaker, int round, String text, List<CitationRef> citations, Instant at) {}
public record Intervention(InterventionKind kind, String text, Optional<AttachmentRef> attachment, Instant at) {}
public enum InterventionKind { QUESTION, REBUTTAL, AGREEMENT, EVIDENCE, CONCLUDE }
public record Verdict(Conclusion conclusion, Percent confidence, List<String> keyReasons, List<String> counterArguments,
                      List<String> reversalEvents, Map<PersonaId, String> stances, List<CitationRef> citations, Instant at) {}
public enum Conclusion { BUY, HOLD, SELL, WATCH, OVERWEIGHT, UNDERWEIGHT }

public final class DebateSession {   // 이벤트 소싱 애그리거트
  public static DebateSession start(UserId u, Symbol s, DebateTheme t, List<PersonaDefinition> personas, Engine engine, Instant now)
  public List<DomainEvent> record(Utterance u) / intervene(Intervention i) / conclude(Verdict v) / resume(List<PersonaDefinition> current, Instant now)
  public DebateSession apply(DomainEvent e)
}
```

`Verdict`는 **주문 파라미터가 아니다.** 자동화가 이를 `OrderIntent`로 바꿀 때는 `engine.automation`의 변환기가 스키마 검증 → `OrderIntent` 생성 → `GuardrailChain`을 거친다(10.4).

## 9. 이벤트 로그 (eventlog) [확정]

```java
/** 동기화·감사·projection의 원천. 추가 전용. */
public record EventEnvelope(UserId userId, DeviceId deviceId, long seq, Instant occurredAt, String type, DomainEvent payload) {}

public sealed interface DomainEvent permits
  // portfolio
  LotOpened, LotReduced, LotClosed, LotAgedOutOfAutoBuy,
  // trading
  OrderIntended, OrderSubmitted, OrderAmendRequested, OrderCancelRequested, OrderStatusChanged, OrderFilled, OrderResultUnknown,
  // guardrail / automation
  GuardrailEvaluated, AutomationSettingChanged, LimitsLowered, LimitRaiseIgnored, KillSwitchChanged, AutoSellPermissionChanged, SimulatedOrderRecorded,
  // debate
  DebateStarted, PersonaSpoke, UserIntervened, RoundEnded, VerdictReached, DebateResumed,
  // watchlist / settings
  WatchlistChanged, PersonaDefinitionChanged, UserSettingChanged, LayoutRatioChanged, ChartModeChanged,
  // lease
  LeaseAcquired, LeaseRenewed, LeaseLost, LeaseForciblyTaken { }
```

- `seq`는 **디바이스별 단조 증가.** 동기화는 `(deviceId, seq)` 커서로 빠진 구간만 가져온다.
- `payload`는 core 타입. 직렬화(JSON)는 `shared`가 담당하고 스키마는 `protocol/`에서 생성한다.
- 동기화하지 않는 것(잔고·체결 원본·RAG 인덱스)은 이벤트가 아니라 `engine`의 캐시 테이블이다.
- 주문 감사 로그는 `OrderIntended → GuardrailEvaluated → OrderSubmitted → OrderStatusChanged/Filled`의 연쇄 자체다. 별도 로그 문장을 만들지 않는다. 계좌번호·키는 이벤트에 들어가지 않는다(`DepositBalance`에는 금액만 있다).

```java
public interface EventStore {                 // core.eventlog
  long append(UserId u, DeviceId d, List<DomainEvent> events, Instant now);   // 반환: 마지막 seq
  Stream<EventEnvelope> replay(UserId u, Optional<DeviceId> d, long afterSeq);
}
```

## 10. 포트 (port) [확정]

전부 `core.port`. 구현은 `engine`(주문·시세는 토스 어댑터만). 예외는 Section 11의 도메인 예외로 감싸서 던진다.

```java
public interface TradingPort {                                // 토스 전용. 다른 구현 금지(D8)
  BrokerOrder submit(OrderIntent intent, ClientOrderId id);   // 타임아웃 → OrderResultUnknown 예외 (재시도 전 lookup 필수)
  BrokerOrder amend(UserId u, String brokerOrderId, OrderAmendment amendment);   // 새 brokerOrderId. 타임아웃 → OrderResultUnknown [제안, F14]
  BrokerOrder cancel(UserId u, String brokerOrderId);                            // 취소 요청 레코드(새 id) 반환
  BrokerOrder lookup(UserId u, String brokerOrderId);
  List<BrokerOrder> openOrders(UserId u, Market m);
  List<BrokerOrder> closedOrders(UserId u, Market m, LocalDate from, LocalDate to, Optional<String> cursor);
  List<Fill> fills(UserId u, Market m, LocalDate day);
  PortfolioSnapshot snapshot(UserId u, Market m);
}
public interface MarketDataPort {
  Quote quote(Symbol s); OrderBook orderBook(Symbol s);
  List<Candle> candles(Symbol s, Duration interval, Instant from, Instant to);
  StockFlags flags(Symbol s); Optional<ExchangeRate> exchangeRate(Currency from, Currency to);
}
public interface MarketCalendarPort { MarketSession sessionAt(Market m, Instant t); boolean isTradingDay(Market m, LocalDate d); }
public interface RealtimeFeedPort {                          // 구독은 engine이 lease 보유 시 우선
  Subscription subscribeTrades(Symbol s, Consumer<Fill> onTrade);
  Subscription subscribeQuotes(Symbol s, Consumer<Quote> onQuote);
  Subscription subscribeMyOrders(UserId u, Consumer<BrokerOrder> onChange);
}
public interface DisclosurePort { List<Disclosure> recent(Symbol s, Instant since); }
public interface NewsPort { List<Article> recent(Symbol s, Instant since); }           // publishedAt 필수
public interface LlmPort { LlmResponse complete(LlmPurpose purpose, LlmRequest req); }  // 모델명은 여기 없음(docs/LLM_ROUTING.md)
public interface SimulationPort { SimulationRun start(DebateSession s); Optional<Utterance> poll(SimulationRun r); }
public interface NotifierPort { void notify(UserId u, Notification n); }
public interface SecretStorePort { void put(SecretKey key, char[] value); boolean exists(SecretKey key); void delete(SecretKey key); }
  // get()이 없다 — 키 값은 쓰기 전용. 어댑터가 내부에서만 읽는다(CLAUDE.md 절대 규칙 2).
public interface IdentityPort { IdentityProof verify(UserId u, IdentityRequest r); }
public interface AssetPort { List<ExternalAsset> assets(UserId u); }
public interface PensionHoldingsPort {                        // [제안] F13. 구현체 둘: SEC EDGAR 13F(분기) / 공공데이터포털(연간)
  PensionSource source();
  List<PensionDataset> catalog();                              // 13F: 제출 목록 JSON / 연간: Swagger. 실패 시 MarketDataUnavailable
  PensionHoldingsSnapshot fetch(PensionDataset dataset);       // 정보표 XML 또는 전 페이지 수집·정규화. 연간은 키 없으면 SecretMissing
}
// 시계는 java.time.Clock 그대로 주입한다. 별도 포트를 만들지 않는다.
```

```java
package banghak.stockholm.core.lease;
public interface LeaseHolder {                         // 서버가 없을 때는 AlwaysHeldLease
  boolean holds(UserId u); Optional<Instant> expiresAt(UserId u);
}
public final class AlwaysHeldLease implements LeaseHolder { /* 단독 모드: 항상 true */ }
```

## 11. 예외 (error)

```java
public abstract class DomainException extends RuntimeException {}
// 입력·불변식
public final class InvalidValue extends DomainException {}          // 값 객체 생성 실패
public final class CurrencyMismatch extends DomainException {}
// 규칙
public final class GuardrailViolation extends DomainException { GuardrailVerdict.Rejected verdict; }
public final class LimitRaiseRejected extends DomainException {}
// 외부 세계 (어댑터가 서드파티 예외를 이걸로 감싼다)
public final class BrokerUnavailable extends DomainException {}
public final class OrderRejected extends DomainException {}
public final class OrderResultUnknown extends DomainException {}   // 타임아웃. 호출자는 lookup 후에만 재시도
public final class MarketDataUnavailable extends DomainException {}
public final class LlmUnavailable extends DomainException {}
public final class SecretMissing extends DomainException {}
```

분류 기준은 "호출자가 무엇을 할 수 있는가": `BrokerUnavailable`은 재시도·알림, `OrderRejected`는 사용자에게 사유 표시, `OrderResultUnknown`은 조회 후 결정, `GuardrailViolation`은 화면에 위반 목록.

## 12. 불변식과 필수 테스트 (TDD 대상)

`core.guardrail`·`core.automation`·`core.eventlog`·`core.lease`는 테스트를 먼저 쓴다. 최소 목록:

**Money / Quantity**
- KRW는 소수 0자리, USD는 2자리로 HALF_EVEN. 통화 불일치 연산은 `CurrencyMismatch`.
- `Money.convert`는 `from` 통화 검증. 환율은 양수.

**AutoBuyExposure**
- 보유 **정확히 168시간**인 lot은 노출액에 **포함되지 않는다**(미경과 = 168h 미만). 167h59m59s는 포함.
- `MANUAL`·`AI_RECOMMENDED` lot은 제외. 청산된 lot 제외. 미체결 자동 매수 주문은 포함.
- 해외 lot은 `fxAtBuy`로 환산. 환율 없는 해외 lot은 예외.
- 국내·해외를 섞은 목록에서 `Market` 필터가 정확히 갈라진다.

**TotalExposureCap / DailyBuyCap**
- 노출액 + 주문 = **정확히 10,000,000원 → 통과**, 10,000,001원 → 거부.
- 시장별 독립: 국내 1000만원 꽉 찬 상태에서 해외 자동 매수는 통과.

**DepositFloor**
- 하한 설정: 설정 시점 예수금의 **정확히 50% → 허용**, 그 미만 → `InvalidValue`.
- 주문 후 예수금 = 하한 → 통과, 하한 − 1원 → 거부.

**AutoSellQuota**
- 누적 + 이번 = 기준의 **정확히 90% → 통과**, 초과 → 거부. 기준 수량 갱신 후 누적은 유지.
- 허용 없는 종목 → 거부. `includeStopLoss=false`인데 손실 매도 → 거부.

**ConcurrentSymbolCap** — 8종목 보유 중 9번째 신규 → 거부, 기존 종목 추가 → 통과.

**GuardrailLimits.lowerTo** — 올리는 요청은 무시되고 `LimitRaiseIgnored` 이벤트, 낮추는 요청만 반영.

**GuardrailChain** — 위반 두 개 이상이면 전부 모인다. 규칙 순서는 이름순으로 결정적.

**ClientOrderId.deterministic** — 같은 입력 → 같은 값, 회차만 달라도 다른 값.

**OrderAmendment / BrokerOrder.canAmend·canCancel** (F14) — 둘 다 비면 `InvalidValue`, US + 수량 → `InvalidValue`. 상태 10종 × 출처 3종 표 전체: `PENDING_CANCEL`은 둘 다 false, `AUTO_BUY`+`PENDING`은 cancel만 true.

**TradingWindow** — 미국: `America/New_York` 서머타임 전환 주간에 KST 22:30~07:00 창과의 교집합이 맞는지(3월·11월 고정 시계로).

**DebateSession** — 이벤트 재생으로 같은 상태 복원. 재개 시 현재 페르소나 정의가 스냅샷과 다르면 새 정의로 `DebateResumed`에 기록.

**EventStore(fake)** — seq는 디바이스별 단조 증가, `replay(afterSeq)`는 그 이후만.

**AlwaysHeldLease** — 항상 보유. (실제 lease는 8단계.)

## 13. 열린 항목

- **수량의 소수점**: 토스 미국 주식 소수점 주문 지원 여부 → `Quantity` scale 규칙 [확인 필요]
- **lot 소진 순서 FIFO** [제안]
- `Percent`를 비율로 저장하는 결정(0.9) — 프론트 표시 변환 규칙과 함께 `protocol/`에 명시 [제안]
- `Conclusion` 열거값이 세 테마를 다 덮는지(산업 동향은 OVERWEIGHT/UNDERWEIGHT) — 토론 설계와 맞춰 확정 [제안]
