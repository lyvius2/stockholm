package banghak.stock.engine.adapter.out.persistence.repository

import banghak.stock.engine.adapter.out.persistence.entity.WatchlistGroupEntity
import banghak.stock.engine.adapter.out.persistence.entity.WatchlistItemEntity
import banghak.stock.engine.adapter.out.persistence.entity.WatchlistItemKey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface WatchlistGroupRepository : JpaRepository<WatchlistGroupEntity, String> {
    @Query("select g from WatchlistGroupEntity g where g.userId = :userId order by g.sortOrder")
    fun findByUserId(@Param("userId") userId: String): List<WatchlistGroupEntity>

    @Query("select g from WatchlistGroupEntity g where g.userId = :userId and g.groupId = :groupId")
    fun findOwned(
        @Param("userId") userId: String,
        @Param("groupId") groupId: String,
    ): WatchlistGroupEntity?
}

/**
 * 그룹 id 로만 닿음.
 * 호출자가 그 그룹이 사용자의 것인지 먼저 확인함.
 */
interface WatchlistItemRepository : JpaRepository<WatchlistItemEntity, WatchlistItemKey> {
    @Query(
        "select i from WatchlistItemEntity i where i.id.groupId in :groupIds " +
            "order by i.id.groupId, i.sortOrder"
    )
    fun findByGroupIds(@Param("groupIds") groupIds: Collection<String>): List<WatchlistItemEntity>

    @Modifying
    @Query("delete from WatchlistItemEntity i where i.id.groupId = :groupId")
    fun deleteByGroupId(@Param("groupId") groupId: String)
}
