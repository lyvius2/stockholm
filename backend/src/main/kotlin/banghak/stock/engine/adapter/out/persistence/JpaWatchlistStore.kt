package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.market.WatchlistGroup
import banghak.stock.core.domain.market.WatchlistItem
import banghak.stock.core.port.WatchlistPort
import banghak.stock.engine.adapter.out.persistence.entity.WatchlistGroupEntity
import banghak.stock.engine.adapter.out.persistence.entity.WatchlistItemEntity
import banghak.stock.engine.adapter.out.persistence.entity.WatchlistItemKey
import banghak.stock.engine.adapter.out.persistence.repository.WatchlistGroupRepository
import banghak.stock.engine.adapter.out.persistence.repository.WatchlistItemRepository
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@Profile(RuntimeProfiles.ENGINE)
class JpaWatchlistStore(
    private val groups: WatchlistGroupRepository,
    private val items: WatchlistItemRepository,
) : WatchlistPort {
    @Transactional(readOnly = true)
    override fun groups(userId: UserId): List<WatchlistGroup> =
        groups.findByUserId(userId.value).map { groupOf(it) }

    @Transactional(readOnly = true)
    override fun items(userId: UserId): List<WatchlistItem> {
        val owned = groups.findByUserId(userId.value)
        if (owned.isEmpty()) return emptyList()
        val order = owned.withIndex().associate { (index, group) -> group.groupId to index }
        return items
            .findByGroupIds(owned.map { it.groupId })
            .sortedWith(compareBy({ order[it.id.groupId] ?: Int.MAX_VALUE }, { it.sortOrder }))
            .map { itemOf(it) }
    }

    @Transactional
    override fun saveGroup(userId: UserId, group: WatchlistGroup) {
        val row = groups.findById(group.groupId).orElse(null)
        if (row == null) {
            groups.save(
                WatchlistGroupEntity(
                    group.groupId,
                    userId.value,
                    group.name,
                    group.sortOrder,
                    group.updatedAt,
                )
            )
            return
        }
        // 다른 사용자의 그룹 id 면 손대지 않음
        if (row.userId != userId.value) return
        row.name = group.name
        row.sortOrder = group.sortOrder
        row.updatedAt = group.updatedAt
    }

    @Transactional
    override fun deleteGroup(userId: UserId, groupId: String) {
        val row = groups.findOwned(userId.value, groupId) ?: return
        items.deleteByGroupId(groupId)
        groups.delete(row)
    }

    @Transactional
    override fun saveItem(userId: UserId, item: WatchlistItem) {
        groups.findOwned(userId.value, item.groupId) ?: return
        val key = WatchlistItemKey(item.groupId, item.symbol.market.name, item.symbol.code)
        val row = items.findById(key).orElse(null)
        if (row == null) {
            items.save(WatchlistItemEntity(key, item.sortOrder, item.addedAt))
            return
        }
        row.sortOrder = item.sortOrder
    }

    @Transactional
    override fun deleteItem(userId: UserId, groupId: String, symbol: Symbol) {
        groups.findOwned(userId.value, groupId) ?: return
        items.deleteById(WatchlistItemKey(groupId, symbol.market.name, symbol.code))
    }

    @Transactional
    override fun reorderItems(userId: UserId, groupId: String, symbols: List<Symbol>) {
        groups.findOwned(userId.value, groupId) ?: return
        val rows = items.findByGroupIds(listOf(groupId))
        val position = symbols.withIndex().associate { (index, symbol) -> symbol to index }
        // 목록에 든 종목이 앞에, 빠진 종목은 원래 순서로 뒤에
        rows
            .sortedWith(compareBy({ position[symbolOf(it)] ?: Int.MAX_VALUE }, { it.sortOrder }))
            .forEachIndexed { index, row -> row.sortOrder = index }
    }

    private fun groupOf(row: WatchlistGroupEntity) =
        WatchlistGroup(row.groupId, row.name, row.sortOrder, row.updatedAt)

    private fun itemOf(row: WatchlistItemEntity) =
        WatchlistItem(row.id.groupId, symbolOf(row), row.sortOrder, row.addedAt)

    private fun symbolOf(row: WatchlistItemEntity) =
        Symbol(Market.valueOf(row.id.market), row.id.code)
}
