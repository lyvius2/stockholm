package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.market.KrTradingDetail
import banghak.stock.core.domain.market.ListingBoard
import banghak.stock.core.domain.market.ListingStatus
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.StockFlags
import banghak.stock.core.domain.market.StockQuery
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.port.StockFlagsCachePort
import banghak.stock.core.port.StockMasterPort
import banghak.stock.support.EngineDatabaseTest
import banghak.stock.support.fakes.FakeStockCatalog
import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/** 종목 마스터 upsert·상장폐지 표시와 경고 캐시가 SQLite 에 설계대로 남음. */
class StockMasterPersistenceTest : EngineDatabaseTest() {
    @Autowired private lateinit var master: StockMasterPort
    @Autowired private lateinit var flagsCache: StockFlagsCachePort
    @Autowired private lateinit var engineWriteDataSource: HikariDataSource

    private val write by lazy { JdbcTemplate(engineWriteDataSource) }
    private val at = Instant.parse("2026-09-29T00:00:00Z")
    private val samsung = Symbol(Market.KR, "005930")
    private val hynix = Symbol(Market.KR, "000660")
    private val kosdaq = Symbol(Market.KR, "035720")

    @BeforeEach
    fun clearTables() {
        write.update("delete from stock_master")
        write.update("delete from stock_warning")
    }

    @Test
    @DisplayName("다시 저장하면 토스 열은 새 값으로 바꾸고 KRX·DART 가 채운 업종은 남김")
    fun upsertKeepsEnrichedColumns() {
        master.saveAll(listOf(profile(samsung, ListingBoard.KOSPI)), at)
        write.update("update stock_master set sector_name = '전기전자' where code = '005930'")

        master.saveAll(
            listOf(profile(samsung, ListingBoard.KOSPI).copy(name = "삼성전자")),
            at.plusSeconds(60),
        )

        val row = write.queryForMap("select * from stock_master where code = '005930'")
        assertThat(row["name"]).isEqualTo("삼성전자")
        assertThat(row["chosung"]).isEqualTo("ㅅㅅㅈㅈ")
        assertThat(row["listing_board"]).isEqualTo("KOSPI")
        assertThat(row["nxt_supported"]).isEqualTo(1)
        assertThat(row["sector_name"]).isEqualTo("전기전자")
        assertThat(row["updated_at"]).isEqualTo("2026-09-29T00:01:00.000Z")
    }

    @Test
    @DisplayName("종목명·코드·초성 앞부분으로 찾고 앞부분 일치가 먼저 오며, 상장폐지 종목은 결과에 없음")
    fun searchByNameCodeChosung() {
        master.saveAll(
            listOf(
                profile(samsung, ListingBoard.KOSPI).copy(name = "삼성전자"),
                profile(hynix, ListingBoard.KOSPI).copy(name = "SK하이닉스"),
                profile(kosdaq, ListingBoard.KOSDAQ).copy(name = "카카오"),
                profile(Symbol(Market.KR, "005935"), ListingBoard.KOSPI).copy(name = "삼성전자우"),
                profile(Symbol(Market.KR, "009150"), ListingBoard.KOSPI).copy(name = "삼성전기"),
            ),
            at,
        )
        write.update("update stock_master set delisted = 1 where code = '009150'")

        assertThat(master.search(StockQuery("삼성전")).map { it.name })
            .containsExactly("삼성전자", "삼성전자우")
        assertThat(master.search(StockQuery("ㅅㅅㅈㅈ")).map { it.symbol.code })
            .containsExactly("005930", "005935")
        assertThat(master.search(StockQuery("0059")).map { it.symbol.code })
            .containsExactly("005930", "005935")
        assertThat(master.search(StockQuery("하이닉스")).map { it.name }).containsExactly("SK하이닉스")
        assertThat(master.search(StockQuery("삼성", limit = 1))).hasSize(1)
        assertThat(master.search(StockQuery("100%_"))).isEmpty()

        assertThat(master.find(samsung)?.name).isEqualTo("삼성전자")
        assertThat(master.find(Symbol(Market.KR, "009150"))).isNull()
        assertThat(master.find(Symbol(Market.US, "NVDA"))).isNull()
    }

    @Test
    @DisplayName("목록에 없는 종목은 같은 상장 시장 안에서만 상장폐지로 표시하고, 다시 나타나면 되살림")
    fun delistingIsScopedToBoard() {
        master.saveAll(
            listOf(
                profile(samsung, ListingBoard.KOSPI),
                profile(hynix, ListingBoard.KOSPI),
                profile(kosdaq, ListingBoard.KOSDAQ),
            ),
            at,
        )

        master.markDelistedExcept(ListingBoard.KOSPI, setOf(samsung), at)

        assertThat(delisted()).containsExactly("000660")
        master.saveAll(listOf(profile(hynix, ListingBoard.KOSPI)), at)
        assertThat(delisted()).isEmpty()
    }

    @Test
    @DisplayName("마스터에 있고 상장폐지가 아닌 종목만 상장 중으로 봄")
    fun isListedExcludesDelistedAndUnknown() {
        master.saveAll(
            listOf(profile(samsung, ListingBoard.KOSPI), profile(hynix, ListingBoard.KOSPI)),
            at,
        )
        master.markDelistedExcept(ListingBoard.KOSPI, setOf(samsung), at)

        assertThat(master.isListed(samsung)).isTrue()
        assertThat(master.isListed(hynix)).isFalse()
        assertThat(master.isListed(kosdaq)).isFalse()
    }

    @Test
    @DisplayName("600건도 한 번에 저장됨(한 문장 500행 단위로 나눔)")
    fun savesMoreRowsThanOneStatement() {
        val many =
            (0 until 600).map { profile(Symbol(Market.KR, "%06d".format(it)), ListingBoard.KOSDAQ) }

        master.saveAll(many, at)

        assertThat(write.queryForObject("select count(*) from stock_master", Int::class.java))
            .isEqualTo(600)
    }

    @Test
    @DisplayName("경고 캐시는 모름(null)을 그대로 저장하고 다시 읽음")
    fun flagsCacheRoundTripsUnknown() {
        val flags =
            StockFlags(
                symbol = samsung,
                isInvestmentWarning = true,
                isInvestmentRisk = false,
                isOverheated = false,
                isLiquidationTrading = false,
                isViStatic = true,
                isViDynamic = false,
                hasUnknownWarning = true,
                isTradingHalted = null,
                isAdministrative = null,
                asOf = at,
            )

        flagsCache.save(flags)
        flagsCache.save(flags.copy(isTradingHalted = false, asOf = at.plusSeconds(5)))

        assertThat(flagsCache.find(samsung))
            .isEqualTo(flags.copy(isTradingHalted = false, asOf = at.plusSeconds(5)))
        assertThat(flagsCache.find(hynix)).isNull()
    }

    private fun delisted(): List<String?> =
        write.queryForList(
            "select code from stock_master where delisted = 1 order by code",
            String::class.java,
        )

    private fun profile(symbol: Symbol, board: ListingBoard) =
        FakeStockCatalog.profileOf(symbol, board, KrTradingDetail(false, true, false, false))
            .copy(name = "삼성전자우", status = ListingStatus.ACTIVE)
}
