package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.StockFlags
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.port.StockFlagsCachePort
import banghak.stock.engine.adapter.out.persistence.entity.StockKey
import banghak.stock.engine.adapter.out.persistence.entity.StockWarningEntity
import banghak.stock.engine.adapter.out.persistence.repository.StockWarningRepository
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@Profile(RuntimeProfiles.ENGINE)
class JpaStockFlagsCacheAdapter(private val repository: StockWarningRepository) :
    StockFlagsCachePort {
    @Transactional(readOnly = true)
    override fun find(symbol: Symbol): StockFlags? =
        repository.findById(keyOf(symbol)).orElse(null)?.let(::toDomain)

    @Transactional
    override fun save(flags: StockFlags) {
        val row =
            repository.findById(keyOf(flags.symbol)).orElse(null)
                ?: StockWarningEntity(
                    key = keyOf(flags.symbol),
                    investmentWarning = false,
                    investmentRisk = false,
                    administrative = null,
                    tradingHalted = null,
                    viStatic = false,
                    viDynamic = false,
                    overheated = false,
                    liquidation = false,
                    unknownWarning = false,
                    fetchedAt = flags.asOf,
                )
        row.investmentWarning = flags.isInvestmentWarning
        row.investmentRisk = flags.isInvestmentRisk
        row.administrative = flags.isAdministrative
        row.tradingHalted = flags.isTradingHalted
        row.viStatic = flags.isViStatic
        row.viDynamic = flags.isViDynamic
        row.overheated = flags.isOverheated
        row.liquidation = flags.isLiquidationTrading
        row.unknownWarning = flags.hasUnknownWarning
        row.fetchedAt = flags.asOf
        repository.save(row)
    }

    private fun keyOf(symbol: Symbol) = StockKey(symbol.market.name, symbol.code)

    private fun toDomain(row: StockWarningEntity) =
        StockFlags(
            symbol = Symbol(Market.valueOf(row.key.market), row.key.code),
            isInvestmentWarning = row.investmentWarning,
            isInvestmentRisk = row.investmentRisk,
            isOverheated = row.overheated,
            isLiquidationTrading = row.liquidation,
            isViStatic = row.viStatic,
            isViDynamic = row.viDynamic,
            hasUnknownWarning = row.unknownWarning,
            isTradingHalted = row.tradingHalted,
            isAdministrative = row.administrative,
            asOf = row.fetchedAt,
        )
}
