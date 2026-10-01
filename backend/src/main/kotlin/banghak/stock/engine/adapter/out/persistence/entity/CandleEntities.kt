package banghak.stock.engine.adapter.out.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant

@Embeddable
data class CandleKey(
    @Column(name = "market") val market: String,
    @Column(name = "code") val code: String,
    @Column(name = "interval") val interval: String,
    @Column(name = "open_time") val openTime: Instant,
) : Serializable

/**
 * 읽기 전용으로 씀.
 * 쓰기는 jOOQ 일괄 upsert 가 함.
 */
@Entity
@Table(name = "candle")
class CandleEntity(
    @EmbeddedId val key: CandleKey,
    @Column(nullable = false) val open: BigDecimal,
    @Column(nullable = false) val high: BigDecimal,
    @Column(nullable = false) val low: BigDecimal,
    @Column(nullable = false) val close: BigDecimal,
    @Column(nullable = false) val currency: String,
    @Column(nullable = false) val volume: BigDecimal,
)

@Embeddable
data class CandleSeriesKey(
    @Column(name = "market") val market: String,
    @Column(name = "code") val code: String,
    @Column(name = "interval") val interval: String,
) : Serializable

@Entity
@Table(name = "candle_coverage")
class CandleCoverageEntity(
    @EmbeddedId val key: CandleSeriesKey,
    @Column(name = "covered_from", nullable = false) var coveredFrom: Instant,
    @Column(name = "covered_to", nullable = false) var coveredTo: Instant,
    @Column(name = "reached_start", nullable = false) var reachedStart: Boolean,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)
