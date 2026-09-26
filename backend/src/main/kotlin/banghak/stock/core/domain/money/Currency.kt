package banghak.stock.core.domain.money

/** 통화. `scale`은 저장·표시 소수 자릿수임. */
enum class Currency(val scale: Int) {
    KRW(0),
    USD(2),
}
