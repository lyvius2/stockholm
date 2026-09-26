package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.eventlog.DomainEvent
import banghak.stock.core.domain.eventlog.EventEnvelope
import banghak.stock.core.domain.eventlog.SyncScopes
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.port.EventStore
import banghak.stock.engine.adapter.out.persistence.entity.EventLogEntity
import banghak.stock.engine.adapter.out.persistence.repository.EventLogRepository
import banghak.stock.shared.config.RuntimeProfiles
import banghak.stock.shared.protocol.EventPayloadCodec
import java.time.Instant
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * SQLite `event_log` 위의 `EventStore`. append 는 쓰기 트랜잭션 하나 안에서 seq 를 이어 붙임. SQLite 쓰기 연결이 하나뿐이라 seq
 * 경합이 없음.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class JpaEventStore(
    private val repository: EventLogRepository,
    private val codec: EventPayloadCodec,
) : EventStore {
    @Transactional
    override fun append(
        userId: UserId,
        deviceId: DeviceId,
        events: List<DomainEvent>,
        now: Instant,
    ): Long {
        var seq = repository.lastSeq(userId.value, deviceId.value)
        val rows = events.map { event ->
            toEntity(EventEnvelope.of(userId, deviceId, ++seq, now, event))
        }
        repository.saveAll(rows)
        return seq
    }

    @Transactional(readOnly = true)
    override fun replay(
        userId: UserId,
        deviceId: DeviceId?,
        afterSeq: Long,
    ): Sequence<EventEnvelope> {
        val rows =
            if (deviceId == null) repository.replayAll(userId.value)
            else repository.replayDevice(userId.value, deviceId.value, afterSeq)
        return rows.map(::toEnvelope).asSequence()
    }

    private fun toEntity(envelope: EventEnvelope): EventLogEntity =
        EventLogEntity(
            userId = envelope.userId.value,
            deviceId = envelope.deviceId.value,
            seq = envelope.seq,
            occurredAt = envelope.occurredAt,
            type = envelope.type,
            payloadVersion = EventPayloadCodec.CURRENT_VERSION,
            payloadJson = codec.encode(envelope.payload),
            syncScope = SyncScopes.of(envelope.payload),
            receivedAt = null,
        )

    private fun toEnvelope(row: EventLogEntity): EventEnvelope =
        EventEnvelope.of(
            UserId(row.userId),
            DeviceId(row.deviceId),
            row.seq,
            row.occurredAt,
            codec.decode(row.type, row.payloadJson),
        )
}
