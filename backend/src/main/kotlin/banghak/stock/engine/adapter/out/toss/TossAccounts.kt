package banghak.stock.engine.adapter.out.toss

import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.error.DomainException
import banghak.stock.core.domain.error.InvalidValueException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.shared.config.RuntimeProfiles
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.ratelimiter.annotation.RateLimiter
import java.util.concurrent.ConcurrentHashMap
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 사용자의 종합매매 계좌 순번 조회.
 * 계좌 목록 API 는 초당 1회라 결과는 [TossAccountCache] 가 보관함.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class TossAccountLookup(private val accounts: TossAccountClient) {
    @RateLimiter(name = "toss-account")
    @CircuitBreaker(name = "toss-account", fallbackMethod = "accountsUnavailable")
    fun brokerageAccountSeq(caller: TossCaller): Long {
        val brokerage =
            TossOrderResponses.readResultOf(accounts.accounts(caller)).filter {
                it.accountType == BROKERAGE
            }
        // TODO(마법사 ③ 계좌 선택): 종합매매 계좌가 여럿이면 사용자가 고른 계좌를 저장해 쓰게 할 것
        return when (brokerage.size) {
            1 -> brokerage.single().accountSeq
            0 -> throw InvalidValueException("토스 종합매매 계좌가 없음")
            else -> throw InvalidValueException("토스 종합매매 계좌가 여럿이라 어느 계좌로 할지 골라야 함")
        }
    }

    fun accountsUnavailable(caller: TossCaller, cause: Throwable): Long =
        throw (cause as? DomainException
            ?: BrokerUnavailableException("토스 계좌 목록에 연결할 수 없음(${cause::class.simpleName})"))

    companion object {
        private const val BROKERAGE = "BROKERAGE"
    }
}

/**
 * 사용자별 계좌 순번 보관.
 * 계좌 순번은 계좌번호가 아니라 토스가 매긴 순번임.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class TossAccountCache(private val lookup: TossAccountLookup) {
    private val seqs = ConcurrentHashMap<UserId, Long>()

    fun accountSeq(userId: UserId): Long =
        seqs[userId] ?: lookup.brokerageAccountSeq(TossCaller(userId)).also { seqs[userId] = it }
}
