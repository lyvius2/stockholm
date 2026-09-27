package banghak.stock.engine.adapter.out.llm

import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.CredentialFields
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.port.CredentialVerifier
import banghak.stock.engine.adapter.out.credential.CredentialChecks
import banghak.stock.shared.config.RuntimeProfiles
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** 모델 목록 조회 한 번으로 키를 확인함. 값은 헤더에만 싣고 어디에도 남기지 않음. */
@Component
@Profile(RuntimeProfiles.ENGINE)
class OpenAiCredentialVerifier(private val client: OpenAiModelsClient) : CredentialVerifier {
    override val kind = CredentialKind.OPENAI

    @CircuitBreaker(name = "openai-verify", fallbackMethod = "unreachable")
    override fun verify(fields: Map<String, SecretValue>): CredentialCheck {
        val response =
            client
                .models("Bearer " + String(fields.getValue(CredentialFields.VALUE).reveal()))
                .execute()
        return CredentialChecks.fromStatus(response, modelCount(response.body()))
    }

    fun unreachable(fields: Map<String, SecretValue>, cause: Throwable): CredentialCheck =
        CredentialChecks.unreachable(cause)
}

@Component
@Profile(RuntimeProfiles.ENGINE)
class AnthropicCredentialVerifier(private val client: AnthropicModelsClient) : CredentialVerifier {
    override val kind = CredentialKind.ANTHROPIC

    @CircuitBreaker(name = "anthropic-verify", fallbackMethod = "unreachable")
    override fun verify(fields: Map<String, SecretValue>): CredentialCheck {
        val response =
            client
                .models(String(fields.getValue(CredentialFields.VALUE).reveal()), ANTHROPIC_VERSION)
                .execute()
        return CredentialChecks.fromStatus(response, modelCount(response.body()))
    }

    fun unreachable(fields: Map<String, SecretValue>, cause: Throwable): CredentialCheck =
        CredentialChecks.unreachable(cause)

    companion object {
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }
}

@Component
@Profile(RuntimeProfiles.ENGINE)
class DeepSeekCredentialVerifier(private val client: DeepSeekModelsClient) : CredentialVerifier {
    override val kind = CredentialKind.DEEPSEEK

    @CircuitBreaker(name = "deepseek-verify", fallbackMethod = "unreachable")
    override fun verify(fields: Map<String, SecretValue>): CredentialCheck {
        val response =
            client
                .models("Bearer " + String(fields.getValue(CredentialFields.VALUE).reveal()))
                .execute()
        return CredentialChecks.fromStatus(response, modelCount(response.body()))
    }

    fun unreachable(fields: Map<String, SecretValue>, cause: Throwable): CredentialCheck =
        CredentialChecks.unreachable(cause)
}

/** 주소가 곧 자격임. `/api/tags` 가 200 이면 검증됨이고 모델이 없으면 경고를 detail 에 남김. */
@Component
@Profile(RuntimeProfiles.ENGINE)
class OllamaCredentialVerifier(private val client: OllamaTagsClient) : CredentialVerifier {
    override val kind = CredentialKind.OLLAMA

    @CircuitBreaker(name = "ollama-verify", fallbackMethod = "unreachable")
    override fun verify(fields: Map<String, SecretValue>): CredentialCheck {
        val address = String(fields.getValue(CredentialFields.VALUE).reveal()).trim().trimEnd('/')
        val base =
            address.toHttpUrlOrNull()?.takeIf(::isPlainServerAddress)
                ?: return CredentialCheck.Rejected("주소 형식이 아님(예: http://127.0.0.1:11434)")
        // 데몬이 이 주소로 요청을 보내므로 서버 주소 외의 요소(계정·쿼리·조각)는 받지 않음
        val url = base.newBuilder().addPathSegments("api/tags").build()
        val response = client.tags(url.toString()).execute()
        if (!response.isSuccessful) return CredentialChecks.fromStatus(response)
        val models = response.body()?.models.orEmpty()
        val detail =
            mapOf(
                "address" to address,
                "modelCount" to models.size.toString(),
                "models" to models.joinToString(",") { it.name },
            )
        return CredentialCheck.Ok(
            if (models.isEmpty()) detail + ("warning" to "설치된 모델 없음") else detail
        )
    }

    fun unreachable(fields: Map<String, SecretValue>, cause: Throwable): CredentialCheck =
        CredentialChecks.unreachable(cause)

    private fun isPlainServerAddress(url: HttpUrl): Boolean =
        url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.query == null &&
            url.fragment == null &&
            url.pathSegments.all { it.isEmpty() }
}

private fun modelCount(body: ModelList?): Map<String, String> =
    mapOf("modelCount" to body?.data?.size?.toString().orEmpty())
