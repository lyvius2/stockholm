package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.market.IndexCode
import banghak.stock.core.domain.market.IndexQuote
import banghak.stock.core.domain.money.Percent
import banghak.stock.core.port.IndexQuoteStorePort
import banghak.stock.engine.adapter.out.persistence.entity.MarketIndexQuoteEntity
import banghak.stock.engine.adapter.out.persistence.repository.MarketIndexQuoteRepository
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** 프록시 티커는 `source` 열에 `TOSS_ETF:SPY` 처럼 함께 적음. */
@Component
@Profile(RuntimeProfiles.ENGINE)
class JpaIndexQuoteStore(private val repository: MarketIndexQuoteRepository) : IndexQuoteStorePort {
    @Transactional(readOnly = true)
    override fun loadAll(): List<IndexQuote> =
        repository.findAll().mapNotNull { row ->
            IndexCode.entries.firstOrNull { it.name == row.indexCode }?.let { quoteOf(it, row) }
        }

    @Transactional
    override fun saveAll(quotes: List<IndexQuote>) {
        quotes.forEach { quote ->
            val row = repository.findById(quote.code.name).orElse(null)
            if (row == null) repository.save(rowOf(quote)) else update(row, quote)
        }
    }

    private fun quoteOf(code: IndexCode, row: MarketIndexQuoteEntity): IndexQuote? {
        val asOf = row.asOf ?: return null
        val (source, proxy) =
            row.source.split(PROXY_SEPARATOR, limit = 2).let { it[0] to it.getOrNull(1) }
        return IndexQuote(
            code,
            row.value,
            row.changeAmount,
            row.changeRatio?.let { Percent(it) },
            asOf,
            row.closed,
            proxy,
            source,
        )
    }

    private fun rowOf(quote: IndexQuote) =
        MarketIndexQuoteEntity(
            quote.code.name,
            quote.value,
            quote.change,
            quote.changeRatio?.ratio,
            quote.asOf,
            quote.isClosed,
            sourceOf(quote),
            quote.asOf,
        )

    private fun update(row: MarketIndexQuoteEntity, quote: IndexQuote) {
        row.value = quote.value
        row.changeAmount = quote.change
        row.changeRatio = quote.changeRatio?.ratio
        row.asOf = quote.asOf
        row.closed = quote.isClosed
        row.source = sourceOf(quote)
        row.fetchedAt = quote.asOf
    }

    private fun sourceOf(quote: IndexQuote): String =
        if (quote.proxy == null) quote.source else "${quote.source}$PROXY_SEPARATOR${quote.proxy}"

    companion object {
        private const val PROXY_SEPARATOR = ":"
    }
}
