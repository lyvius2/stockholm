package banghak.stock.engine.application.market

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.error.NotFoundException
import banghak.stock.core.domain.eventlog.DomainEvent
import banghak.stock.core.domain.eventlog.WatchlistChanged
import banghak.stock.core.domain.eventlog.WatchlistGroupChanged
import banghak.stock.core.domain.eventlog.WatchlistReordered
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.market.Watchlist
import banghak.stock.core.domain.market.WatchlistGroup
import banghak.stock.core.domain.market.WatchlistItem
import banghak.stock.core.port.EventStore
import banghak.stock.core.port.StockMasterPort
import banghak.stock.core.port.WatchlistPort
import banghak.stock.core.usecase.ManageWatchlistUseCase
import banghak.stock.shared.config.RuntimeProfiles
import banghak.stock.shared.crypto.UlidGenerator
import java.time.Clock
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 관심종목(F4).
 * 변경은 projection 과 동기화 이벤트를 같은 트랜잭션으로 남김.
 * 그룹 소유 검사를 모든 변경 앞에 두어 다른 사용자의 그룹 id 로는 아무것도 못 하게 함.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class WatchlistService(
    private val watchlists: WatchlistPort,
    private val stocks: StockMasterPort,
    private val events: EventStore,
    private val ids: UlidGenerator,
    private val clock: Clock,
) : ManageWatchlistUseCase {
    @Transactional(readOnly = true)
    override fun watchlist(userId: UserId): Watchlist {
        val items = watchlists.items(userId).map { it.copy(name = stocks.find(it.symbol)?.name) }
        return Watchlist(watchlists.groups(userId), items)
    }

    @Transactional
    override fun createGroup(userId: UserId, deviceId: DeviceId, name: String): WatchlistGroup {
        val groups = watchlists.groups(userId)
        if (groups.size >= Watchlist.MAX_GROUPS)
            throw InvalidValueException("그룹은 ${Watchlist.MAX_GROUPS}개까지")
        val group =
            WatchlistGroup(
                groupId = ids.next().value,
                name = name.trim(),
                sortOrder = (groups.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                updatedAt = clock.instant(),
            )
        watchlists.saveGroup(userId, group)
        record(
            userId,
            deviceId,
            WatchlistGroupChanged(group.groupId, group.name, group.sortOrder, false),
        )
        return group
    }

    @Transactional
    override fun renameGroup(userId: UserId, deviceId: DeviceId, groupId: String, name: String) {
        val group =
            ownedGroup(userId, groupId).copy(name = name.trim(), updatedAt = clock.instant())
        watchlists.saveGroup(userId, group)
        record(userId, deviceId, WatchlistGroupChanged(groupId, group.name, group.sortOrder, false))
    }

    @Transactional
    override fun removeGroup(userId: UserId, deviceId: DeviceId, groupId: String) {
        val group = ownedGroup(userId, groupId)
        watchlists.deleteGroup(userId, groupId)
        record(userId, deviceId, WatchlistGroupChanged(groupId, group.name, group.sortOrder, true))
    }

    @Transactional
    override fun addItem(
        userId: UserId,
        deviceId: DeviceId,
        groupId: String?,
        symbol: Symbol,
    ): WatchlistItem {
        if (!stocks.isListed(symbol)) throw NotFoundException("상장 중인 종목이 아님: $symbol")
        val group =
            if (groupId != null) ownedGroup(userId, groupId)
            else watchlists.groups(userId).firstOrNull() ?: createDefaultGroup(userId, deviceId)
        val existing = watchlists.items(userId).filter { it.groupId == group.groupId }
        existing
            .firstOrNull { it.symbol == symbol }
            ?.let {
                return it.copy(name = stocks.find(symbol)?.name)
            }
        if (existing.size >= Watchlist.MAX_ITEMS_PER_GROUP)
            throw InvalidValueException("그룹당 종목은 ${Watchlist.MAX_ITEMS_PER_GROUP}개까지")
        val item =
            WatchlistItem(
                groupId = group.groupId,
                symbol = symbol,
                sortOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                addedAt = clock.instant(),
                name = stocks.find(symbol)?.name,
            )
        watchlists.saveItem(userId, item)
        record(userId, deviceId, WatchlistChanged(group.groupId, symbol, true))
        return item
    }

    @Transactional
    override fun removeItem(userId: UserId, deviceId: DeviceId, groupId: String, symbol: Symbol) {
        ownedGroup(userId, groupId)
        watchlists.deleteItem(userId, groupId, symbol)
        record(userId, deviceId, WatchlistChanged(groupId, symbol, false))
    }

    @Transactional
    override fun reorder(
        userId: UserId,
        deviceId: DeviceId,
        groupId: String,
        symbols: List<Symbol>,
    ) {
        ownedGroup(userId, groupId)
        if (symbols.distinct().size != symbols.size) throw InvalidValueException("순서에 같은 종목이 겹침")
        watchlists.reorderItems(userId, groupId, symbols)
        record(userId, deviceId, WatchlistReordered(groupId, symbols))
    }

    private fun createDefaultGroup(userId: UserId, deviceId: DeviceId): WatchlistGroup =
        createGroup(userId, deviceId, Watchlist.DEFAULT_GROUP_NAME)

    private fun ownedGroup(userId: UserId, groupId: String): WatchlistGroup =
        watchlists.groups(userId).firstOrNull { it.groupId == groupId }
            ?: throw NotFoundException("관심종목 그룹이 없음: $groupId")

    private fun record(userId: UserId, deviceId: DeviceId, event: DomainEvent) {
        events.append(userId, deviceId, listOf(event), clock.instant())
    }
}
