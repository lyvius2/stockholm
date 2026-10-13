package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.WatchlistGroup
import banghak.stock.core.domain.market.WatchlistItem
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.port.WatchlistPort
import banghak.stock.support.EngineDatabaseTest
import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/** 관심종목 표가 사용자의 그룹을 거쳐서만 읽히고, 다른 사용자는 그 그룹 id 로 아무것도 못 함. */
class WatchlistPersistenceTest : EngineDatabaseTest() {
    @Autowired private lateinit var store: WatchlistPort
    @Autowired private lateinit var engineWriteDataSource: HikariDataSource

    private val write by lazy { JdbcTemplate(engineWriteDataSource) }
    private val user = TradingFixtures.user
    private val other = UserId.from(Ulid.of(TradingFixtures.now, ByteArray(10) { 9 }))
    private val at = Instant.parse("2026-10-13T00:00:00Z")

    @BeforeEach
    fun clearTables() {
        write.update("delete from watchlist_item")
        write.update("delete from watchlist_group")
        write.update("delete from app_user")
        listOf(user, other).forEach {
            write.update(
                "insert into app_user (user_id, role, display_name, password_hash, status, toss_key_decision, created_at, updated_at) values (?, 'ADMIN', 'w', 'h', 'ACTIVE', 'NONE', '2026-10-13T00:00:00.000Z', '2026-10-13T00:00:00.000Z')",
                it.value,
            )
        }
    }

    @Test
    @DisplayName("그룹·종목을 정렬 순서로 읽고, 순서 바꾸기는 빠진 종목을 뒤에 두며, 그룹을 지우면 종목도 지워짐")
    fun storesGroupsAndItems() {
        store.saveGroup(user, WatchlistGroup("g2", "둘째", 1, at))
        store.saveGroup(user, WatchlistGroup("g1", "첫째", 0, at))
        store.saveItem(user, WatchlistItem("g1", TradingFixtures.samsung, 1, at))
        store.saveItem(user, WatchlistItem("g1", TradingFixtures.nvidia, 0, at))
        store.saveItem(user, WatchlistItem("g2", TradingFixtures.samsung, 0, at))

        assertThat(store.groups(user).map { it.name }).containsExactly("첫째", "둘째")
        assertThat(store.items(user).map { "${it.groupId}:${it.symbol.code}" })
            .containsExactly("g1:NVDA", "g1:005930", "g2:005930")

        store.reorderItems(user, "g1", listOf(TradingFixtures.samsung))
        assertThat(store.items(user).filter { it.groupId == "g1" }.map { it.symbol.code })
            .containsExactly("005930", "NVDA")

        store.deleteItem(user, "g1", TradingFixtures.nvidia)
        store.deleteGroup(user, "g2")
        assertThat(store.items(user).map { "${it.groupId}:${it.symbol.code}" })
            .containsExactly("g1:005930")
        assertThat(store.groups(user).map { it.groupId }).containsExactly("g1")
    }

    @Test
    @DisplayName("다른 사용자는 그 그룹을 보지도 못하고, 같은 id 로 덮어쓰거나 종목을 넣거나 지우지도 못함")
    fun isolatesUsers() {
        store.saveGroup(user, WatchlistGroup("g1", "내 것", 0, at))
        store.saveItem(user, WatchlistItem("g1", TradingFixtures.samsung, 0, at))

        store.saveGroup(other, WatchlistGroup("g1", "가로챔", 0, at))
        store.saveItem(other, WatchlistItem("g1", TradingFixtures.nvidia, 1, at))
        store.deleteItem(other, "g1", TradingFixtures.samsung)
        store.deleteGroup(other, "g1")

        assertThat(store.groups(other)).isEmpty()
        assertThat(store.items(other)).isEmpty()
        assertThat(store.groups(user).single().name).isEqualTo("내 것")
        assertThat(store.items(user).map { it.symbol }).containsExactly(TradingFixtures.samsung)
    }
}
