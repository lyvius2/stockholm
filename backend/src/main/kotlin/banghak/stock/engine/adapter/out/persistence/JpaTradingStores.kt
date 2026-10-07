package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.portfolio.BuyOrigin
import banghak.stock.core.domain.portfolio.Lot
import banghak.stock.core.domain.portfolio.LotDisposal
import banghak.stock.core.domain.portfolio.LotId
import banghak.stock.core.domain.trading.BrokerOrder
import banghak.stock.core.domain.trading.BrokerOrderRecord
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.domain.trading.FillSummary
import banghak.stock.core.domain.trading.OrderIntent
import banghak.stock.core.domain.trading.OrderKind
import banghak.stock.core.domain.trading.OrderListing
import banghak.stock.core.domain.trading.OrderOrigin
import banghak.stock.core.domain.trading.OrderProgress
import banghak.stock.core.domain.trading.OrderSide
import banghak.stock.core.domain.trading.OrderStatus
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.RecordedOrder
import banghak.stock.core.domain.trading.TimeInForce
import banghak.stock.core.port.BrokerOrderStorePort
import banghak.stock.core.port.LotStorePort
import banghak.stock.engine.adapter.out.persistence.entity.BrokerOrderEntity
import banghak.stock.engine.adapter.out.persistence.entity.LotDisposalEntity
import banghak.stock.engine.adapter.out.persistence.entity.LotEntity
import banghak.stock.engine.adapter.out.persistence.repository.BrokerOrderRepository
import banghak.stock.engine.adapter.out.persistence.repository.LotDisposalRepository
import banghak.stock.engine.adapter.out.persistence.repository.LotRepository
import banghak.stock.shared.config.RuntimeProfiles
import banghak.stock.shared.crypto.UlidGenerator
import java.math.BigDecimal
import java.time.Instant
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@Profile(RuntimeProfiles.ENGINE)
class JpaBrokerOrderStore(private val repository: BrokerOrderRepository) : BrokerOrderStorePort {
    @Transactional
    override fun recordAccepted(order: BrokerOrder, isHighValueConfirmed: Boolean) {
        val existing = repository.findById(order.brokerOrderId).orElse(null)
        if (existing != null && existing.userId != order.intent.userId.value)
            throw InvalidValueException("주문 ${order.brokerOrderId} 은 다른 사용자의 기록임")
        val row = existing ?: newRow(order)
        row.clientOrderId = order.clientOrderId.value
        row.origin = order.intent.origin.name
        row.triggerType = TriggerCodec.typeOf(order.intent.trigger)
        row.triggerJson = TriggerCodec.jsonOf(order.intent.trigger)
        row.highValueConfirmed = isHighValueConfirmed
        repository.save(row)
    }

    @Transactional(readOnly = true)
    override fun findPlacedSince(
        userId: UserId,
        market: Market,
        since: Instant,
    ): List<BrokerOrder> =
        repository
            .findPlacedSince(userId.value, market.name, since, TriggerCodec.EXTERNAL)
            .map(::toDomain)

    @Transactional(readOnly = true)
    override fun findRecorded(userId: UserId, brokerOrderId: String): RecordedOrder? =
        repository.findOwned(userId.value, brokerOrderId)?.let(::recordedOf)

    @Transactional(readOnly = true)
    override fun findOrigin(userId: UserId, brokerOrderId: String): OrderOrigin? =
        repository.findOwned(userId.value, brokerOrderId)?.let { OrderOrigin.valueOf(it.origin) }

    @Transactional
    override fun applyBrokerRecord(userId: UserId, record: BrokerOrderRecord, at: Instant) {
        val existing = repository.findById(record.brokerOrderId).orElse(null)
        if (existing != null && existing.userId != userId.value)
            throw InvalidValueException("주문 ${record.brokerOrderId} 은 다른 사용자의 기록임")
        val row = existing ?: externalRow(userId, record, at)
        row.kind = record.kind.name
        row.timeInForce = record.timeInForce.name
        row.limitPriceAmount = record.limitPrice?.amount
        row.limitPriceCurrency = record.limitPrice?.currency?.name
        row.quantity = record.quantity?.value
        row.orderAmountAmount = record.orderAmount?.amount
        row.orderAmountCurrency = record.orderAmount?.currency?.name
        row.status = record.status.name
        row.filledQuantity = record.filledQuantity.value
        row.avgPriceAmount = record.averageFilledPrice?.amount
        row.avgPriceCurrency = record.averageFilledPrice?.currency?.name
        row.filledAmountAmount = record.filledAmount?.amount
        row.filledAmountCurrency = record.filledAmount?.currency?.name
        row.feeAmount = record.fee?.amount
        row.taxAmount = record.tax?.amount
        row.filledAt = record.filledAt
        row.canceledAt = record.canceledAt
        row.orderedAt = record.orderedAt
        row.updatedAt = at
        row.fetchedAt = at
        repository.save(row)
    }

