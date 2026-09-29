package banghak.stock.engine.adapter.out.persistence.repository

import banghak.stock.engine.adapter.out.persistence.entity.StockKey
import banghak.stock.engine.adapter.out.persistence.entity.StockWarningEntity
import org.springframework.data.jpa.repository.JpaRepository

interface StockWarningRepository : JpaRepository<StockWarningEntity, StockKey>
