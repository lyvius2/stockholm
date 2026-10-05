package banghak.stock.engine.adapter.out.kftc

import banghak.stock.core.domain.account.CredentialFields
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.domain.asset.AssetConsent
import banghak.stock.core.domain.asset.AssetKind
import banghak.stock.core.domain.asset.ExternalAsset
import banghak.stock.core.domain.error.AssetUnavailableException
import banghak.stock.core.domain.error.ConsentRequiredException
import banghak.stock.core.domain.error.DomainException
import banghak.stock.core.domain.error.SecretMissingException
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.money.Currency
import banghak.stock.core.domain.money.Money
import banghak.stock.core.port.AssetConsentPort
import banghak.stock.core.port.AssetPort
import banghak.stock.core.port.SecretStorePort
import banghak.stock.engine.adapter.out.credential.RevealedSecrets.asString
import banghak.stock.engine.adapter.out.keychain.SecretReader
import banghak.stock.engine.config.KftcProperties
import banghak.stock.shared.config.RuntimeProfiles
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import java.math.BigDecimal
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import retrofit2.Response
import tools.jackson.databind.json.JsonMapper

/**
 * Keychain 에 두는 사용자 토큰 묶음.
 * 값은 JSON 한 덩어리로 `KFTC_TOKEN` 항목에 있음.
 */
data class StoredKftcToken(
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Instant = Instant.EPOCH,
    val consentedAt: Instant = Instant.EPOCH,
    /**
     * 동의 자체의 만료.
     * 접근 토큰 만료([expiresAt])와 달리 갱신으로 늘어나지 않음.
     */
    val consentExpiresAt: Instant = Instant.EPOCH,
    val userSeqNo: String = "",
)