    @Transactional
    override fun markFillQueued(userId: UserId, brokerOrderId: String, queued: FillSummary) {
        val row =
            repository.findOwned(userId.value, brokerOrderId)
                ?: throw InvalidValueException("주문 기록이 없어 체결 대기열 요약을 남길 수 없음")
        row.queuedQuantity = queued.quantity.value
        row.queuedAmount = queued.amount.amount
        row.queuedFee = queued.fee.amount
        row.queuedTax = queued.tax.amount
    }

    @Transactional(readOnly = true)
    override fun openBrokerOrderIds(userId: UserId): Set<String> =
        repository.findIdsByUserIdAndStatuses(userId.value, OPEN_STATUSES).toSet()

    // 처음 보는 주문은 Stockholm 밖에서 낸 것이라 멱등 키·트리거가 없고 출처는 수동으로 둠
    private fun externalRow(userId: UserId, record: BrokerOrderRecord, at: Instant) =
        BrokerOrderEntity(
            brokerOrderId = record.brokerOrderId,
            clientOrderId = null,
            replacesBrokerOrderId = null,
            userId = userId.value,
            market = record.symbol.market.name,
            code = record.symbol.code,
            side = record.side.name,
            kind = record.kind.name,
            timeInForce = record.timeInForce.name,
            limitPriceAmount = null,
            limitPriceCurrency = null,
            quantity = null,
            orderAmountAmount = null,
            orderAmountCurrency = null,
            status = record.status.name,
            filledQuantity = record.filledQuantity.value,
            avgPriceAmount = null,
            avgPriceCurrency = null,
            filledAmountAmount = null,
            filledAmountCurrency = null,
            feeAmount = null,
            taxAmount = null,
            filledAt = null,
            canceledAt = null,
            rejectReason = null,
            origin = OrderOrigin.MANUAL.name,
            triggerType = TriggerCodec.EXTERNAL,
            triggerJson = null,
            remote = false,
            highValueConfirmed = false,
            orderedAt = record.orderedAt,
            updatedAt = at,
            fetchedAt = at,
        )

    // 대기열에 넣은 요약이 없던 행(처음 보는 주문)은 아직 아무것도 넣지 않은 것임
    @Transactional(readOnly = true)
    override fun findOpen(userId: UserId): List<OrderListing> =
        repository.findByUserIdAndStatuses(userId.value, OPEN_STATUSES).map(::listingOf)

    @Transactional(readOnly = true)
    override fun findClosedSince(userId: UserId, since: Instant): List<OrderListing> =
        repository
            .findByUserIdAndStatusesSince(userId.value, CLOSED_STATUSES, since)
            .map(::listingOf)

    // 화면 목록은 의도·트리거를 복원하지 않고 행의 사실만 옮김(외부 주문도 같은 모양)
    private fun listingOf(row: BrokerOrderEntity): OrderListing =
        OrderListing(
            brokerOrderId = row.brokerOrderId,
            replacesBrokerOrderId = row.replacesBrokerOrderId,
            symbol = Symbol(Market.valueOf(row.market), row.code),
            side = OrderSide.valueOf(row.side),
            kind = OrderKind.valueOf(row.kind),
            timeInForce = TimeInForce.valueOf(row.timeInForce),
            limitPrice = money(row.limitPriceAmount, row.limitPriceCurrency),
            quantity = row.quantity?.let(Quantity::of),
            orderAmount = money(row.orderAmountAmount, row.orderAmountCurrency),
            status = statusOf(row.status),
            filledQuantity = Quantity.of(row.filledQuantity),
            averageFilledPrice = money(row.avgPriceAmount, row.avgPriceCurrency),
            filledAmount = money(row.filledAmountAmount, row.filledAmountCurrency),
            origin = OrderOrigin.valueOf(row.origin),
            isPlacedByStockholm = row.triggerType != TriggerCodec.EXTERNAL,
            orderedAt = row.orderedAt,
            updatedAt = row.updatedAt,
            closedAt = row.canceledAt ?: row.filledAt,
        )

