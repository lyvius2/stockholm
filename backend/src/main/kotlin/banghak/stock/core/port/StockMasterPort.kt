package banghak.stock.core.port

import banghak.stock.core.domain.market.ListingBoard
import banghak.stock.core.domain.market.StockProfile
import banghak.stock.core.domain.market.StockQuery
import banghak.stock.core.domain.market.StockSummary
import banghak.stock.core.domain.market.Symbol
import java.time.Instant

/**
 * 종목 마스터 저장소.
 * 설치 공용이며 동기화하지 않음.
 * 상장폐지 종목은 과거 lot·토론이 참조하므로 지우지 않고 표시만 함.
 */
interface StockMasterPort {
    fun saveAll(profiles: List<StockProfile>, at: Instant)

    /** [board] 에 속하던 종목 중 [listed] 에 없는 것을 상장폐지로 표시함. */
    fun markDelistedExcept(board: ListingBoard, listed: Set<Symbol>, at: Instant)

    /** 마지막으로 모든 시장을 빠짐없이 동기화한 시각. */
    /** 종목 마스터에 있고 상장폐지가 아닌지. */
    fun isListed(symbol: Symbol): Boolean

    fun lastSyncedAt(): Instant?

    /**
     * 상장 중인 종목의 요약.
     * 없거나 상장폐지면 null.
     */
    fun find(symbol: Symbol): StockSummary?

    /**
     * 종목명·코드·영문명 앞부분 또는 초성으로 상장 중인 종목을 찾음.
     * 앞부분 일치가 먼저 옴.
     */
    fun search(query: StockQuery): List<StockSummary>

    fun recordSync(at: Instant)
}
