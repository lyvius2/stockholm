package banghak.stock.engine.adapter.out.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "broker_order")
class BrokerOrderEntity(
    @Id @Column(name = "broker_order_id") val brokerOrderId: String,
    @Column(name = "client_order_id") var clientOrderId: String?,
    @Column(name = "replaces_broker_order_id") var replacesBrokerOrderId: String?,
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(nullable = false) val market: String,
    @Column(nullable = false) val code: String,
    @Column(nullable = false) val side: String,
    @Column(nullable = false) var kind: String,
    @Column(name = "time_in_force", nullable = false) var timeInForce: String,
    @Column(name = "limit_price_amount") var limitPriceAmount: BigDecimal?,
    @Column(name = "limit_price_currency") var limitPriceCurrency: String?,
    @Column var quantity: BigDecimal?,
    @Column(name = "order_amount_amount") var orderAmountAmount: BigDecimal?,
    @Column(name = "order_amount_currency") var orderAmountCurrency: String?,
    @Column(nullable = false) var status: String,
    @Column(name = "filled_quantity", nullable = false) var filledQuantity: BigDecimal,
    @Column(name = "avg_price_amount") var avgPriceAmount: BigDecimal?,
    @Column(name = "avg_price_currency") var avgPriceCurrency: String?,
    @Column(name = "fee_amount") var feeAmount: BigDecimal?,
    @Column(name = "tax_amount") var taxAmount: BigDecimal?,
    @Column(name = "filled_at") var filledAt: Instant?,
    @Column(name = "canceled_at") var canceledAt: Instant?,
    @Column(name = "reject_reason") var rejectReason: String?,
    @Column(nullable = false) var origin: String,
    @Column(name = "trigger_type", nullable = false) var triggerType: String,
    @Column(name = "trigger_json") var triggerJson: String?,
    @Column(nullable = false) var remote: Boolean,
    @Column(name = "high_value_confirmed", nullable = false) var highValueConfirmed: Boolean,
    @Column(name = "ordered_at", nullable = false) var orderedAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @Column(name = "fetched_at", nullable = false) var fetchedAt: Instant,
)

@Entity
@Table(name = "lot")
class LotEntity(
    @Id @Column(name = "lot_id") val lotId: String,
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(nullable = false) val market: String,
    @Column(nullable = false) val code: String,
    @Column(name = "bought_quantity", nullable = false) val boughtQuantity: BigDecimal,
    @Column(name = "remaining_quantity", nullable = false) var remainingQuantity: BigDecimal,
    @Column(name = "unit_cost_amount", nullable = false) val unitCostAmount: BigDecimal,
    @Column(name = "unit_cost_currency", nullable = false) val unitCostCurrency: String,
    @Column(name = "fx_from") val fxFrom: String?,
    @Column(name = "fx_to") val fxTo: String?,
    @Column(name = "fx_rate") val fxRate: BigDecimal?,
    @Column(name = "fx_as_of") val fxAsOf: Instant?,
    @Column(name = "bought_at", nullable = false) val boughtAt: Instant,
    @Column(nullable = false) val origin: String,
    @Column(name = "broker_order_id") val brokerOrderId: String?,
    @Column(name = "recommendation_id") val recommendationId: String?,
    @Column(name = "aged_out_at") var agedOutAt: Instant?,
    @Column(name = "closed_at") var closedAt: Instant?,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)

@Entity
@Table(name = "order_submission")
class OrderSubmissionEntity(
    @Id @Column(name = "client_order_id") val clientOrderId: String,
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(name = "device_id", nullable = false) val deviceId: String,
    @Column(nullable = false) val market: String,
    @Column(nullable = false) val code: String,
    @Column(nullable = false) val side: String,
    @Column(nullable = false) val kind: String,
    @Column(name = "time_in_force", nullable = false) val timeInForce: String,
    @Column(name = "limit_price_amount") val limitPriceAmount: BigDecimal?,
    @Column(name = "limit_price_currency") val limitPriceCurrency: String?,
    @Column val quantity: BigDecimal?,
    @Column(name = "order_amount_amount") val orderAmountAmount: BigDecimal?,
    @Column(name = "order_amount_currency") val orderAmountCurrency: String?,
    @Column(nullable = false) val origin: String,
    @Column(name = "trigger_type", nullable = false) val triggerType: String,
    @Column(name = "trigger_json") val triggerJson: String?,
    @Column(name = "intended_at", nullable = false) val intendedAt: Instant,
    @Column(name = "high_value_confirmed", nullable = false) val highValueConfirmed: Boolean,
    @Column(nullable = false) var state: String,
    @Column(name = "broker_order_id") var brokerOrderId: String?,
    @Column var reason: String?,
    @Column(name = "sent_at", nullable = false) val sentAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)
