package banghak.stock.engine.adapter.`in`.web.watchlist

import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.market.Watchlist
import banghak.stock.core.domain.market.WatchlistGroup
import banghak.stock.core.domain.market.WatchlistItem
import banghak.stock.core.usecase.ManageWatchlistUseCase
import banghak.stock.engine.adapter.`in`.web.common.SymbolDto
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Instant
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class WatchlistGroupDto(
    val groupId: String,
    val name: String,
    val sortOrder: Int,
    val updatedAt: Instant,
) {
    companion object {
        fun of(group: WatchlistGroup) =
            WatchlistGroupDto(group.groupId, group.name, group.sortOrder, group.updatedAt)
    }
}

data class WatchlistItemDto(
    val groupId: String,
    val symbol: SymbolDto,
    val name: String?,
    val sortOrder: Int,
    val addedAt: Instant,
) {
    companion object {
        fun of(item: WatchlistItem) =
            WatchlistItemDto(
                item.groupId,
                SymbolDto.of(item.symbol),
                item.name,
                item.sortOrder,
                item.addedAt,
            )
    }
}

data class WatchlistResponse(
    val groups: List<WatchlistGroupDto>,
    val items: List<WatchlistItemDto>,
) {
    companion object {
        fun of(watchlist: Watchlist) =
            WatchlistResponse(
                watchlist.groups.map(WatchlistGroupDto::of),
                watchlist.items.map(WatchlistItemDto::of),
            )
    }
}

data class WatchlistGroupRequest(val name: String = "")

/** [groupId] 가 없으면 첫 그룹(없으면 기본 그룹을 만듦). */
data class WatchlistItemRequest(val groupId: String? = null, val symbol: SymbolDto = SymbolDto())

data class WatchlistOrderRequest(val symbols: List<SymbolDto> = emptyList())

/**
 * 관심종목(F4).
 * 세션 사용자의 것만 읽고 쓰며 변경은 디바이스 간 동기화됨.
 */
@RestController
@RequestMapping("/watchlist")
@Profile(RuntimeProfiles.ENGINE)
class WatchlistController(private val watchlists: ManageWatchlistUseCase) {
    @GetMapping
    fun watchlist(principal: Principal): WatchlistResponse =
        WatchlistResponse.of(watchlists.watchlist(principal.userId))

    @PostMapping("/groups")
    fun createGroup(principal: Principal, @RequestBody request: WatchlistGroupRequest) =
        WatchlistGroupDto.of(
            watchlists.createGroup(principal.userId, principal.session.deviceId, request.name)
        )

    @PutMapping("/groups/{groupId}")
    fun renameGroup(
        principal: Principal,
        @PathVariable groupId: String,
        @RequestBody request: WatchlistGroupRequest,
    ) {
        watchlists.renameGroup(principal.userId, principal.session.deviceId, groupId, request.name)
    }

    @DeleteMapping("/groups/{groupId}")
    fun removeGroup(principal: Principal, @PathVariable groupId: String) {
        watchlists.removeGroup(principal.userId, principal.session.deviceId, groupId)
    }

    @PostMapping("/items")
    fun addItem(principal: Principal, @RequestBody request: WatchlistItemRequest) =
        WatchlistItemDto.of(
            watchlists.addItem(
                principal.userId,
                principal.session.deviceId,
                request.groupId,
                request.symbol.toSymbol(),
            )
        )

    @DeleteMapping("/groups/{groupId}/items/{market}/{code}")
    fun removeItem(
        principal: Principal,
        @PathVariable groupId: String,
        @PathVariable market: String,
        @PathVariable code: String,
    ) {
        watchlists.removeItem(
            principal.userId,
            principal.session.deviceId,
            groupId,
            SymbolDto(market, code).toSymbol(),
        )
    }

    @PutMapping("/groups/{groupId}/order")
    fun reorder(
        principal: Principal,
        @PathVariable groupId: String,
        @RequestBody request: WatchlistOrderRequest,
    ) {
        watchlists.reorder(
            principal.userId,
            principal.session.deviceId,
            groupId,
            request.symbols.map { it.toSymbol() },
        )
    }
}
