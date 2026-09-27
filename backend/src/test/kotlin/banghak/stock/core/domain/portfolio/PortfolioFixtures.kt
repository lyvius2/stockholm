package banghak.stock.core.domain.portfolio

import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.ExchangeRate
import banghak.stock.core.domain.money.Money
import banghak.stock.core.domain.trading.Quantity
import banghak.stock.core.domain.trading.TradingFixtures
import java.math.BigDecimal
import java.time.Instant

object PortfolioFixtures {
    val now: Instant = TradingFixtures.now

    fun fx(rate: String, asOf: Instant = now): ExchangeRate =
        ExchangeRate(Currency.USD, Currency.KRW, BigDecimal(rate), asOf)

    fun lot(
        symbol: Symbol = TradingFixtures.samsung,
        quantity: String = "10",
        remaining: String = quantity,
        unitCost: Money = TradingFixtures.krw("70000"),
        fxAtBuy: ExchangeRate? = null,
        boughtAt: Instant = now,
        origin: BuyOrigin = BuyOrigin.MANUAL,
        seed: Int = 1,
    ): Lot =
        Lot(
            LotId.from(Ulid.of(boughtAt, ByteArray(10) { seed.toByte() })),
            TradingFixtures.user,
            symbol,
            Quantity.of(quantity),
            Quantity.of(remaining),
            unitCost,
            fxAtBuy,
            boughtAt,
            origin,
        )
}
