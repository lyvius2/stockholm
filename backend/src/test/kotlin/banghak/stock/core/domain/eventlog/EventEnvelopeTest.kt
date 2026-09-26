package banghak.stock.core.domain.eventlog

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.ClientOrderId
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class EventEnvelopeTest {
    private val t0 = Instant.parse("2026-09-26T09:00:00Z")
    private val user = UserId.from(Ulid.of(t0, ByteArray(10)))
    private val deviceA = DeviceId.from(Ulid.of(t0, ByteArray(10) { 1 }))
    private val deviceB = DeviceId.from(Ulid.of(t0, ByteArray(10) { 2 }))
    private val samsung = Symbol(Market.KR, "005930")

    @Test
    @DisplayName("동기화 범위는 이벤트 종류가 정함")
    fun syncScopeIsDeterminedByEventKind() {
        assertThat(SyncScopes.of(OrderResultUnknown(ClientOrderId("k"), "timeout")))
            .isEqualTo(SyncScope.LOCAL)
        assertThat(SyncScopes.of(GuardrailEvaluated(ClientOrderId("k"), true, emptyList())))
            .isEqualTo(SyncScope.LOCAL)
        assertThat(SyncScopes.of(UserSettingChanged("theme", "dark"))).isEqualTo(SyncScope.USER)
        assertThat(SyncScopes.of(WatchlistChanged("g1", samsung, added = true)))
            .isEqualTo(SyncScope.USER)
        assertThat(SyncScopes.of(JournalMemoChanged(samsung, "m1"))).isEqualTo(SyncScope.FAMILY)
    }

    @Test
    @DisplayName("seq 는 1 이상이고 type 은 payload 의 종류와 같아야 함")
    fun validatesSeqAndType() {
        val event = ChartModeChanged(detailed = true)
        assertThatThrownBy { EventEnvelope.of(user, deviceA, 0, t0, event) }
            .isInstanceOf(InvalidValueException::class.java)
        assertThatThrownBy { EventEnvelope(user, deviceA, 1, t0, "UserSettingChanged", event) }
            .isInstanceOf(InvalidValueException::class.java)
        assertThat(EventEnvelope.of(user, deviceA, 1, t0, event).type).isEqualTo("ChartModeChanged")
    }

    @Test
    @DisplayName("충돌은 발생 시각이 늦은 쪽이 이기고, 같으면 디바이스 ID가 큰 쪽이 이김")
    fun laterOccurredAtWinsThenLargerDeviceId() {
        val early = EventEnvelope.of(user, deviceB, 1, t0, ChartModeChanged(detailed = true))
        val late =
            EventEnvelope.of(user, deviceA, 1, t0.plusMillis(1), ChartModeChanged(detailed = false))
        assertThat(late.supersedes(early)).isTrue()
        assertThat(early.supersedes(late)).isFalse()

        val sameTimeA = EventEnvelope.of(user, deviceA, 1, t0, ChartModeChanged(detailed = true))
        val sameTimeB = EventEnvelope.of(user, deviceB, 1, t0, ChartModeChanged(detailed = false))
        assertThat(sameTimeB.supersedes(sameTimeA)).isTrue()
        assertThat(sameTimeA.supersedes(sameTimeB)).isFalse()
        assertThat(sameTimeA.supersedes(sameTimeA)).isFalse()
    }
}