    private fun recordedOf(row: BrokerOrderEntity): RecordedOrder {
        val currency = Market.valueOf(row.market).currency
        val money = { amount: BigDecimal? -> Money.of(amount ?: BigDecimal.ZERO, currency) }
        return RecordedOrder(
            progress = OrderProgress(statusOf(row.status), Quantity.of(row.filledQuantity)),
            isPlacedByStockholm = row.triggerType != TriggerCodec.EXTERNAL,
            origin = OrderOrigin.valueOf(row.origin),
            queuedFill =
                FillSummary(
                    Quantity.of(row.queuedQuantity ?: BigDecimal.ZERO),
                    money(row.queuedAmount),
                    money(row.queuedFee),
                    money(row.queuedTax),
                ),
        )
    }

    private fun statusOf(name: String): OrderStatus =
        OrderStatus.entries.firstOrNull { it.name == name } ?: OrderStatus.UNKNOWN

    // 실시간 이벤트가 먼저 와 행이 있으면 그 행의 상태·체결을 그대로 두고 의도만 채움
    private fun newRow(order: BrokerOrder): BrokerOrderEntity {
        val intent = order.intent
        return BrokerOrderEntity(
            brokerOrderId = order.brokerOrderId,
            clientOrderId = order.clientOrderId.value,
            replacesBrokerOrderId = order.replacesBrokerOrderId,
            userId = intent.userId.value,
            market = intent.symbol.market.name,
            code = intent.symbol.code,
            side = intent.side.name,
            kind = intent.kind.name,
            timeInForce = intent.timeInForce.name,
            limitPriceAmount = intent.limitPrice?.amount,
            limitPriceCurrency = intent.limitPrice?.currency?.name,
            quantity = intent.quantity?.value,
            orderAmountAmount = intent.orderAmount?.amount,
            orderAmountCurrency = intent.orderAmount?.currency?.name,
            status = order.status.name,
            filledQuantity = order.filledQuantity.value,
            avgPriceAmount = order.averageFilledPrice?.amount,
            avgPriceCurrency = order.averageFilledPrice?.currency?.name,
            filledAmountAmount = null,
            filledAmountCurrency = null,
            feeAmount = null,
            taxAmount = null,
            filledAt = null,
            canceledAt = null,
            rejectReason = null,
            origin = intent.origin.name,
            triggerType = TriggerCodec.typeOf(intent.trigger),
            triggerJson = TriggerCodec.jsonOf(intent.trigger),
            remote = false,
            highValueConfirmed = false,
            orderedAt = order.updatedAt,
            updatedAt = order.updatedAt,
            fetchedAt = order.updatedAt,
        )
    }

    // 의도 시각은 따로 저장하지 않아 주문 시각으로 채움(가드레일은 이 값을 쓰지 않음)
    private fun toDomain(row: BrokerOrderEntity): BrokerOrder {
        val market = Market.valueOf(row.market)
        return BrokerOrder(
            clientOrderId =
                ClientOrderId(
                    row.clientOrderId ?: error("Stockholm 이 낸 주문 ${row.brokerOrderId} 에 멱등 키가 없음")
                ),
            brokerOrderId = row.brokerOrderId,
            replacesBrokerOrderId = row.replacesBrokerOrderId,
            intent =
                OrderIntent(
                    userId = UserId(row.userId),
                    symbol = Symbol(market, row.code),
                    side = OrderSide.valueOf(row.side),
                    kind = OrderKind.valueOf(row.kind),
                    timeInForce = TimeInForce.valueOf(row.timeInForce),
                    limitPrice = money(row.limitPriceAmount, row.limitPriceCurrency),
                    quantity = row.quantity?.let(Quantity::of),
                    orderAmount = money(row.orderAmountAmount, row.orderAmountCurrency),
                    origin = OrderOrigin.valueOf(row.origin),
                    trigger = TriggerCodec.decode(row.triggerType, row.triggerJson),
                    intendedAt = row.orderedAt,
                ),
            status = statusOf(row.status),
            filledQuantity = Quantity.of(row.filledQuantity),
            averageFilledPrice = money(row.avgPriceAmount, row.avgPriceCurrency),
            updatedAt = row.updatedAt,
        )
    }

    companion object {
        private val OPEN_STATUSES = OrderStatus.entries.filter { it.isOpen }.map { it.name }
        // 모름(UNKNOWN)은 닫힌 것도 열린 것도 아니라 둘 다에서 뺌
        private val CLOSED_STATUSES =
            OrderStatus.entries.filter { !it.isOpen && it != OrderStatus.UNKNOWN }.map { it.name }
    }
}

