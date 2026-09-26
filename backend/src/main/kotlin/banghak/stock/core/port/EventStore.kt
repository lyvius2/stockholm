package banghak.stock.core.port

import banghak.stock.core.domain.eventlog.DomainEvent
import banghak.stock.core.domain.eventlog.EventEnvelope
import banghak.stock.core.domain.eventlog.SyncScope
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import java.time.Instant

/** 추가 전용 이벤트 로그. 이 저장소가 곧 트랜잭션 아웃박스이며 projection 갱신과 같은 트랜잭션에서 커밋됨. 모든 조회는 `userId` 범위 안에서만 동작함. */
interface EventStore {
    /**
     * 이벤트를 순서대로 덧붙임.
     *
     * @param events 비어 있으면 아무것도 쓰지 않음
     * @param now 발생 시각. 호출자가 `Clock`에서 받아 넘김
     * @return 그 (사용자, 디바이스)의 마지막 seq
     */
    fun append(userId: UserId, deviceId: DeviceId, events: List<DomainEvent>, now: Instant): Long

    /**
     * 이벤트를 다시 읽음.
     *
     * @param deviceId 지정하면 그 디바이스의 seq 순서, 없으면 사용자의 모든 디바이스를 발생 시각 순서로
     * @param afterSeq 이 seq 뒤부터(포함하지 않음). 디바이스를 지정하지 않으면 무시됨
     */
    fun replay(userId: UserId, deviceId: DeviceId?, afterSeq: Long): Sequence<EventEnvelope>

    /** 동기화로 내보낼 이벤트. `LOCAL` 범위는 절대 포함되지 않음. */
    fun replayForSync(userId: UserId, deviceId: DeviceId, afterSeq: Long): Sequence<EventEnvelope> =
        replay(userId, deviceId, afterSeq).filter { it.syncScope != SyncScope.LOCAL }
}
