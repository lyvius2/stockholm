package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.market.IndexCode
import banghak.stock.core.domain.market.IndexQuote
import banghak.stock.core.port.IndexQuoteStorePort
import banghak.stock.support.EngineDatabaseTest
import com.zaxxer.hikari.HikariDataSource
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/** 지수 티커의 마지막 값이 지수마다 한 행으로 남고 프록시·출처가 되살아남. */
class IndexQuotePersistenceTest : EngineDatabaseTest() {
    @Autowired private lateinit var store: IndexQuoteStorePort
    @Autowired private lateinit var engineWriteDataSource: HikariDataSource

    private val write by lazy { JdbcTemplate(engineWriteDataSource) }
    private val at = Instant.parse("2026-10-02T01:00:00Z")

    @BeforeEach
    fun clearTable() {
        write.update("delete from market_index_quote")
    }

    @Test
    @DisplayName("저장한 값을 같은 값으로 읽고, 같은 지수는 덮어쓰며, 프록시 티커와 출처가 함께 남음")
    fun roundTripsAndOverwrites() {
        val kospi =
            IndexQuote.of(
                IndexCode.KOSPI,
                BigDecimal("3412.85"),
                BigDecimal("3400.55"),
                at,
                false,
                null,
                "TOSS",
            )
        val sp500 =
            IndexQuote.of(IndexCode.SP500, BigDecimal("600.00"), null, at, false, "SPY", "TOSS_ETF")
        store.saveAll(listOf(kospi, sp500))
        store.saveAll(listOf(kospi.copy(value = BigDecimal("3420.00"))))

        val loaded = store.loadAll().associateBy { it.code }

        assertThat(loaded).hasSize(2)
        assertThat(loaded.getValue(IndexCode.KOSPI).value).isEqualByComparingTo("3420.00")
        assertThat(loaded.getValue(IndexCode.KOSPI).changeRatio).isEqualTo(kospi.changeRatio)
        assertThat(loaded.getValue(IndexCode.SP500)).isEqualTo(sp500)
        assertThat(
                write.queryForObject(
                    "select source from market_index_quote where index_code = 'SP500'",
                    String::class.java,
                )
            )
            .isEqualTo("TOSS_ETF:SPY")
    }
}
