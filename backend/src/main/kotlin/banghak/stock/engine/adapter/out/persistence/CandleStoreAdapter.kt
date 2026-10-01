package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.Candle
import banghak.stock.core.domain.trading.CandleCoverage
import banghak.stock.core.domain.trading.CandleInterval
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.port.CandleStorePort
import banghak.stock.engine.adapter.out.persistence.converter.InstantTextConverter
import banghak.stock.engine.adapter.out.persistence.entity.CandleCoverageEntity
import banghak.stock.engine.adapter.out.persistence.entity.CandleEntity
import banghak.stock.engine.adapter.out.persistence.entity.CandleSeriesKey
import banghak.stock.engine.adapter.out.persistence.repository.CandleCoverageRepository
import banghak.stock.engine.adapter.out.persistence.repository.CandleRepository
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 봉 저장소.
 * 읽기와 보유 구간은 JPA, 봉 쓰기·지우기는 한 번에 수백~수천 행이라 jOOQ 일괄 처리로 함.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class CandleStoreAdapter(
    private val dsl: DSLContext,
    private val candles: CandleRepository,
    private val coverages: CandleCoverageRepository,
) : CandleStorePort {
    @Transactional(readOnly = true)
    override fun coverage(symbol: Symbol, interval: CandleInterval): CandleCoverage? =
        coverages.findById(seriesKeyOf(symbol, interval)).orElse(null)?.let(::coverageOf)

    @Transactional(readOnly = true)
    override fun candles(
        symbol: Symbol,
        interval: CandleInterval,
        from: Instant,
        before: Instant,
        limit: Int,
    ): List<Candle> =
        candles
            .findRange(
                symbol.market.name,
                symbol.code,
                codeOf(interval),
                from,
                before,
                Limit.of(limit),
            )
            .map { candleOf(symbol, interval, it) }

    @Transactional
    override fun save(candles: List<Candle>, coverage: CandleCoverage?, fetchedAt: Instant) {
        candles.chunked(ROWS_PER_STATEMENT).forEach { upsert(it, fetchedAt) }
        if (coverage != null) saveCoverage(coverage, fetchedAt)
    }

    @Transactional
    override fun purge(interval: CandleInterval, olderThan: Instant): Int {
        val deleted =
            dsl.deleteFrom(CANDLE)
                .where(INTERVAL.eq(codeOf(interval)))
                .and(OPEN_TIME.lt(text(olderThan)))
                .execute()
        coverages.findSeries(codeOf(interval)).forEach { row ->
            val trimmed = coverageOf(row).trimmedTo(olderThan)
            if (trimmed == null) coverages.delete(row)
            else {
                row.coveredFrom = trimmed.from
                row.reachedStart = trimmed.reachedStart
            }
        }
        return deleted
    }

    private fun upsert(candles: List<Candle>, fetchedAt: Instant) {
        val insert =
            candles.fold(dsl.insertInto(CANDLE).columns(COLUMNS)) { step, candle ->
                step.values(rowOf(candle, fetchedAt))
            }
        insert
            .onConflict(MARKET, CODE, INTERVAL, OPEN_TIME)
            .doUpdate()
            .set(UPDATABLE_COLUMNS.associateWith { DSL.excluded(it) })
            .execute()
    }

    private fun saveCoverage(coverage: CandleCoverage, at: Instant) {
        val key = seriesKeyOf(coverage.symbol, coverage.interval)
        val row = coverages.findById(key).orElse(null)
        if (row == null) {
            coverages.save(
                CandleCoverageEntity(key, coverage.from, coverage.to, coverage.reachedStart, at)
            )
            return
        }
        row.coveredFrom = coverage.from
        row.coveredTo = coverage.to
        row.reachedStart = coverage.reachedStart
        row.updatedAt = at
    }

    // 받은 시각에 아직 끝나지 않은 봉은 진행 중(is_final = 0)이며 다음에 받을 때 덮어씀
    private fun rowOf(candle: Candle, fetchedAt: Instant): List<Any?> =
        listOf(
            candle.symbol.market.name,
            candle.symbol.code,
            codeOf(candle.interval),
            text(candle.openTime),
            candle.open.amount.toPlainString(),
            candle.high.amount.toPlainString(),
            candle.low.amount.toPlainString(),
            candle.close.amount.toPlainString(),
            candle.open.currency.name,
            candle.volume.value.toPlainString(),
            SOURCE_TOSS,
            if (candle.openTime.plus(candle.interval.length).isAfter(fetchedAt)) 0 else 1,
            text(fetchedAt),
        )

    private fun candleOf(symbol: Symbol, interval: CandleInterval, row: CandleEntity): Candle {
        val currency = Currency.valueOf(row.currency)
        return Candle(
            symbol,
            interval,
            row.key.openTime,
            Money.of(row.open, currency),
            Money.of(row.high, currency),
            Money.of(row.low, currency),
            Money.of(row.close, currency),
            Quantity.of(row.volume),
        )
    }

    private fun coverageOf(row: CandleCoverageEntity): CandleCoverage =
        CandleCoverage(
            Symbol(Market.valueOf(row.key.market), row.key.code),
            intervalOf(row.key.interval),
            row.coveredFrom,
            row.coveredTo,
            row.reachedStart,
        )

    private fun seriesKeyOf(symbol: Symbol, interval: CandleInterval) =
        CandleSeriesKey(symbol.market.name, symbol.code, codeOf(interval))

    private fun codeOf(interval: CandleInterval): String =
        when (interval) {
            CandleInterval.MINUTE_1 -> "M1"
            CandleInterval.DAY_1 -> "D1"
        }

    private fun intervalOf(code: String): CandleInterval =
        CandleInterval.entries.single { codeOf(it) == code }

    private fun text(at: Instant): String = InstantTextConverter.FORMAT.format(at)

    companion object {
        // 행마다 열 13개라 SQLite 바인드 변수 한도(32766) 안에서 넉넉히 끊음
        private const val ROWS_PER_STATEMENT = 500
        private const val SOURCE_TOSS = "TOSS"

        private val CANDLE = DSL.table("candle")
        private val MARKET: Field<String> = DSL.field("market", String::class.java)
        private val CODE: Field<String> = DSL.field("code", String::class.java)
        private val INTERVAL: Field<String> = DSL.field("interval", String::class.java)
        private val OPEN_TIME: Field<String> = DSL.field("open_time", String::class.java)
        private val COLUMNS: List<Field<*>> =
            listOf(
                MARKET,
                CODE,
                INTERVAL,
                OPEN_TIME,
                DSL.field("open", String::class.java),
                DSL.field("high", String::class.java),
                DSL.field("low", String::class.java),
                DSL.field("close", String::class.java),
                DSL.field("currency", String::class.java),
                DSL.field("volume", String::class.java),
                DSL.field("source", String::class.java),
                DSL.field("is_final", Int::class.java),
                DSL.field("fetched_at", String::class.java),
            )
        private val UPDATABLE_COLUMNS = COLUMNS - setOf(MARKET, CODE, INTERVAL, OPEN_TIME)
    }
}
