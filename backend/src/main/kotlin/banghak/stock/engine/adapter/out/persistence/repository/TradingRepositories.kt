package banghak.stock.engine.adapter.out.persistence.repository

import banghak.stock.engine.adapter.out.persistence.entity.BrokerOrderEntity
import banghak.stock.engine.adapter.out.persistence.entity.LotEntity
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
}

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
