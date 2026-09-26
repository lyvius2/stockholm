package banghak.stock.engine.adapter.out.persistence.repository

import banghak.stock.engine.adapter.out.persistence.entity.EventLogEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface EventLogRepository : JpaRepository<EventLogEntity, Long> {
    @Query(
        "select coalesce(max(e.seq), 0) from EventLogEntity e where e.userId = :userId and e.deviceId = :deviceId"
    )
    fun lastSeq(@Param("userId") userId: String, @Param("deviceId") deviceId: String): Long

    @Query(
        "select e from EventLogEntity e where e.userId = :userId and e.deviceId = :deviceId and e.seq > :afterSeq order by e.seq"
    )
    fun replayDevice(
        @Param("userId") userId: String,
        @Param("deviceId") deviceId: String,
        @Param("afterSeq") afterSeq: Long,
    ): List<EventLogEntity>

    @Query(
        "select e from EventLogEntity e where e.userId = :userId order by e.occurredAt, e.deviceId, e.seq"
    )
    fun replayAll(@Param("userId") userId: String): List<EventLogEntity>
}
