package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.trading.Candle
import banghak.stock.core.domain.trading.CandleCoverage
import banghak.stock.core.domain.trading.CandleInterval
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.port.CandleStorePort
import banghak.stock.support.EngineDatabaseTest
import com.zaxxer.hikari.HikariDataSource
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/** 봉과 보유 구간이 SQLite 에 설계대로 남고 범위 조회가 양 끝을 포함함. */
class CandlePersistenceTest : EngineDatabaseTest() {
    @Autowired private lateinit var store: CandleStorePort
    @Autowired private lateinit var engineWriteDataSource: HikariDataSource

    private val write by lazy { JdbcTemplate(engineWriteDataSource) }
    private val nvidia = TradingFixtures.nvidia
    private val t0 = Instant.parse("2026-09-30T14:00:00Z")
    private val fetchedAt = t0.plus(Duration.ofMinutes(2).plusSeconds(30))

    @BeforeEach
    fun clearTables() {
        write.update("delete from candle")
        write.update("delete from candle_coverage")
    }

    @Test
    @DisplayName("저장한 봉을 금액 자릿수 그대로 최신순으로 읽고, 범위는 양 끝을 포함함")
    fun savesAndReadsRange() {
        store.save((0..4).map { candle(it, "120.50") }, coverage(0, 4), fetchedAt)

        val range = store.candles(nvidia, M1, at(1), at(3), 10)

        assertThat(range.map { it.openTime }).containsExactly(at(3), at(2), at(1))
        assertThat(range.first()).isEqualTo(candle(3, "120.50"))
        assertThat(store.candles(nvidia, M1, at(0), at(4), 2).map { it.openTime })
            .containsExactly(at(4), at(3))
        assertThat(store.candles(TradingFixtures.samsung, M1, at(0), at(4), 10)).isEmpty()
        assertThat(store.candles(nvidia, CandleInterval.DAY_1, at(0), at(4), 10)).isEmpty()
    }

    @Test
    @DisplayName("같은 시각의 봉은 새 값으로 덮어쓰고, 받은 시각에 끝나지 않은 봉은 진행 중으로 표시함")
    fun upsertsAndMarksUnfinished() {
        store.save((0..2).map { candle(it, "120.50") }, coverage(0, 2), fetchedAt)

        // 받은 시각은 14:02:30 이라 14:02 봉만 진행 중
        assertThat(finalFlags()).containsExactly(1, 1, 0)

        store.save(listOf(candle(2, "121.00")), coverage(0, 2), fetchedAt.plusSeconds(60))

        assertThat(store.candles(nvidia, M1, at(2), at(2), 1).single().close)
            .isEqualTo(TradingFixtures.usd("121.00"))
        assertThat(finalFlags()).containsExactly(1, 1, 1)
        assertThat(write.queryForObject("select count(*) from candle", Int::class.java))
            .isEqualTo(3)
    }

    @Test
    @DisplayName("보유 구간은 종목·봉 단위마다 하나이고 다시 저장하면 바뀜")
    fun keepsOneCoveragePerSeries() {
        assertThat(store.coverage(nvidia, M1)).isNull()

        store.save(emptyList(), coverage(0, 2), fetchedAt)
        store.save(emptyList(), coverage(0, 4).copy(reachedStart = true), fetchedAt)

        assertThat(store.coverage(nvidia, M1)).isEqualTo(coverage(0, 4).copy(reachedStart = true))
        assertThat(store.coverage(nvidia, CandleInterval.DAY_1)).isNull()
    }

    @Test
    @DisplayName("보존 기간 정리는 그 봉 단위의 오래된 봉만 지우고 보유 구간을 줄이며, 남는 봉이 없으면 구간도 지움")
    fun purgesAndTrimsCoverage() {
        store.save(
            (0..4).map { candle(it, "120.50") },
            coverage(0, 4).copy(reachedStart = true),
            fetchedAt,
        )
        val day = candle(0, "120.50").copy(interval = CandleInterval.DAY_1)
        store.save(
            listOf(day),
            CandleCoverage(nvidia, CandleInterval.DAY_1, at(0), at(0), true),
            fetchedAt,
        )

        assertThat(store.purge(M1, at(2))).isEqualTo(2)

        assertThat(store.coverage(nvidia, M1)).isEqualTo(coverage(2, 4))
        assertThat(store.candles(nvidia, M1, at(0), at(4), 10)).hasSize(3)
        assertThat(store.candles(nvidia, CandleInterval.DAY_1, at(0), at(0), 10)).hasSize(1)

        store.purge(M1, at(10))
        assertThat(store.coverage(nvidia, M1)).isNull()
        assertThat(store.coverage(nvidia, CandleInterval.DAY_1)).isNotNull()
    }

    private fun finalFlags(): List<Int> =
        write
            .queryForList("select is_final from candle order by open_time", Int::class.java)
            .filterNotNull()

    private fun coverage(from: Int, to: Int) =
        CandleCoverage(nvidia, M1, at(from), at(to), reachedStart = false)

    private fun candle(minute: Int, close: String): Candle {
        val price = TradingFixtures.usd(close)
        return Candle(nvidia, M1, at(minute), price, price, price, price, Quantity.of("1.5"))
    }

    private fun at(minute: Int): Instant = t0.plus(Duration.ofMinutes(minute.toLong()))

    companion object {
        private val M1 = CandleInterval.MINUTE_1
    }
}
