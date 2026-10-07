package banghak.stock.engine.adapter.out.persistence.repository

import banghak.stock.engine.adapter.out.persistence.entity.BrokerOrderEntity
import banghak.stock.engine.adapter.out.persistence.entity.ConditionalSubmissionEntity
import banghak.stock.engine.adapter.out.persistence.entity.FillQueueEntity
import banghak.stock.engine.adapter.out.persistence.entity.LotDisposalEntity
import banghak.stock.engine.adapter.out.persistence.entity.LotEntity
import banghak.stock.engine.adapter.out.persistence.entity.LotLedgerEntity
import banghak.stock.engine.adapter.out.persistence.entity.OrderSubmissionEntity
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface BrokerOrderRepository : JpaRepository<BrokerOrderEntity, String> {
    @Query(
        "select o from BrokerOrderEntity o where o.userId = :userId and o.market = :market " +
            "and o.orderedAt >= :since and o.triggerType <> :externalTrigger order by o.orderedAt"
    )
    fun findPlacedSince(
        @Param("userId") userId: String,
        @Param("market") market: String,
        @Param("since") since: Instant,
        @Param("externalTrigger") externalTrigger: String,
    ): List<BrokerOrderEntity>

    @Query(
        "select o from BrokerOrderEntity o where o.userId = :userId and o.brokerOrderId = :brokerOrderId"
    )
    fun findOwned(
        @Param("userId") userId: String,
        @Param("brokerOrderId") brokerOrderId: String,
    ): BrokerOrderEntity?

    @Query(
        "select o.brokerOrderId from BrokerOrderEntity o where o.userId = :userId and o.status in :statuses"
    )
    fun findIdsByUserIdAndStatuses(
        @Param("userId") userId: String,
        @Param("statuses") statuses: Collection<String>,
    ): List<String>

    @Query(
        "select o from BrokerOrderEntity o where o.userId = :userId and o.status in :statuses " +
            "order by o.orderedAt desc"
    )
    fun findByUserIdAndStatuses(
        @Param("userId") userId: String,
        @Param("statuses") statuses: Collection<String>,
    ): List<BrokerOrderEntity>

    // 오늘 분류는 증권사의 체결·취소 시각으로 하고, 없는 주문(거부·정정됨)만 수집 시각으로 함
    @Query(
        "select o from BrokerOrderEntity o where o.userId = :userId and o.status in :statuses " +
            "and coalesce(o.canceledAt, o.filledAt, o.updatedAt) >= :since " +
            "order by coalesce(o.canceledAt, o.filledAt, o.updatedAt) desc"
    )
    fun findByUserIdAndStatusesSince(
        @Param("userId") userId: String,
        @Param("statuses") statuses: Collection<String>,
        @Param("since") since: Instant,
    ): List<BrokerOrderEntity>
}

interface LotRepository : JpaRepository<LotEntity, String> {
    @Query(
        "select l from LotEntity l where l.userId = :userId and l.market = :market " +
            "and l.closedAt is null order by l.boughtAt"
    )
    fun findOpenLots(
        @Param("userId") userId: String,
        @Param("market") market: String,
    ): List<LotEntity>

    @Query("select l from LotEntity l where l.userId = :userId and l.lotId = :lotId")
    fun findOwned(@Param("userId") userId: String, @Param("lotId") lotId: String): LotEntity?
}

interface LotDisposalRepository : JpaRepository<LotDisposalEntity, String>

interface FillQueueRepository : JpaRepository<FillQueueEntity, String> {
    @Query(
        "select f from FillQueueEntity f where f.userId = :userId and f.state in :states " +
            "order by f.executedAt, f.fillId"
    )
    fun findByUserIdAndStates(
        @Param("userId") userId: String,
        @Param("states") states: Collection<String>,
    ): List<FillQueueEntity>

    @Query("select f from FillQueueEntity f where f.userId = :userId and f.fillId = :fillId")
    fun findOwned(
        @Param("userId") userId: String,
        @Param("fillId") fillId: String,
    ): FillQueueEntity?
}

interface LotLedgerRepository : JpaRepository<LotLedgerEntity, String>

interface OrderSubmissionRepository : JpaRepository<OrderSubmissionEntity, String> {
    @Query(
        "select s from OrderSubmissionEntity s where s.userId = :userId and s.clientOrderId = :clientOrderId"
    )
    fun findOwned(
        @Param("userId") userId: String,
        @Param("clientOrderId") clientOrderId: String,
    ): OrderSubmissionEntity?

    @Query(
        "select s from OrderSubmissionEntity s where s.userId = :userId and s.state in :states order by s.sentAt"
    )
    fun findByUserIdAndStates(
        @Param("userId") userId: String,
        @Param("states") states: Collection<String>,
    ): List<OrderSubmissionEntity>

    @Query(
        "select s.brokerOrderId from OrderSubmissionEntity s where s.userId = :userId and s.brokerOrderId is not null"
    )
    fun findClaimedBrokerOrderIds(@Param("userId") userId: String): List<String>
}

interface ConditionalSubmissionRepository : JpaRepository<ConditionalSubmissionEntity, String> {
    @Query(
        "select s from ConditionalSubmissionEntity s where s.userId = :userId and s.clientOrderId = :clientOrderId"
    )
    fun findOwned(
        @Param("userId") userId: String,
        @Param("clientOrderId") clientOrderId: String,
    ): ConditionalSubmissionEntity?

    @Query(
        "select s from ConditionalSubmissionEntity s where s.userId = :userId and s.state in :states order by s.sentAt"
    )
    fun findByUserIdAndStates(
        @Param("userId") userId: String,
        @Param("states") states: Collection<String>,
    ): List<ConditionalSubmissionEntity>

    @Query(
        "select s.conditionalOrderId from ConditionalSubmissionEntity s where s.userId = :userId and s.conditionalOrderId is not null"
    )
    fun findClaimedConditionalOrderIds(@Param("userId") userId: String): List<String>
}
