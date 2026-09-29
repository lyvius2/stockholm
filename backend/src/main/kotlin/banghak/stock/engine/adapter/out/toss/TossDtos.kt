package banghak.stock.engine.adapter.out.toss

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/**
 * 토스 성공 응답 봉투.
 * 실패는 `error` 봉투로 오며 [TossResponses] 가 도메인 예외로 바꿈.
 */
data class TossEnvelope<T>(val result: T? = null)

data class TossErrorEnvelope(val error: TossErrorBody? = null)

data class TossErrorBody(val code: String = "", val message: String = "")

data class TossTokenResponse(
    @JsonProperty("access_token") val accessToken: String = "",
    @JsonProperty("token_type") val tokenType: String = "",
    @JsonProperty("expires_in") val expiresIn: Long = 0,
)

data class TossPrice(
    val symbol: String = "",
    val timestamp: String? = null,
    val lastPrice: BigDecimal = BigDecimal.ZERO,
    val currency: String = "",
)

data class TossCandles(val candles: List<TossCandle> = emptyList(), val nextBefore: String? = null)

data class TossCandle(
    val timestamp: String = "",
    val openPrice: BigDecimal = BigDecimal.ZERO,
    val highPrice: BigDecimal = BigDecimal.ZERO,
    val lowPrice: BigDecimal = BigDecimal.ZERO,
    val closePrice: BigDecimal = BigDecimal.ZERO,
    val volume: BigDecimal = BigDecimal.ZERO,
    val currency: String = "",
)

data class TossExchangeRate(
    val baseCurrency: String = "",
    val quoteCurrency: String = "",
    val rate: BigDecimal = BigDecimal.ZERO,
    val validFrom: String = "",
    val validUntil: String = "",
)

data class TossSession(
    val startTime: String = "",
    val endTime: String = "",
    val singlePriceAuctionStartTime: String? = null,
    val singlePriceAuctionEndTime: String? = null,
)

data class TossKrSessions(
    val preMarket: TossSession? = null,
    val regularMarket: TossSession? = null,
    val afterMarket: TossSession? = null,
)

data class TossKrDay(val date: String = "", val integrated: TossKrSessions? = null)

data class TossKrCalendar(val today: TossKrDay = TossKrDay())

data class TossUsDay(
    val date: String = "",
    val dayMarket: TossSession? = null,
    val preMarket: TossSession? = null,
    val regularMarket: TossSession? = null,
    val afterMarket: TossSession? = null,
)

data class TossUsCalendar(val today: TossUsDay = TossUsDay())

data class TossOrderbook(
    val timestamp: String? = null,
    val currency: String = "",
    val asks: List<TossOrderbookEntry> = emptyList(),
    val bids: List<TossOrderbookEntry> = emptyList(),
)

data class TossOrderbookEntry(
    val price: BigDecimal = BigDecimal.ZERO,
    val volume: BigDecimal = BigDecimal.ZERO,
)

data class TossListedStock(
    val symbol: String = "",
    val name: String = "",
    val securityType: String = "",
    val isCommonShare: Boolean = false,
    val isinCode: String = "",
)

data class TossStockInfo(
    val symbol: String = "",
    val name: String = "",
    val englishName: String = "",
    val isinCode: String = "",
    val market: String = "",
    val securityType: String = "",
    val isCommonShare: Boolean = false,
    val status: String = "",
    val currency: String = "",
    val listDate: String? = null,
    val delistDate: String? = null,
    val sharesOutstanding: BigDecimal = BigDecimal.ZERO,
    val leverageFactor: BigDecimal? = null,
    val koreanMarketDetail: TossKrMarketDetail? = null,
)

// 필수 필드가 빠지면 거래 가능으로 오인하지 않도록 null 로 받아 어댑터가 거름
data class TossKrMarketDetail(
    val liquidationTrading: Boolean? = null,
    val nxtSupported: Boolean? = null,
    val krxTradingSuspended: Boolean? = null,
    val nxtTradingSuspended: Boolean? = null,
)

data class TossStockWarning(
    val warningType: String = "",
    val startDate: String? = null,
    val endDate: String? = null,
)
