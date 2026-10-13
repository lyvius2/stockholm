package banghak.stock.engine.adapter.out.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

@Entity
@Table(name = "watchlist_group")
class WatchlistGroupEntity(
    @Id @Column(name = "group_id") val groupId: String,
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(nullable = false) var name: String,
    @Column(name = "sort_order", nullable = false) var sortOrder: Int,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)

@Embeddable
data class WatchlistItemKey(
    @Column(name = "group_id") val groupId: String,
    @Column(name = "market") val market: String,
    @Column(name = "code") val code: String,
) : Serializable

/** 사용자 열이 없는 표라 항상 사용자의 그룹 id 를 거쳐서만 읽고 씀. */
@Entity
@Table(name = "watchlist_item")
class WatchlistItemEntity(
    @EmbeddedId val id: WatchlistItemKey,
    @Column(name = "sort_order", nullable = false) var sortOrder: Int,
    @Column(name = "added_at", nullable = false) val addedAt: Instant,
)
