package banghak.stock.core.domain.eventlog

import banghak.stock.core.domain.automation.ExecutionStage
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
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
import java.time.Instant

/**
 * 동기화·감사·projection의 원천이 되는 도메인 이벤트. 추가 전용. 계좌번호·키·토큰은 어떤 이벤트에도 들어가지 않음. 동기화 범위는 [SyncScopes]의 표
 * 한곳에서 정함.
 */
sealed interface DomainEvent

// portfolio
data class LotOpened(
    val lotId: LotId,
    val symbol: Symbol,
    val quantity: Quantity,
    val unitCost: Money,
    val origin: BuyOrigin,
    val fxAtBuy: ExchangeRate?,
    val openedAt: Instant,
) : DomainEvent

data class LotReduced(
    val lotId: LotId,
    val quantity: Quantity,
    val unitProceeds: Money,
    val disposedAt: Instant,
) : DomainEvent

data class LotClosed(val lotId: LotId, val closedAt: Instant) : DomainEvent

/** 보유 168시간이 지나 자동 매수 노출액에서 빠짐. */
data class LotAgedOutOfAutoBuy(val lotId: LotId, val agedOutAt: Instant) : DomainEvent

// trading
data class OrderIntended(
    val clientOrderId: ClientOrderId,
    val symbol: Symbol,
    val side: OrderSide,
    val kind: OrderKind,
    val quantity: Quantity?,
    val limitPrice: Money?,
    val orderAmount: Money?,
    val origin: OrderOrigin,
) : DomainEvent

data class OrderSubmitted(val clientOrderId: ClientOrderId, val brokerOrderId: String) : DomainEvent

data class OrderAmendRequested(
    val clientOrderId: ClientOrderId,
    val brokerOrderId: String,
    val newLimitPrice: Money?,
    val newQuantity: Quantity?,
) : DomainEvent

data class OrderCancelRequested(val clientOrderId: ClientOrderId, val brokerOrderId: String) :
    DomainEvent

data class OrderStatusChanged(
    val brokerOrderId: String,
    val from: OrderStatus,
    val to: OrderStatus,
) : DomainEvent

data class OrderFilled(
    val brokerOrderId: String,
    val quantity: Quantity,
    val price: Money,
    val fee: Money,
    val tax: Money,
) : DomainEvent

/** 주문 결과를 모름(타임아웃). 조회로 확정하기 전에는 재시도하지 않음. */
data class OrderResultUnknown(val clientOrderId: ClientOrderId, val reason: String) : DomainEvent

// guardrail / automation
data class GuardrailEvaluated(
    val clientOrderId: ClientOrderId,
    val passed: Boolean,
    val violations: List<String>,
) : DomainEvent

data class AutomationSettingChanged(
    val market: Market,
    val side: OrderSide,
    val stage: ExecutionStage,
) : DomainEvent

data class LimitsLowered(val limit: String, val value: String) : DomainEvent

/** 한도를 올리는 요청은 무시하고 기록만 남김. 한도는 낮출 수만 있음. */
data class LimitRaiseIgnored(val limit: String, val requested: String) : DomainEvent

data class KillSwitchChanged(val engaged: Boolean, val reason: String) : DomainEvent

data class AutoSellPermissionChanged(
    val symbol: Symbol,
    val allowed: Boolean,
    val includeStopLoss: Boolean,
    val baseQuantity: Quantity,
) : DomainEvent

data class SimulatedOrderRecorded(
    val clientOrderId: ClientOrderId,
    val symbol: Symbol,
    val side: OrderSide,
    val quantity: Quantity,
    val price: Money,
) : DomainEvent

data class AutoBuyExclusionChanged(val symbol: Symbol, val excluded: Boolean) : DomainEvent

data class ApprovalRequested(
    val approvalId: String,
    val clientOrderId: ClientOrderId,
    val expiresAt: Instant,
) : DomainEvent

data class ApprovalDecided(val approvalId: String, val approved: Boolean) : DomainEvent

data class ApprovalExpired(val approvalId: String) : DomainEvent

// debate — 세션·페르소나 식별자는 debate 패키지가 생기면 그 타입으로 바꿈
data class DebateStarted(val sessionId: String, val symbol: Symbol, val theme: String) : DomainEvent

data class PersonaSpoke(val sessionId: String, val personaId: String, val round: Int) : DomainEvent

data class UserIntervened(val sessionId: String, val kind: String) : DomainEvent

data class RoundEnded(val sessionId: String, val round: Int) : DomainEvent

data class VerdictReached(val sessionId: String, val conclusion: String) : DomainEvent

data class DebateResumed(val sessionId: String, val personaDefinitionsChanged: Boolean) :
    DomainEvent

// watchlist / settings
data class WatchlistChanged(val groupId: String, val symbol: Symbol, val added: Boolean) :
    DomainEvent

data class PersonaDefinitionChanged(val personaId: String, val version: Int) : DomainEvent

data class UserSettingChanged(val key: String, val value: String) : DomainEvent

data class LayoutRatioChanged(val splitter: String, val ratio: Percent) : DomainEvent

data class ChartModeChanged(val detailed: Boolean) : DomainEvent

data class JournalMemoChanged(val symbol: Symbol, val memoId: String) : DomainEvent

// lease
data class LeaseAcquired(val device: DeviceId, val until: Instant) : DomainEvent

data class LeaseRenewed(val device: DeviceId, val until: Instant) : DomainEvent

data class LeaseLost(val device: DeviceId) : DomainEvent

data class LeaseForciblyTaken(val byDevice: DeviceId, val fromDevice: DeviceId) : DomainEvent
