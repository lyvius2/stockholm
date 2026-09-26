package banghak.stock.core.domain.eventlog

import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.ClientOrderId
import banghak.stock.core.port.EventStore
import banghak.stock.support.fakes.MemoryEventStore
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** `EventStore` 구현이 지켜야 할 계약. 메모리 fake로 검증하고, 영속 구현은 같은 테스트를 상속해 돌림. */
open class EventStoreContractTest {
    protected open fun newStore(): EventStore = MemoryEventStore()

    private val store = newStore()
    private val t0 = Instant.parse("2026-09-26T09:00:00Z")
    private val alice = UserId.from(Ulid.of(t0, ByteArray(10) { 1 }))
    private val bob = UserId.from(Ulid.of(t0, ByteArray(10) { 2 }))
    private val mac = DeviceId.from(Ulid.of(t0, ByteArray(10) { 3 }))
    private val laptop = DeviceId.from(Ulid.of(t0, ByteArray(10) { 4 }))
    private val samsung = Symbol(Market.KR, "005930")

    private fun userEvent(key: String = "theme") = UserSettingChanged(key, "dark")

    private fun localEvent() = OrderResultUnknown(ClientOrderId("k1"), "timeout")

    @Test
    @DisplayName("seq는 사용자·디바이스 쌍마다 1부터 단조 증가함")
    fun seqIncreasesPerUserDevicePair() {
        assertThat(store.append(alice, mac, listOf(userEvent(), userEvent()), t0)).isEqualTo(2L)
        assertThat(store.append(alice, mac, listOf(userEvent()), t0)).isEqualTo(3L)
        assertThat(store.append(alice, laptop, listOf(userEvent()), t0)).isEqualTo(1L)
        assertThat(store.replay(alice, mac, afterSeq = 0).map { it.seq }.toList())
            .containsExactly(1L, 2L, 3L)
    }

    @Test
    @DisplayName("같은 디바이스의 두 사용자는 각자 1부터 셈")
    fun usersOnSameDeviceCountSeparately() {
        store.append(alice, mac, listOf(userEvent()), t0)
        assertThat(store.append(bob, mac, listOf(userEvent()), t0)).isEqualTo(1L)
        assertThat(store.replay(bob, mac, afterSeq = 0).map { it.seq }.toList()).containsExactly(1L)
    }

    @Test
    @DisplayName("빈 목록을 덧붙이면 아무것도 쓰지 않고 마지막 seq를 돌려줌")
    fun appendingNothingReturnsLastSeq() {
        store.append(alice, mac, listOf(userEvent()), t0)
        assertThat(store.append(alice, mac, emptyList(), t0)).isEqualTo(1L)
        assertThat(store.replay(alice, mac, afterSeq = 0).count()).isEqualTo(1)
    }

    @Test
    @DisplayName("afterSeq 뒤의 이벤트만 다시 읽음")
    fun replaysOnlyAfterGivenSeq() {
        store.append(alice, mac, listOf(userEvent("a"), userEvent("b"), userEvent("c")), t0)
        val keys =
            store
                .replay(alice, mac, afterSeq = 2)
                .map { (it.payload as UserSettingChanged).key }
                .toList()
        assertThat(keys).containsExactly("c")
    }

    @Test
    @DisplayName("디바이스를 지정하지 않으면 사용자의 모든 디바이스를 발생 시각 순서로 읽음")
    fun replaysAllDevicesInOccurredAtOrder() {
        store.append(alice, laptop, listOf(userEvent("late")), t0.plusSeconds(10))
        store.append(alice, mac, listOf(userEvent("early")), t0)
        val keys =
            store
                .replay(alice, deviceId = null, afterSeq = 0)
                .map { (it.payload as UserSettingChanged).key }
                .toList()
        assertThat(keys).containsExactly("early", "late")
    }

    @Test
    @DisplayName("다른 사용자의 이벤트는 절대 섞이지 않음")
    fun neverMixesUsers() {
        store.append(alice, mac, listOf(userEvent("alice")), t0)
        store.append(bob, mac, listOf(userEvent("bob")), t0)
        assertThat(store.replay(alice, null, 0).map { it.userId }.toSet()).containsExactly(alice)
        assertThat(store.replay(bob, mac, 0).map { it.userId }.toSet()).containsExactly(bob)
    }

    @Test
    @DisplayName("LOCAL 범위 이벤트는 동기화 출력에 없음")
    fun excludesLocalScopeFromSync() {
        store.append(
            alice,
            mac,
            listOf(localEvent(), userEvent(), JournalMemoChanged(samsung, "m1")),
            t0,
        )
        val scopes = store.replayForSync(alice, mac, afterSeq = 0).map { it.syncScope }.toList()
        assertThat(scopes).containsExactly(SyncScope.USER, SyncScope.FAMILY)
        assertThat(store.replay(alice, mac, 0).count()).isEqualTo(3)
    }

    @Test
    @DisplayName("봉투의 type은 이벤트 클래스 이름이고 발생 시각은 append 시각임")
    fun envelopeCarriesTypeNameAndAppendTime() {
        store.append(alice, mac, listOf(userEvent()), t0)
        val envelope = store.replay(alice, mac, 0).single()
        assertThat(envelope.type).isEqualTo("UserSettingChanged")
        assertThat(envelope.occurredAt).isEqualTo(t0)
        assertThat(envelope.deviceId).isEqualTo(mac)
    }
}
