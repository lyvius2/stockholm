package banghak.stock.core.domain.market

import banghak.stock.core.domain.money.Currency
import java.time.ZoneId

/** 거래 시장. 통화·시간대·수량 자릿수를 가짐. 미국 소수점 수량은 토스가 6자리까지 허용함(시장가 매도에만, 보유·매도가능수량에는 소수점이 올 수 있음). */
enum class Market(val currency: Currency, val zone: ZoneId, val quantityScale: Int) {
    KR(Currency.KRW, ZoneId.of("Asia/Seoul"), 0),
    US(Currency.USD, ZoneId.of("America/New_York"), 6),
}