@Component
@Profile(RuntimeProfiles.ENGINE)
class JpaLotStore(
    private val repository: LotRepository,
    private val disposals: LotDisposalRepository,
    private val ulids: UlidGenerator,
) : LotStorePort {
    @Transactional(readOnly = true)
    override fun openLots(userId: UserId, market: Market): List<Lot> =
        repository.findOpenLots(userId.value, market.name).map(::toDomain)

    @Transactional
    override fun saveOpened(lot: Lot, brokerOrderId: String?) {
        repository.save(
            LotEntity(
                lotId = lot.id.value,
                userId = lot.userId.value,
                market = lot.symbol.market.name,
                code = lot.symbol.code,
                boughtQuantity = lot.boughtQuantity.value,
                remainingQuantity = lot.remainingQuantity.value,
                unitCostAmount = lot.unitCost.amount,
                unitCostCurrency = lot.unitCost.currency.name,
                fxFrom = lot.fxAtBuy?.from?.name,
                fxTo = lot.fxAtBuy?.to?.name,
                fxRate = lot.fxAtBuy?.rate,
                fxAsOf = lot.fxAtBuy?.asOf,
                boughtAt = lot.boughtAt,
                origin = lot.origin.name,
                brokerOrderId = brokerOrderId,
                recommendationId = null,
                agedOutAt = null,
                closedAt = null,
                createdAt = lot.boughtAt,
                updatedAt = lot.boughtAt,
                opening = lot.isOpening,
            )
        )
    }

    @Transactional
    override fun saveReduced(lots: List<Lot>, at: Instant) {
        lots.forEach { lot ->
            val row =
                repository.findOwned(lot.userId.value, lot.id.value)
                    ?: throw InvalidValueException("lot ${lot.id} 의 기록이 없음")
            row.remainingQuantity = lot.remainingQuantity.value
            if (!lot.isOpen) row.closedAt = at
            row.updatedAt = at
        }
    }

    @Transactional
    override fun saveDisposals(
        userId: UserId,
        symbol: Symbol,
        disposals: List<LotDisposal>,
        at: Instant,
    ) {
        this.disposals.saveAll(disposals.map { disposalRowOf(userId, symbol, it, at) })
    }

    private fun disposalRowOf(userId: UserId, symbol: Symbol, disposal: LotDisposal, at: Instant) =
        LotDisposalEntity(
            disposalId = ulids.next().value,
            userId = userId.value,
            lotId = disposal.lotId.value,
            brokerOrderId = disposal.brokerOrderId,
            market = symbol.market.name,
            code = symbol.code,
            quantity = disposal.quantity.value,
            sellPriceAmount = disposal.sellPrice.amount,
            sellPriceCurrency = disposal.sellPrice.currency.name,
            buyUnitCostAmount = disposal.buyUnitCost.amount,
            buyUnitCostCurrency = disposal.buyUnitCost.currency.name,
            feeAmount = disposal.fee.amount,
            taxAmount = disposal.tax.amount,
            fxFrom = disposal.fxAtSell?.from?.name,
            fxTo = disposal.fxAtSell?.to?.name,
            fxRate = disposal.fxAtSell?.rate,
            fxAsOf = disposal.fxAtSell?.asOf,
            realizedAmount = disposal.realized.amount,
            realizedCurrency = disposal.realized.currency.name,
            realizedKrw = disposal.realizedKrw?.amount,
            fxPnlKrw = disposal.fxPnlKrw?.amount,
            holdingDays = disposal.holdingDays,
            lotOrigin = disposal.lotOrigin.name,
            disposedAt = disposal.disposedAt,
            createdAt = at,
        )

    private fun toDomain(row: LotEntity): Lot {
        val currency = Currency.valueOf(row.unitCostCurrency)
        return Lot(
            id = LotId(row.lotId),
            userId = UserId(row.userId),
            symbol = Symbol(Market.valueOf(row.market), row.code),
            boughtQuantity = Quantity.of(row.boughtQuantity),
            remainingQuantity = Quantity.of(row.remainingQuantity),
            unitCost = Money.of(row.unitCostAmount, currency),
            fxAtBuy = fxOf(row),
            boughtAt = row.boughtAt,
            origin = BuyOrigin.valueOf(row.origin),
            isOpening = row.opening,
        )
    }

    private fun fxOf(row: LotEntity): ExchangeRate? {
        val rate = row.fxRate ?: return null
        return ExchangeRate(
            Currency.valueOf(requireNotNull(row.fxFrom)),
            Currency.valueOf(requireNotNull(row.fxTo)),
            rate,
            requireNotNull(row.fxAsOf),
        )
    }
}

private fun money(amount: BigDecimal?, currency: String?): Money? =
    if (amount == null || currency == null) null else Money.of(amount, Currency.valueOf(currency))
