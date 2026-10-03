package banghak.stock.core.domain.market

import banghak.stock.core.domain.money.Percent
import banghak.stock.core.domain.trading.TradingFixtures.krw
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class MoverRankerTest {
    private val now = Instant.parse("2026-10-02T01:00:00Z")

    @Test
    @DisplayName("현재가로 등락률을 다시 계산해 급등·급락 각 5개를 가리고, 현재가가 없으면 랭킹 가격을 씀")
    fun reranksByFreshQuotes() {
        // 랭킹에서는 A 가 1위(+10%)지만 현재가로는 B(+20%)가 앞섬.
        // C 는 현재가가 없어 랭킹 가격(-5%) 그대로
        val candidates =
            listOf(
                ranked(1, "A00001", last = "1100", base = "1000"),
                ranked(2, "B00002", last = "1050", base = "1000"),
                ranked(3, "C00003", last = "950", base = "1000"),
            ) +
                (4..12).map {
                    ranked(it, "D${it.toString().padStart(5, '0')}", last = "990", base = "1000")
                }
        val quotes = mapOf(symbol("B00002") to krw("1200"))

        val (gainers, losers) =
            MoverRanker.rank(candidates, quotes, previous = null, flags = emptyMap())

        assertThat(gainers.map { it.symbol.code }).containsExactly("B00002", "A00001")
        assertThat(gainers[0].changeRate).isEqualTo(Percent.ofRatio("0.2"))
        assertThat(losers).hasSize(5)
        assertThat(losers[0].symbol.code).isEqualTo("C00003")
        assertThat(losers[0].changeRate).isEqualTo(Percent.ofRatio("-0.05"))
        // 등락률이 같으면 종목 코드 순
        assertThat(losers.drop(1).map { it.symbol.code })
            .containsExactly("D00004", "D00005", "D00006", "D00007")
        assertThat(losers.map { it.rank }).containsExactly(1, 2, 3, 4, 5)
    }

    @Test
    @DisplayName("오른 종목만 급등, 내린 종목만 급락이고 보합은 어디에도 없어 한 종목이 양쪽에 나오지 않음")
    fun splitsBySignAndDropsFlat() {
        val candidates =
            listOf(
                ranked(1, "A00001", last = "1100", base = "1000"),
                ranked(2, "B00002", last = "1000", base = "1000"),
                ranked(3, "C00003", last = "900", base = "1000"),
            )

        val (gainers, losers) = MoverRanker.rank(candidates, emptyMap(), null, emptyMap())

        assertThat(gainers.map { it.symbol.code }).containsExactly("A00001")
        assertThat(losers.map { it.symbol.code }).containsExactly("C00003")
        // 모두 오른 날은 급락이 비어 있음
        val allUp = listOf(ranked(1, "A00001", "1100", "1000"), ranked(2, "B00002", "1050", "1000"))
        assertThat(MoverRanker.rank(allUp, emptyMap(), null, emptyMap()).second).isEmpty()
    }

    @Test
    @DisplayName("기준가가 0인 종목은 빼고, 같은 종목이 겹쳐 오면 한 번만 셈")
    fun dropsZeroBaseAndDuplicates() {
        val candidates =
            listOf(
                ranked(1, "A00001", last = "1100", base = "0"),
                ranked(2, "B00002", last = "1100", base = "1000"),
                ranked(3, "B00002", last = "1100", base = "1000"),
            )

        val (gainers, _) = MoverRanker.rank(candidates, emptyMap(), null, emptyMap())

        assertThat(gainers.map { it.symbol.code }).containsExactly("B00002")
    }

    @Test
    @DisplayName("순위 변동은 직전 판 같은 쪽과 비교해 NEW·↑n·↓n·같음을 매김")
    fun marksRankChanges() {
        val previous =
            MoverBoard(
                Market.KR,
                gainers =
                    listOf(
                        mover(1, "A00001"),
                        mover(2, "B00002"),
                        mover(3, "C00003"),
                    ),
                losers = emptyList(),
                session = MarketSession.REGULAR,
                asOf = now,
                isDelayed = false,
            )
        val candidates =
            listOf(
                ranked(1, "C00003", last = "1300", base = "1000"),
                ranked(2, "A00001", last = "1200", base = "1000"),
                ranked(3, "E00005", last = "1100", base = "1000"),
                ranked(4, "B00002", last = "1050", base = "1000"),
            )

        val (gainers, _) = MoverRanker.rank(candidates, emptyMap(), previous, emptyMap())

        assertThat(gainers.map { it.symbol.code to it.rankChange })
            .containsExactly(
                "C00003" to RankChange.Up(2),
                "A00001" to RankChange.Down(1),
                "E00005" to RankChange.New,
                "B00002" to RankChange.Down(2),
            )
    }

    @Test
    @DisplayName("종목 경고를 받은 종목에는 플래그가 붙고 못 받은 종목은 비어 있음")
    fun attachesFlags() {
        val flagged = symbol("A00001")
        val flags =
            StockFlags(flagged, true, false, false, false, false, false, false, null, null, now)

        val (gainers, _) =
            MoverRanker.rank(
                listOf(ranked(1, "A00001", "1100", "1000"), ranked(2, "B00002", "1050", "1000")),
                emptyMap(),
                null,
                mapOf(flagged to flags),
            )

        assertThat(gainers[0].flags?.isInvestmentWarning).isTrue()
        assertThat(gainers[1].flags).isNull()
    }

    private fun symbol(code: String) = Symbol(Market.KR, code)

    private fun ranked(rank: Int, code: String, last: String, base: String) =
        RankedStock(
            rank,
            symbol(code),
            krw(last),
            krw(base),
            changeRate = null,
            tradingVolume = BigDecimal("1000"),
            tradingAmount = krw("1000000"),
        )

    private fun mover(rank: Int, code: String) =
        Mover(rank, symbol(code), krw("1000"), Percent.ZERO, BigDecimal.ONE, RankChange.New, null)
}
