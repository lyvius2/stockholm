package banghak.stock.engine.adapter.`in`.web

import banghak.stock.core.domain.error.NotFoundException
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.market.Watchlist
import banghak.stock.core.domain.market.WatchlistGroup
import banghak.stock.core.domain.market.WatchlistItem
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.usecase.ManageWatchlistUseCase
import banghak.stock.engine.adapter.`in`.web.watchlist.WatchlistController
import banghak.stock.support.web.ApiTestSupport
import banghak.stock.support.web.ApiTestSupport.assertConforms
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 관심종목 API 가 세션 사용자·디바이스로 usecase 를 부르고 스키마에 맞는 응답을 줌. */
class WatchlistApiTest {
    private val calls = mutableListOf<String>()
    private val group = WatchlistGroup("g1", "반도체", 0, TradingFixtures.now)
    private val item = WatchlistItem("g1", TradingFixtures.samsung, 0, TradingFixtures.now, "삼성전자")
    private val usecase =
        object : ManageWatchlistUseCase {
            override fun watchlist(userId: UserId) = Watchlist(listOf(group), listOf(item))

            override fun createGroup(
                userId: UserId,
                deviceId: DeviceId,
                name: String,
            ): WatchlistGroup {
                calls += "create:$name:${userId.value}:${deviceId.value}"
                return group.copy(name = name)
            }

            override fun renameGroup(
                userId: UserId,
                deviceId: DeviceId,
                groupId: String,
                name: String,
            ) {
                calls += "rename:$groupId:$name"
            }

            override fun removeGroup(userId: UserId, deviceId: DeviceId, groupId: String) {
                if (groupId == "missing") throw NotFoundException("없음")
                calls += "remove:$groupId"
            }

            override fun addItem(
                userId: UserId,
                deviceId: DeviceId,
                groupId: String?,
                symbol: Symbol,
            ): WatchlistItem {
                calls += "add:$groupId:${symbol.code}"
                return item.copy(symbol = symbol, name = null)
            }

            override fun removeItem(
                userId: UserId,
                deviceId: DeviceId,
                groupId: String,
                symbol: Symbol,
            ) {
                calls += "removeItem:$groupId:${symbol.market}:${symbol.code}"
            }

            override fun reorder(
                userId: UserId,
                deviceId: DeviceId,
                groupId: String,
                symbols: List<Symbol>,
            ) {
                calls += "reorder:$groupId:${symbols.joinToString(",") { it.code }}"
            }
        }
    private val mvc = ApiTestSupport.mockMvc(WatchlistController(usecase))

    @Test
    @DisplayName("관심종목 전체는 그룹과 종목(이름 포함)을 돌려줌")
    fun listsWatchlist() {
        val body =
            mvc.perform(get("/watchlist"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.groups[0].name").value("반도체"))
                .andExpect(jsonPath("$.items[0].name").value("삼성전자"))
                .andReturn()
                .response
                .contentAsString
        assertConforms(body, "api-watchlist")
    }

    @Test
    @DisplayName("그룹 만들기·이름 바꾸기·지우기와 종목 담기·빼기·순서 바꾸기가 세션 사용자로 전달됨")
    fun forwardsChanges() {
        val created =
            mvc.perform(
                    post("/watchlist/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"배당"}""")
                )
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString
        assertConforms(created, "api-watchlist-group")

        mvc.perform(
                put("/watchlist/groups/g1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"반도체2"}""")
            )
            .andExpect(status().isOk)
        mvc.perform(delete("/watchlist/groups/g1")).andExpect(status().isOk)
        val added =
            mvc.perform(
                    post("/watchlist/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"symbol":{"market":"US","code":"NVDA"}}""")
                )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.name").doesNotExist())
                .andReturn()
                .response
                .contentAsString
        assertConforms(added, "api-watchlist-item")
        mvc.perform(delete("/watchlist/groups/g1/items/KR/005930")).andExpect(status().isOk)
        mvc.perform(
                put("/watchlist/groups/g1/order")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"symbols":[{"market":"US","code":"NVDA"},{"market":"KR","code":"005930"}]}"""
                    )
            )
            .andExpect(status().isOk)

        assertThat(calls)
            .containsExactly(
                "create:배당:${TradingFixtures.user.value}:${TradingFixtures.device.value}",
                "rename:g1:반도체2",
                "remove:g1",
                "add:null:NVDA",
                "removeItem:g1:KR:005930",
                "reorder:g1:NVDA,005930",
            )
    }

    @Test
    @DisplayName("없는 그룹은 404, 모르는 시장은 400")
    fun mapsErrors() {
        mvc.perform(delete("/watchlist/groups/missing"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NotFoundException"))
        mvc.perform(
                post("/watchlist/items")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"symbol":{"market":"JP","code":"7203"}}""")
            )
            .andExpect(status().isBadRequest)
    }
}
