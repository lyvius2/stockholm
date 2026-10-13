package banghak.stock.core.port

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.market.WatchlistGroup
import banghak.stock.core.domain.market.WatchlistItem

/**
 * 관심종목(`watchlist_group`·`watchlist_item`).
 * `WatchlistChanged` 계열 이벤트의 projection 이며 모든 조회·변경은 사용자 범위 안에서만 동작함.
 * 종목 행은 사용자의 그룹을 거쳐서만 닿음.
 */
interface WatchlistPort {
    /** 정렬 순서대로. */
    fun groups(userId: UserId): List<WatchlistGroup>

    /**
     * 사용자의 모든 그룹에 담긴 종목을 그룹·정렬 순서대로.
     * 이름은 채우지 않음.
     */
    fun items(userId: UserId): List<WatchlistItem>

    /**
     * 같은 그룹 id 는 덮어씀.
     * 다른 사용자의 그룹 id 면 아무것도 하지 않음.
     */
    fun saveGroup(userId: UserId, group: WatchlistGroup)

    /** 그룹과 그 종목을 지움. */
    fun deleteGroup(userId: UserId, groupId: String)

    /** 같은 (그룹, 종목)은 덮어씀. */
    fun saveItem(userId: UserId, item: WatchlistItem)

    fun deleteItem(userId: UserId, groupId: String, symbol: Symbol)

    /**
     * 그룹 안 종목의 정렬 순서를 [symbols] 순서로 바꿈.
     * 목록에 없는 종목은 뒤에 그대로 둠.
     */
    fun reorderItems(userId: UserId, groupId: String, symbols: List<Symbol>)
}
