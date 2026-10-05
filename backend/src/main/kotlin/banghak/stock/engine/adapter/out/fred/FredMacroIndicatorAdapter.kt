package banghak.stock.engine.adapter.out.fred

import banghak.stock.core.domain.account.CredentialFields
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.error.DomainException
import banghak.stock.core.domain.error.MarketDataUnavailableException
import banghak.stock.core.domain.error.SecretMissingException
import banghak.stock.core.domain.market.MacroObservation
import banghak.stock.core.domain.market.MacroSeries
import banghak.stock.core.port.MacroIndicatorPort
import banghak.stock.engine.adapter.out.keychain.SecretReader
import banghak.stock.shared.config.RuntimeProfiles
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * FRED 관측값 어댑터.
 * 키는 쿼리에 실리므로 요청 주소·오류 본문을 로그·예외에 싣지 않음.
 * 키가 등록되지 않았으면 SecretMissingException(출처 미설정), 받지 못하면 MarketDataUnavailableException.
 * 휴장일 값은 "." 로 오므로 뺌.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class FredMacroIndicatorAdapter(
    private val client: FredObservationsClient,
    private val secrets: SecretReader,
) : MacroIndicatorPort {
    private val mapper = jsonMapper {
        addModule(kotlinModule())
        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    }

    @CircuitBreaker(name = "fred", fallbackMethod = "observationsUnavailable")
    override fun recentObservations(series: MacroSeries, count: Int): List<MacroObservation> {
        val key =
            secrets.read(SecretKey.shared(CredentialKind.FRED.secretName(CredentialFields.VALUE)))
                ?: throw SecretMissingException("FRED 키가 등록되지 않음")
        // 쓰고 나면 원본과 복사본을 모두 지움.
        // Retrofit 에 넘기는 String 은 불변이라 지울 수 없음
        val revealed = key.reveal()
        val response =
            try {
                // 휴장일("." 값)이 섞여 오므로 넉넉히 받아 거름
                client
                    .observations(series.fredId, String(revealed), "json", "desc", count * 2)
                    .execute()
            } catch (e: IOException) {
                throw MarketDataUnavailableException("FRED 에 연결할 수 없음(${e::class.simpleName})")
            } finally {
                revealed.fill(Char.MIN_VALUE)
                key.wipe()
            }
        if (!response.isSuccessful)
            throw MarketDataUnavailableException("FRED 오류 HTTP ${response.code()}")
        val body =
            response.body()?.string() ?: throw MarketDataUnavailableException("FRED 응답이 비어 있음")
        return mapper
            .readValue<FredObservationsBody>(body)
            .observations
            .mapNotNull { observationOf(it) }
            .take(count)
    }

    fun observationsUnavailable(
        series: MacroSeries,
        count: Int,
        cause: Throwable,
    ): List<MacroObservation> =
        throw (cause as? DomainException
            ?: MarketDataUnavailableException("FRED 에 연결할 수 없음(${cause::class.simpleName})"))

    private fun observationOf(row: FredObservation): MacroObservation? {
        val value = row.value.toBigDecimalOrNull() ?: return null
        return MacroObservation(LocalDate.parse(row.date), value)
    }
}

internal data class FredObservationsBody(val observations: List<FredObservation> = emptyList())

internal data class FredObservation(val date: String = "", val value: String = "")

private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()
