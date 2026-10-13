package banghak.stock.support.fakes

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.market.WatchlistGroup
import banghak.stock.core.domain.market.WatchlistItem
import banghak.stock.core.port.WatchlistPort

/**
 * 메모리 관심종목.
 * 그룹은 사용자별로, 종목은 그룹 id 로 둠.
 */
class MemoryWatchlist : WatchlistPort {
    val groupRows = mutableMapOf<UserId, MutableList<WatchlistGroup>>()
    val itemRows = mutableMapOf<String, MutableList<WatchlistItem>>()

    override fun groups(userId: UserId): List<WatchlistGroup> =
        groupRows[userId].orEmpty().sortedBy { it.sortOrder }

    override fun items(userId: UserId): List<WatchlistItem> =
        groups(userId).flatMap { group ->
            itemRows[group.groupId].orEmpty().sortedBy { it.sortOrder }
        }

    override fun saveGroup(userId: UserId, group: WatchlistGroup) {
        val owner =
            groupRows.entries.firstOrNull { entry ->
                entry.value.any { it.groupId == group.groupId }
            }
        if (owner != null && owner.key != userId) return
        val rows = groupRows.getOrPut(userId) { mutableListOf() }
        rows.removeIf { it.groupId == group.groupId }
        rows += group
    }

    override fun deleteGroup(userId: UserId, groupId: String) {
        if (groupRows[userId]?.removeIf { it.groupId == groupId } == true) itemRows.remove(groupId)
    }

    override fun saveItem(userId: UserId, item: WatchlistItem) {
        if (groups(userId).none { it.groupId == item.groupId }) return
        val rows = itemRows.getOrPut(item.groupId) { mutableListOf() }
        rows.removeIf { it.symbol == item.symbol }
        rows += item.copy(name = null)
    }

    override fun deleteItem(userId: UserId, groupId: String, symbol: Symbol) {
        if (groups(userId).none { it.groupId == groupId }) return
        itemRows[groupId]?.removeIf { it.symbol == symbol }
    }

    override fun reorderItems(userId: UserId, groupId: String, symbols: List<Symbol>) {
        if (groups(userId).none { it.groupId == groupId }) return
        val position = symbols.withIndex().associate { (index, symbol) -> symbol to index }
        val rows = itemRows[groupId] ?: return
        val reordered =
            rows
                .sortedWith(compareBy({ position[it.symbol] ?: Int.MAX_VALUE }, { it.sortOrder }))
                .mapIndexed { index, item -> item.copy(sortOrder = index) }
        rows.clear()
        rows += reordered
    }
}
