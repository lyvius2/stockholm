package banghak.stock.engine.adapter.out.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

@Embeddable
data class StockKey(
    @Column(name = "market") val market: String,
    @Column(name = "code") val code: String,
) : Serializable

@Entity
@Table(name = "stock_warning")
class StockWarningEntity(
    @EmbeddedId val key: StockKey,
    @Column(name = "investment_warning", nullable = false) var investmentWarning: Boolean,
    @Column(name = "investment_risk", nullable = false) var investmentRisk: Boolean,
    @Column(name = "administrative") var administrative: Boolean?,
    @Column(name = "trading_halted") var tradingHalted: Boolean?,
    @Column(name = "vi_static", nullable = false) var viStatic: Boolean,
    @Column(name = "vi_dynamic", nullable = false) var viDynamic: Boolean,
    @Column(nullable = false) var overheated: Boolean,
    @Column(nullable = false) var liquidation: Boolean,
    @Column(name = "unknown_warning", nullable = false) var unknownWarning: Boolean,
    @Column(name = "fetched_at", nullable = false) var fetchedAt: Instant,
)
