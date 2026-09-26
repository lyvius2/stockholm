package banghak.stock.core.domain.trading

import banghak.stock.core.domain.automation.StrategyId
import banghak.stock.core.domain.error.InvalidValue
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ClientOrderIdTest {
    private val user = UserId.from(Ulid.of(Instant.parse("2026-09-26T00:00:00Z"), ByteArray(10)))
    private val strategy = StrategyId("surge-v1")
    private val samsung = Symbol(Market.KR, "005930")
    private val day = LocalDate.of(2026, 9, 28)

    @Test
    @DisplayName("토스 제약대로 1~36자 영숫자·하이픈·밑줄만 허용함")
    fun acceptsOnlyTossCompliantFormat() {
        assertThat(ClientOrderId("abc-DEF_123").value).isEqualTo("abc-DEF_123")
        assertThatThrownBy { ClientOrderId("") }.isInstanceOf(InvalidValue::class.java)
        assertThatThrownBy { ClientOrderId("a".repeat(37)) }.isInstanceOf(InvalidValue::class.java)
        assertThatThrownBy { ClientOrderId("has space") }.isInstanceOf(InvalidValue::class.java)
        assertThatThrownBy { ClientOrderId("dot.not.ok") }.isInstanceOf(InvalidValue::class.java)
    }

    @Test
    @DisplayName("결정적 키는 같은 입력에 같은 값이고 26자임")
    fun deterministicKeyIsStableAndTwentySixChars() {
        val first = ClientOrderId.deterministic(user, strategy, samsung, day, sequence = 1)
        val again = ClientOrderId.deterministic(user, strategy, samsung, day, sequence = 1)
        assertThat(first).isEqualTo(again)
        assertThat(first.value).hasSize(26).matches("[0-9A-Z]{26}")
    }

    @Test
    @DisplayName("회차·종목·거래일·전략·사용자 중 하나만 달라도 다른 값임")
    fun anyInputChangeYieldsDifferentKey() {
        val base = ClientOrderId.deterministic(user, strategy, samsung, day, sequence = 1)
        assertThat(ClientOrderId.deterministic(user, strategy, samsung, day, sequence = 2))
            .isNotEqualTo(base)
        assertThat(ClientOrderId.deterministic(user, strategy, Symbol(Market.US, "NVDA"), day, 1))
            .isNotEqualTo(base)
        assertThat(ClientOrderId.deterministic(user, strategy, samsung, day.plusDays(1), 1))
            .isNotEqualTo(base)
        assertThat(ClientOrderId.deterministic(user, StrategyId("other"), samsung, day, 1))
            .isNotEqualTo(base)
        val otherUser =
            UserId.from(Ulid.of(Instant.parse("2026-09-26T00:00:00Z"), ByteArray(10) { 1 }))
        assertThat(ClientOrderId.deterministic(otherUser, strategy, samsung, day, 1))
            .isNotEqualTo(base)
    }

    @Test
    @DisplayName("수동 주문 키는 ULID 26자를 그대로 씀")
    fun manualKeyIsRawUlid() {
        val ulid = Ulid.of(Instant.parse("2026-09-26T00:00:00Z"), ByteArray(10))
        assertThat(ClientOrderId.from(ulid).value).isEqualTo(ulid.value)
    }
}
