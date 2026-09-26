package banghak.stock.core.domain.eventlog

import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import java.time.Instant

/**
 * 저장·동기화되는 이벤트 한 건. `seq`는 (사용자, 디바이스) 쌍마다 1부터 단조 증가함.
 *
 * @property type 이벤트 클래스 이름. 직렬화·색인 키
 */
data class EventEnvelope(
    val userId: UserId,
    val deviceId: DeviceId,
    val seq: Long,
    val occurredAt: Instant,
    val type: String,
    val payload: DomainEvent,
) {
    init {
        if (seq < 1) throw InvalidValueException("seq 는 1 이상이어야 함: $seq")
        if (type != typeOf(payload))
            throw InvalidValueException("type '$type' 이 payload 의 종류 '${typeOf(payload)}' 와 다름")
    }

    val syncScope: SyncScope
        get() = SyncScopes.of(payload)

    /** 수정형 이벤트의 충돌 규칙(LWW): `occurredAt`이 늦은 쪽, 같으면 `deviceId` 문자열이 큰 쪽이 이김. */
    fun supersedes(other: EventEnvelope): Boolean =
        occurredAt > other.occurredAt ||
            (occurredAt == other.occurredAt && deviceId.value > other.deviceId.value)

    companion object {
        fun of(
            userId: UserId,
            deviceId: DeviceId,
            seq: Long,
            occurredAt: Instant,
            payload: DomainEvent,
        ): EventEnvelope =
            EventEnvelope(userId, deviceId, seq, occurredAt, typeOf(payload), payload)

        fun typeOf(payload: DomainEvent): String =
            payload::class.simpleName ?: error("이벤트 클래스에 이름이 없음")
    }
}
