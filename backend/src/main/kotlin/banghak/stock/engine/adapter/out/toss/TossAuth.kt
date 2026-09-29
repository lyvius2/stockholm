package banghak.stock.engine.adapter.out.toss

import banghak.stock.core.domain.account.CredentialFields
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.error.BrokerAccessDeniedException
import banghak.stock.core.domain.error.BrokerUnavailableException
import banghak.stock.core.domain.error.DomainException
import banghak.stock.core.domain.error.SecretMissingException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.port.InstallationPort
import banghak.stock.engine.adapter.out.keychain.SecretReader
import banghak.stock.shared.config.RuntimeProfiles
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import retrofit2.Response as TossHttpResponse

/**
 * 누구의 토스 키로 부르는가.
 * Retrofit `@Tag` 로 요청에 붙어 인증 인터셉터가 읽음.
 */
data class TossCaller(val userId: UserId)

/**
 * 발급받은 토큰.
 * 값은 로그·예외에 싣지 않음.
 */
class TossToken(val value: String, val expiresAt: Instant) {
    override fun toString(): String = "TossToken(****, expiresAt=$expiresAt)"
}

/**
 * 공용 시세를 부를 때 쓸 호출자.
 * 공용 시세 수집은 admin 의 토스 키로 함.
 */
fun interface TossCallerResolver {
    fun publicMarketCaller(): TossCaller
}

@Component
@Profile(RuntimeProfiles.ENGINE)
class AdminTossCallerResolver(private val installations: InstallationPort) : TossCallerResolver {
    override fun publicMarketCaller(): TossCaller {
        val admin =
            installations.load()?.adminUserId
                ?: throw SecretMissingException("admin 이 없어 공용 시세를 부를 토스 키가 없음")
        return TossCaller(admin)
    }
}

/**
 * 토큰 발급.
 * 키는 호출 직전에 Keychain 에서 꺼내 쓰고 지움.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class TossTokenIssuer(
    private val client: TossAuthClient,
    private val secrets: SecretReader,
    private val clock: Clock,
) {
    // 인자는 value class(UserId)가 아닌 TossCaller 로 받음.
    // value class 인자는 JVM 메서드 이름이 바뀌어 fallback 을 못 찾음
    @CircuitBreaker(name = "toss-auth", fallbackMethod = "unavailable")
    fun issue(caller: TossCaller): TossToken {
        // 먼저 읽은 값도 뒤 조회가 실패하면 지워야 하므로 읽자마자 정리 범위에 넣음
        val clientId = readSecret(caller.userId, CredentialFields.CLIENT_ID)
        try {
            val clientSecret = readSecret(caller.userId, CredentialFields.CLIENT_SECRET)
            try {
                val response =
                    client
                        .token(GRANT_TYPE, String(clientId.reveal()), String(clientSecret.reveal()))
                        .execute()
                return tokenOf(response)
            } finally {
                clientSecret.wipe()
            }
        } finally {
            clientId.wipe()
        }
    }

    // 예외 메시지에 요청 본문(키)이 섞일 수 있어 종류 이름만 남김
    fun unavailable(caller: TossCaller, cause: Throwable): TossToken =
        throw (cause as? DomainException
            ?: BrokerUnavailableException("토스에 연결할 수 없음(${cause::class.simpleName})"))

    private fun tokenOf(response: TossHttpResponse<TossTokenResponse>): TossToken {
        val body = response.body()
        return when {
            response.isSuccessful && body != null ->
                TossToken(body.accessToken, clock.instant().plusSeconds(body.expiresIn))
            response.code() in DENIED_STATUSES ->
                throw BrokerAccessDeniedException("토스가 키 또는 접속 IP 를 거부함(HTTP ${response.code()})")
            else -> throw BrokerUnavailableException("토스 토큰 발급 실패(HTTP ${response.code()})")
        }
    }

    private fun readSecret(userId: UserId, field: String) =
        secrets.read(SecretKey.user(userId, CredentialKind.TOSS.secretName(field)))
            ?: throw SecretMissingException("토스 키가 등록되어 있지 않음")

    companion object {
        private const val GRANT_TYPE = "client_credentials"
        private val DENIED_STATUSES = setOf(400, 401, 403)
    }
}

/**
 * 사용자별 토큰 보관.
 * 토스는 클라이언트당 유효 토큰이 하나라 새로 발급하면 직전 토큰이 즉시 무효가 됨.
 * 그래서 사용자마다 잠금을 두고 발급을 직렬화함.
 * 동시에 두 번 발급하면 먼저 받은 토큰으로 가던 요청이 모두 401 이 됨.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class TossTokenCache(private val issuer: TossTokenIssuer, private val clock: Clock) {
    private val tokens = ConcurrentHashMap<UserId, TossToken>()
    private val locks = ConcurrentHashMap<UserId, ReentrantLock>()

    fun bearer(userId: UserId): String = current(userId).value

    /**
     * 401 을 받은 토큰이 아직 보관 중인 토큰일 때만 지우고 새로 받음.
     * 이미 다른 요청이 갱신했으면 그 토큰을 씀.
     */
    fun renewAfterRejection(userId: UserId, rejected: String): String =
        lockOf(userId).withLock {
            val cached = tokens[userId]
            if (cached == null || cached.value == rejected) tokens.remove(userId)
            current(userId).value
        }

    private fun current(userId: UserId): TossToken =
        lockOf(userId).withLock {
            tokens[userId]?.takeIf { isUsable(it) }
                ?: issuer.issue(TossCaller(userId)).also { tokens[userId] = it }
        }

    private fun isUsable(token: TossToken): Boolean =
        Duration.between(clock.instant(), token.expiresAt) > RENEW_BEFORE_EXPIRY

    private fun lockOf(userId: UserId): ReentrantLock =
        locks.computeIfAbsent(userId) { ReentrantLock() }

    companion object {
        private val RENEW_BEFORE_EXPIRY: Duration = Duration.ofSeconds(60)
    }
}

/** `@Tag` 의 호출자 토큰을 Authorization 헤더로 붙임. */
class TossAuthInterceptor(private val tokens: TossTokenCache) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val caller =
            chain.request().tag(TossCaller::class.java) ?: return chain.proceed(chain.request())
        return chain.proceed(withBearer(chain.request(), tokens.bearer(caller.userId)))
    }
}

/**
 * 401 이면 토큰을 한 번만 갱신해 다시 보냄.
 * 두 번째 401 은 그대로 돌려줘 어댑터가 오류로 바꿈.
 */
class TossTokenAuthenticator(private val tokens: TossTokenCache) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null
        val caller = response.request.tag(TossCaller::class.java) ?: return null
        val rejected = response.request.header(AUTHORIZATION)?.removePrefix(BEARER).orEmpty()
        return withBearer(response.request, tokens.renewAfterRejection(caller.userId, rejected))
    }
}

private const val AUTHORIZATION = "Authorization"
private const val BEARER = "Bearer "

private fun withBearer(request: Request, token: String): Request =
    request.newBuilder().header(AUTHORIZATION, BEARER + token).build()