/**
 * 금융결제원 HTTP 호출.
 * 메서드마다 서킷 브레이커와 fallback 을 두고, 실패 사유에 토큰·코드가 섞이지 않게 종류 이름만 남김.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class KftcGateway(private val oauth: KftcOAuthClient, private val accounts: KftcAccountClient) {
    @CircuitBreaker(name = "kftc", fallbackMethod = "tokenUnavailable")
    fun exchange(code: String, clientId: String, clientSecret: String, redirectUri: String) =
        tokenOf(
            oauth.exchange(code, clientId, clientSecret, redirectUri, AUTHORIZATION_CODE).execute()
        )

    fun tokenUnavailable(
        code: String,
        clientId: String,
        clientSecret: String,
        redirectUri: String,
        cause: Throwable,
    ): KftcTokenResponse = throw unavailable(cause)

    @CircuitBreaker(name = "kftc", fallbackMethod = "refreshUnavailable")
    fun refresh(refreshToken: String, clientId: String, clientSecret: String, scope: String) =
        tokenOf(oauth.refresh(refreshToken, clientId, clientSecret, scope, REFRESH_TOKEN).execute())

    fun refreshUnavailable(
        refreshToken: String,
        clientId: String,
        clientSecret: String,
        scope: String,
        cause: Throwable,
    ): KftcTokenResponse = throw unavailable(cause)

    @CircuitBreaker(name = "kftc", fallbackMethod = "userMeUnavailable")
    fun userMe(accessToken: String, userSeqNo: String): KftcUserMe =
        bodyOf(accounts.userMe("Bearer $accessToken", userSeqNo).execute())

    fun userMeUnavailable(accessToken: String, userSeqNo: String, cause: Throwable): KftcUserMe =
        throw unavailable(cause)

    @CircuitBreaker(name = "kftc", fallbackMethod = "balanceUnavailable")
    fun balance(
        accessToken: String,
        bankTranId: String,
        fintechUseNum: String,
        tranDtime: String,
    ): KftcBalance =
        bodyOf(
            accounts.balance("Bearer $accessToken", bankTranId, fintechUseNum, tranDtime).execute()
        )

    fun balanceUnavailable(
        accessToken: String,
        bankTranId: String,
        fintechUseNum: String,
        tranDtime: String,
        cause: Throwable,
    ): KftcBalance = throw unavailable(cause)

    private fun tokenOf(response: Response<KftcTokenResponse>): KftcTokenResponse {
        val body = bodyOf(response)
        if (body.accessToken.isNullOrEmpty())
            throw AssetUnavailableException("금융결제원 토큰 응답에 토큰이 없음(${body.rspCode ?: "코드 없음"})")
        return body
    }

    private fun <T> bodyOf(response: Response<T>): T {
        if (response.code() == HTTP_UNAUTHORIZED)
            throw ConsentRequiredException("금융결제원 동의가 만료됐거나 철회됨")
        if (!response.isSuccessful)
            throw AssetUnavailableException("금융결제원 응답 HTTP ${response.code()}")
        return response.body() ?: throw AssetUnavailableException("금융결제원 응답이 비어 있음")
    }

    private fun unavailable(cause: Throwable): DomainException =
        cause as? DomainException
            ?: AssetUnavailableException("금융결제원에 연결할 수 없음(${cause::class.simpleName})", cause)

    companion object {
        private const val AUTHORIZATION_CODE = "authorization_code"
        private const val REFRESH_TOKEN = "refresh_token"
        private const val HTTP_UNAUTHORIZED = 401
    }
}

/**
 * 동의 토큰 보관과 자산 조회.
 * 앱 자격(client id/secret)은 admin 공유 키, 사용자 토큰은 그 사용자의 개인 키임.
 * 계좌번호는 가려진 표기만 받고 응답 원문은 로그에 남기지 않음.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class KftcAssetAdapter(
    private val gateway: KftcGateway,
    private val secrets: SecretReader,
    private val store: SecretStorePort,
    private val properties: KftcProperties,
    private val mapper: JsonMapper,
    private val clock: Clock,
) : AssetPort, AssetConsentPort {
    private val random = SecureRandom()

    // client_id 는 OAuth 규격상 동의 화면 주소에 실리는 공개 식별자임
    override fun authorizeUrl(userId: UserId, state: String): String =
        properties.baseUrl
            .toHttpUrl()
            .newBuilder()
            .addPathSegments("oauth/2.0/authorize")
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", sharedSecret(CredentialFields.CLIENT_ID))
            .addQueryParameter("redirect_uri", properties.redirectUri)
            .addQueryParameter("scope", properties.scope)
            .addQueryParameter("state", state)
            .addQueryParameter("auth_type", AUTH_TYPE_FIRST)
            .build()
            .toString()

    override fun completeConsent(userId: UserId, code: String): AssetConsent {
        val issued =
            gateway.exchange(
                code,
                sharedSecret(CredentialFields.CLIENT_ID),
                sharedSecret(CredentialFields.CLIENT_SECRET),
                properties.redirectUri,
            )
        val now = clock.instant()
        val stored =
            StoredKftcToken(
                accessToken = issued.accessToken.orEmpty(),
                refreshToken = issued.refreshToken.orEmpty(),
                expiresAt = now.plusSeconds(issued.expiresIn ?: 0),
                consentedAt = now,
                consentExpiresAt = now.plus(Duration.ofDays(properties.consentValidityDays)),
                userSeqNo = issued.userSeqNo.orEmpty(),
            )
        save(userId, stored)
        return consentOf(stored, now)
    }

    override fun consent(userId: UserId): AssetConsent =
        load(userId)?.let { consentOf(it, clock.instant()) } ?: AssetConsent.NONE

    override fun revoke(userId: UserId) = store.delete(tokenKey(userId))

    override fun assets(userId: UserId): List<ExternalAsset> {
        val token = usableToken(userId)
        val me = gateway.userMe(token.accessToken, token.userSeqNo)
        if (me.rspCode != SUCCESS) throw AssetUnavailableException("계좌 목록 응답 코드 ${me.rspCode}")
        val asOf = clock.instant()
        return me.resList.map { account -> assetOf(account, token.accessToken, asOf) }
    }

    // 만료가 가까우면 refresh 토큰으로 갈아 끼움.
    // refresh 도 안 되면 동의를 다시 받아야 함
    // 동의가 끝났으면 토큰이 살아 있어도 쓰지 않음
    private fun usableToken(userId: UserId): StoredKftcToken {
        val stored = load(userId) ?: throw ConsentRequiredException("금융결제원 동의가 없음")
        val now = clock.instant()
        if (!consentOf(stored, now).isActive) throw ConsentRequiredException("금융결제원 동의가 만료됨")
        if (now.isBefore(stored.expiresAt.minus(RENEW_BEFORE_EXPIRY))) return stored
        if (stored.refreshToken.isEmpty())
            throw ConsentRequiredException("금융결제원 토큰을 갱신할 수 없음. 다시 동의할 것")
        val renewed =
            gateway.refresh(
                stored.refreshToken,
                sharedSecret(CredentialFields.CLIENT_ID),
                sharedSecret(CredentialFields.CLIENT_SECRET),
                properties.scope,
            )
        val replaced =
            stored.copy(
                accessToken = renewed.accessToken.orEmpty(),
                refreshToken = renewed.refreshToken ?: stored.refreshToken,
                expiresAt = now.plusSeconds(renewed.expiresIn ?: 0),
                userSeqNo = renewed.userSeqNo ?: stored.userSeqNo,
            )
        save(userId, replaced)
        return replaced
    }

    // 조회 동의가 없는 계좌는 잔액 없이 목록에만 둠
    private fun assetOf(account: KftcAccount, accessToken: String, asOf: Instant): ExternalAsset {
        val balance =
            if (account.inquiryAgreeYn == "Y") balanceOf(account.fintechUseNum, accessToken)
            else null
        return ExternalAsset(
            institution = account.bankName.ifBlank { "금융기관" },
            maskedAccount = ExternalAsset.maskLast4(account.accountNumMasked),
            kind = kindOf(account.accountType),
            balance = balance,
            asOf = asOf,
        )
    }

    // 응답 코드가 성공이 아니면 "잔액 없음" 이 아니라 조회 실패임(직전 결과를 보이거나 503)
    private fun balanceOf(fintechUseNum: String, accessToken: String): Money {
        val response =
            gateway.balance(accessToken, newBankTranId(), fintechUseNum, tranDtime(clock.instant()))
        if (response.rspCode != SUCCESS)
            throw AssetUnavailableException("잔액 조회 응답 코드 ${response.rspCode}")
        val amount = response.balanceAmt ?: throw AssetUnavailableException("잔액 조회 응답에 금액이 없음")
        return Money.of(BigDecimal(amount), Currency.KRW)
    }

    // 거래고유번호 = 이용기관코드(10자리) + "U" + 9자리 난수.
    // 이용기관코드가 비어 있으면 조회를 시작하지 않음
    private fun newBankTranId(): String {
        val useCode = properties.clientUseCode
        if (useCode.length != USE_CODE_LENGTH)
            throw AssetUnavailableException("금융결제원 이용기관코드(stockholm.kftc.client-use-code)를 설정할 것")
        val digits = (1..TRAN_ID_DIGITS).joinToString("") { random.nextInt(10).toString() }
        return "${useCode}U$digits"
    }

    private fun tranDtime(now: Instant): String = TRAN_DTIME.format(now.atZone(KST))

    private fun sharedSecret(field: String): String =
        asString(
            secrets.read(SecretKey.shared(CredentialKind.KFTC_APP.secretName(field)))
                ?: throw SecretMissingException("admin 이 금융결제원 앱 자격을 등록해야 함")
        )

    private fun load(userId: UserId): StoredKftcToken? =
        secrets.read(tokenKey(userId))?.let { value ->
            try {
                mapper.readValue(asString(value), StoredKftcToken::class.java)
            } finally {
                value.wipe()
            }
        }

    private fun save(userId: UserId, token: StoredKftcToken) {
        val value = SecretValue.of(mapper.writeValueAsString(token))
        try {
            store.put(tokenKey(userId), value)
        } finally {
            value.wipe()
        }
    }

    private fun consentOf(token: StoredKftcToken, now: Instant) =
        AssetConsent.of(token.consentedAt, token.consentExpiresAt, now)

    private fun tokenKey(userId: UserId) = SecretKey.user(userId, TOKEN_NAME)

    // 오픈뱅킹 계좌 종류 코드.
    // 공개 명세 기억에 기대며 모르는 값은 OTHER
    private fun kindOf(accountType: String): AssetKind =
        when (accountType) {
            "1" -> AssetKind.DEPOSIT
            "2" -> AssetKind.SAVINGS
            "6" -> AssetKind.FUND
            "T" -> AssetKind.SECURITIES
            else -> AssetKind.OTHER
        }

    companion object {
        const val TOKEN_NAME = "KFTC_TOKEN"
        private const val SUCCESS = "A0000"
        private const val AUTH_TYPE_FIRST = "0"
        private const val USE_CODE_LENGTH = 10
        private const val TRAN_ID_DIGITS = 9
        private val RENEW_BEFORE_EXPIRY: Duration = Duration.ofMinutes(5)
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private val TRAN_DTIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    }
}
