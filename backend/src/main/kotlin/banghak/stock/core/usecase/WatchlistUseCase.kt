package banghak.stock.core.usecase

import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.market.Watchlist
import banghak.stock.core.domain.market.WatchlistGroup
import banghak.stock.core.domain.market.WatchlistItem

/**
 * 관심종목(F4): 그룹과 종목의 조회·변경.
 * 모든 변경은 동기화 이벤트(`WatchlistGroupChanged`·`WatchlistChanged`·`WatchlistReordered`)로 남음.
 */
interface ManageWatchlistUseCase {
    /** 종목 이름을 마스터에서 채워 돌려줌. */
    fun watchlist(userId: UserId): Watchlist

    /** @throws banghak.stock.core.domain.error.InvalidValueException 이름이 비었거나 길거나 그룹 수가 상한이면 발생함 */
    fun createGroup(userId: UserId, deviceId: DeviceId, name: String): WatchlistGroup

    /** @throws banghak.stock.core.domain.error.NotFoundException 사용자의 그룹이 아니면 발생함 */
    fun renameGroup(userId: UserId, deviceId: DeviceId, groupId: String, name: String)

    /** @throws banghak.stock.core.domain.error.NotFoundException 사용자의 그룹이 아니면 발생함 */
    fun removeGroup(userId: UserId, deviceId: DeviceId, groupId: String)

    /**
     * 종목을 그룹에 담음.
     * [groupId] 가 null 이면 첫 그룹에 담고, 그룹이 하나도 없으면 기본 그룹을 만듦.
     * 이미 담긴 종목이면 그대로 둠.
     *
     * @throws banghak.stock.core.domain.error.NotFoundException 사용자의 그룹이 아니거나 상장 중인 종목이 아니면 발생함
     * @throws banghak.stock.core.domain.error.InvalidValueException 그룹의 종목 수가 상한이면 발생함
     */
    fun addItem(userId: UserId, deviceId: DeviceId, groupId: String?, symbol: Symbol): WatchlistItem

    fun removeItem(userId: UserId, deviceId: DeviceId, groupId: String, symbol: Symbol)

    /** @throws banghak.stock.core.domain.error.NotFoundException 사용자의 그룹이 아니면 발생함 */
    fun reorder(userId: UserId, deviceId: DeviceId, groupId: String, symbols: List<Symbol>)
}
