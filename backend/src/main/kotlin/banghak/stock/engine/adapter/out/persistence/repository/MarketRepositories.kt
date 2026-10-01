package banghak.stock.engine.adapter.out.persistence.repository

import banghak.stock.engine.adapter.out.persistence.entity.CandleCoverageEntity
import banghak.stock.engine.adapter.out.persistence.entity.CandleEntity
import banghak.stock.engine.adapter.out.persistence.entity.CandleKey
import banghak.stock.engine.adapter.out.persistence.entity.CandleSeriesKey
import banghak.stock.engine.adapter.out.persistence.entity.StockKey
import banghak.stock.engine.adapter.out.persistence.entity.StockWarningEntity
import java.time.Instant
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StockWarningRepository : JpaRepository<StockWarningEntity, StockKey>

interface CandleRepository : JpaRepository<CandleEntity, CandleKey> {
    @Query(
        "select c from CandleEntity c where c.key.market = :market and c.key.code = :code " +
            "and c.key.interval = :interval and c.key.openTime >= :from and c.key.openTime <= :before " +
            "order by c.key.openTime desc"
    )
    fun findRange(
        @Param("market") market: String,
        @Param("code") code: String,
        @Param("interval") interval: String,
        @Param("from") from: Instant,
        @Param("before") before: Instant,
        limit: Limit,
    ): List<CandleEntity>
}

interface CandleCoverageRepository : JpaRepository<CandleCoverageEntity, CandleSeriesKey> {
    @Query("select c from CandleCoverageEntity c where c.key.interval = :interval")
    fun findSeries(@Param("interval") interval: String): List<CandleCoverageEntity>
}
