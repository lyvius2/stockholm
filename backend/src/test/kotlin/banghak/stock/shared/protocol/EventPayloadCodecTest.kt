package banghak.stock.shared.protocol

import banghak.stock.core.domain.automation.ExecutionStage
import banghak.stock.core.domain.eventlog.*
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.money.Percent
import banghak.stock.core.domain.portfolio.BuyOrigin
import banghak.stock.core.domain.portfolio.LotId
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.OrderKind
import banghak.stock.core.domain.trading.OrderOrigin
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.domain.trading.OrderStatus
import banghak.stock.core.domain.trading.Quantity
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 이벤트 36종 전부가 JSON 을 거쳐 같은 값으로 돌아오고, 금액 자릿수가 보존됨. */
class EventPayloadCodecTest {
    private val codec = EventPayloadCodec()
    private val t0 = Instant.parse("2026-09-27T00:00:00Z")
    private val samsung = Symbol(Market.KR, "005930")
    private val nvidia = Symbol(Market.US, "NVDA")
    private val lot = LotId.from(Ulid.of(t0, ByteArray(10)))
    private val device = DeviceId.from(Ulid.of(t0, ByteArray(10) { 1 }))
    private val other = DeviceId.from(Ulid.of(t0, ByteArray(10) { 2 }))
    private val key = ClientOrderId("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
    private val fx = ExchangeRate(Currency.USD, Currency.KRW, BigDecimal("1350.55"), t0)

    private val samples: List<DomainEvent> =
        listOf(
            LotOpened(
                lot,
                nvidia,
                Quantity.of("1.5"),
                Money.of("123.40", Currency.USD),
                BuyOrigin.AUTO_BUY,
                fx,
                t0,
            ),
            LotOpened(
                lot,
                samsung,
                Quantity.of(10),
                Money.of("70000", Currency.KRW),
                BuyOrigin.MANUAL,
                null,
                t0,
            ),
            LotReduced(lot, Quantity.of(3), Money.of("71000", Currency.KRW), t0),
            LotClosed(lot, t0),
            LotAgedOutOfAutoBuy(lot, t0),
            OrderIntended(
                key,
                samsung,
                OrderSide.BUY,
                OrderKind.LIMIT,
                Quantity.of(10),
                Money.of("70000", Currency.KRW),
                null,
                OrderOrigin.AUTO_BUY,
            ),
            OrderIntended(
                key,
                nvidia,
                OrderSide.BUY,
                OrderKind.MARKET,
                null,
                null,
                Money.of("100.00", Currency.USD),
                OrderOrigin.MANUAL,
            ),
            OrderSubmitted(key, "B-1"),
            OrderAmendRequested(key, "B-1", Money.of("69000", Currency.KRW), Quantity.of(5)),
            OrderCancelRequested(key, "B-1"),
            OrderStatusChanged("B-1", OrderStatus.PENDING, OrderStatus.FILLED),
            OrderFilled(
                "B-1",
                Quantity.of(10),
                Money.of("70000", Currency.KRW),
                Money.of("35", Currency.KRW),
                Money.of("0", Currency.KRW),
            ),
            OrderResultUnknown(key, "timeout"),
            GuardrailEvaluated(key, false, listOf("TotalExposureCap", "DailyBuyCap")),
            AutomationSettingChanged(Market.KR, OrderSide.BUY, ExecutionStage.APPROVAL_REQUIRED),
            LimitsLowered("totalExposure.KR", "9000000"),
            LimitRaiseIgnored("totalExposure.KR", "20000000"),
            KillSwitchChanged(true, "daily loss"),
            AutoSellPermissionChanged(samsung, true, false, Quantity.of(100)),
            SimulatedOrderRecorded(
                key,
                samsung,
                OrderSide.SELL,
                Quantity.of(1),
                Money.of("70000", Currency.KRW),
            ),
            AutoBuyExclusionChanged(samsung, true),
            ApprovalRequested("a1", key, t0),
            ApprovalDecided("a1", true),
            ApprovalExpired("a1"),
            DebateStarted("s1", samsung, "BUY"),
            PersonaSpoke("s1", "p1", 2),
            UserIntervened("s1", "QUESTION"),
            RoundEnded("s1", 2),
            VerdictReached("s1", "BUY"),
            DebateResumed("s1", false),
            WatchlistChanged("g1", samsung, true),
            PersonaDefinitionChanged("p1", 3),
            UserSettingChanged("theme", "dark"),
            LayoutRatioChanged("main", Percent.ofPercent("60")),
            ChartModeChanged(true),
            JournalMemoChanged(samsung, "m1"),
            LeaseAcquired(device, t0),
            LeaseRenewed(device, t0),
            LeaseLost(device),
            LeaseForciblyTaken(device, other),
        )

    @Test
    @DisplayName("이벤트 36종 전부가 JSON 왕복 뒤 같은 값임")
    fun everyEventKindRoundTrips() {
        val covered = samples.map { it::class }.toSet()
        assertThat(covered).containsExactlyInAnyOrderElementsOf(DomainEvent::class.sealedSubclasses)
        for (event in samples) {
            val json = codec.encode(event)
            assertThat(codec.decode(EventEnvelope.typeOf(event), json))
                .describedAs(json)
                .isEqualTo(event)
        }
    }

    @Test
    @DisplayName("금액은 문자열로 적혀 자릿수가 보존됨")
    fun moneyKeepsScaleThroughJson() {
        val json =
            codec.encode(
                OrderFilled(
                    "B",
                    Quantity.of("0.000001"),
                    Money.of("12345678901234567.89", Currency.USD),
                    Money.of("0.10", Currency.USD),
                    Money.of("0", Currency.USD),
                )
            )
        assertThat(json)
            .contains("\"12345678901234567.89\"")
            .contains("\"0.10\"")
            .contains("\"0.000001\"")
    }

    @Test
    @DisplayName("모르는 이벤트 종류는 거부함")
    fun rejectsUnknownType() {
        org.assertj.core.api.Assertions.assertThatThrownBy { codec.decode("Nope", "{}") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
