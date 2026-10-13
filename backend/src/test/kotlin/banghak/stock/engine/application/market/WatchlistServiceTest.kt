package banghak.stock.engine.application.market

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.error.NotFoundException
import banghak.stock.core.domain.eventlog.WatchlistChanged
import banghak.stock.core.domain.eventlog.WatchlistGroupChanged
import banghak.stock.core.domain.eventlog.WatchlistReordered
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.ListingBoard
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.market.Watchlist
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.shared.crypto.UlidGenerator
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.FakeStockCatalog
import banghak.stock.support.fakes.MemoryEventStore
import banghak.stock.support.fakes.MemoryStockMaster
import banghak.stock.support.fakes.MemoryWatchlist
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class WatchlistServiceTest {
    private val clock = MutableClock(TradingFixtures.now)
    private val store = MemoryWatchlist()
    private val master = MemoryStockMaster()
    private val events = MemoryEventStore()
    private val service = WatchlistService(store, master, events, UlidGenerator(clock), clock)
    private val user = TradingFixtures.user
    private val device = TradingFixtures.device
    private val other = UserId.from(Ulid.of(TradingFixtures.now, ByteArray(10) { 7 }))
    private val samsung = TradingFixtures.samsung
    private val nvidia = TradingFixtures.nvidia
    private val hynix = Symbol(Market.KR, "000660")

    init {
        listOf(samsung, nvidia, hynix).forEach { list(it) }
    }

    private fun list(symbol: Symbol) {
        master.listed += symbol
        master.saved[symbol] =
            FakeStockCatalog.profileOf(
                symbol,
                if (symbol.market == Market.KR) ListingBoard.KOSPI else ListingBoard.NASDAQ,
            )
    }

    @Test
    @DisplayName("그룹 없이 담으면 기본 그룹을 만들고, 이름은 마스터에서 채우며, 두 이벤트가 순서대로 남음")
    fun addsToDefaultGroup() {
        val item = service.addItem(user, device, null, samsung)

        val watchlist = service.watchlist(user)
        assertThat(watchlist.groups).hasSize(1)
        assertThat(watchlist.groups.single().name).isEqualTo(Watchlist.DEFAULT_GROUP_NAME)
        assertThat(item.name).isEqualTo("종목005930")
        assertThat(watchlist.items.single().name).isEqualTo("종목005930")
        assertThat(events.replay(user, null, 0).map { it.payload }.toList())
            .containsExactly(
                WatchlistGroupChanged(item.groupId, Watchlist.DEFAULT_GROUP_NAME, 0, false),
                WatchlistChanged(item.groupId, samsung, true),
            )
    }

    @Test
    @DisplayName("이미 담긴 종목은 그대로 두고 이벤트를 더 남기지 않음")
    fun addIsIdempotent() {
        val first = service.addItem(user, device, null, samsung)
        clock.advance(java.time.Duration.ofMinutes(1))
        val again = service.addItem(user, device, first.groupId, samsung)

        assertThat(again).isEqualTo(first)
        assertThat(service.watchlist(user).items).hasSize(1)
        assertThat(events.replay(user, null, 0).count()).isEqualTo(2)
    }

    @Test
    @DisplayName("상장 중이 아닌 종목은 담지 못하고, 그룹당 상한을 넘으면 거부함")
    fun rejectsUnlistedAndOverCap() {
        assertThatThrownBy { service.addItem(user, device, null, Symbol(Market.KR, "999999")) }
            .isInstanceOf(NotFoundException::class.java)

        val group = service.createGroup(user, device, "많이")
        repeat(Watchlist.MAX_ITEMS_PER_GROUP) { index ->
            val symbol = Symbol(Market.KR, index.toString().padStart(6, '0'))
            list(symbol)
            service.addItem(user, device, group.groupId, symbol)
        }
        assertThatThrownBy { service.addItem(user, device, group.groupId, samsung) }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("순서 바꾸기는 목록의 종목을 앞에 두고 빠진 종목은 뒤에 두며, 겹친 종목은 거부함")
    fun reorders() {
        val group = service.createGroup(user, device, "반도체")
        listOf(samsung, hynix, nvidia).forEach { service.addItem(user, device, group.groupId, it) }

        service.reorder(user, device, group.groupId, listOf(nvidia, samsung))

        assertThat(service.watchlist(user).itemsOf(group.groupId).map { it.symbol })
            .containsExactly(nvidia, samsung, hynix)
        assertThat(events.replay(user, null, 0).map { it.payload }.toList())
            .contains(WatchlistReordered(group.groupId, listOf(nvidia, samsung)))
        assertThatThrownBy {
                service.reorder(user, device, group.groupId, listOf(samsung, samsung))
            }
            .isInstanceOf(InvalidValueException::class.java)
    }

    @Test
    @DisplayName("다른 사용자의 그룹 id 로는 이름 변경·삭제·담기·순서 바꾸기 모두 못 함")
    fun guardsOwnership() {
        val group = service.createGroup(user, device, "내 그룹")
        service.addItem(user, device, group.groupId, samsung)

        assertThatThrownBy { service.renameGroup(other, device, group.groupId, "남의 것") }
            .isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { service.removeGroup(other, device, group.groupId) }
            .isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { service.addItem(other, device, group.groupId, nvidia) }
            .isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { service.reorder(other, device, group.groupId, listOf(samsung)) }
            .isInstanceOf(NotFoundException::class.java)
        assertThat(service.watchlist(other)).isEqualTo(Watchlist.EMPTY)
        assertThat(service.watchlist(user).items).hasSize(1)
    }

    @Test
    @DisplayName("그룹 이름은 비거나 길면 거부하고, 그룹 수는 상한까지만")
    fun validatesGroups() {
        assertThatThrownBy { service.createGroup(user, device, "  ") }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy { service.createGroup(user, device, "가".repeat(21)) }
            .isInstanceOf(InvalidValueException::class.java)
        repeat(Watchlist.MAX_GROUPS) { service.createGroup(user, device, "그룹$it") }
        assertThatThrownBy { service.createGroup(user, device, "하나 더") }
            .isInstanceOf(InvalidValueException::class.java)

        val first = service.watchlist(user).groups.first()
        service.renameGroup(user, device, first.groupId, " 바뀜 ")
        service.removeGroup(user, device, first.groupId)
        assertThat(service.watchlist(user).groups).hasSize(Watchlist.MAX_GROUPS - 1)
        assertThat(events.replay(user, null, 0).map { it.payload }.toList())
            .contains(
                WatchlistGroupChanged(first.groupId, "바뀜", 0, false),
                WatchlistGroupChanged(first.groupId, "바뀜", 0, true),
            )
    }
}
