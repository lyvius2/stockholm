package banghak.stock.support.fakes

import banghak.stock.core.domain.eventlog.DomainEvent
import banghak.stock.core.domain.eventlog.EventEnvelope
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.port.EventStore
import java.time.Instant

/** 테스트용 메모리 이벤트 저장소. 영속 구현과 같은 계약을 지켜야 하며 그 계약은 `EventStoreContractTest`가 정함. */
class MemoryEventStore : EventStore {
    private val envelopes = mutableListOf<EventEnvelope>()

    override fun append(
        userId: UserId,
        deviceId: DeviceId,
        events: List<DomainEvent>,
        now: Instant,
    ): Long {
        var seq = lastSeq(userId, deviceId)
        events.forEach { event ->
            envelopes += EventEnvelope.of(userId, deviceId, ++seq, now, event)
        }
        return seq
    }

    override fun replay(
        userId: UserId,
        deviceId: DeviceId?,
        afterSeq: Long,
    ): Sequence<EventEnvelope> {
        val own = envelopes.filter { it.userId == userId }
        if (deviceId == null)
            return own.sortedWith(compareBy({ it.occurredAt }, { it.deviceId.value }, { it.seq }))
                .asSequence()
        return own.filter { it.deviceId == deviceId && it.seq > afterSeq }
            .sortedBy { it.seq }
            .asSequence()
    }

    private fun lastSeq(userId: UserId, deviceId: DeviceId): Long =
        envelopes.filter { it.userId == userId && it.deviceId == deviceId }.maxOfOrNull { it.seq }
            ?: 0L
}
