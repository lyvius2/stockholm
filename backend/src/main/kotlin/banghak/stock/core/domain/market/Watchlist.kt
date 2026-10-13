package banghak.stock.core.domain.market

import banghak.stock.core.domain.error.InvalidValueException
import java.time.Instant

/**
 * 관심종목 그룹(F4).
 * 사용자마다 그룹을 두고 그룹 안에서 종목 순서를 정함.
 */
data class WatchlistGroup(
    val groupId: String,
    val name: String,
    val sortOrder: Int,
    val updatedAt: Instant,
) {
    init {
        validateName(name)
    }

    companion object {
        const val MAX_NAME_LENGTH = 20

        fun validateName(name: String) {
            if (name.isBlank()) throw InvalidValueException("그룹 이름이 비어 있음")
            if (name.length > MAX_NAME_LENGTH)
                throw InvalidValueException("그룹 이름은 ${MAX_NAME_LENGTH}자까지: $name")
        }
    }
}

/**
 * 그룹에 담긴 종목 하나.
 * [name] 은 종목 마스터의 이름이며 마스터에 없으면 null.
 */
data class WatchlistItem(
    val groupId: String,
    val symbol: Symbol,
    val sortOrder: Int,
    val addedAt: Instant,
    val name: String? = null,
)

/**
 * 한 사용자의 관심종목 전체.
 * 그룹과 종목 모두 [WatchlistGroup.sortOrder]·[WatchlistItem.sortOrder] 순임.
 */
data class Watchlist(val groups: List<WatchlistGroup>, val items: List<WatchlistItem>) {
    fun itemsOf(groupId: String): List<WatchlistItem> = items.filter { it.groupId == groupId }

    fun hasGroup(groupId: String): Boolean = groups.any { it.groupId == groupId }

    companion object {
        /**
         * 그룹 수 상한.
         * 서랍의 그룹 탭 한 줄에 들어가는 양.
         */
        const val MAX_GROUPS = 10

        /**
         * 그룹당 종목 상한.
         * 실시간 구독은 화면당 20종목이라 그 너머는 현재가 없이 보임.
         */
        const val MAX_ITEMS_PER_GROUP = 50

        /** 그룹 없이 담으면 이 이름으로 첫 그룹을 만듦. */
        const val DEFAULT_GROUP_NAME = "관심"

        val EMPTY = Watchlist(emptyList(), emptyList())
    }
}
