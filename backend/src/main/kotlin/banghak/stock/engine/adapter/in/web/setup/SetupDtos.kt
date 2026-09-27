package banghak.stock.engine.adapter.`in`.web.setup

import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.CredentialMeta
import banghak.stock.core.domain.account.LlmPreset
import banghak.stock.core.domain.account.SetupProgress
import banghak.stock.core.domain.account.SetupState
import banghak.stock.core.domain.account.TossDecision
import java.time.Instant

/** 마법사 API 의 요청·응답. 값(키·비밀번호)은 응답에 절대 들어가지 않음. */
data class SetupStateResponse(
    val state: SetupState,
    val adminDisplayName: String?,
    val tossDecision: TossDecision,
    val llmPreset: LlmPreset?,
    val canFinishSharedKeys: Boolean,
    val credentials: List<CredentialSummary>,
) {
    companion object {
        fun from(progress: SetupProgress) =
            SetupStateResponse(
                state = progress.state,
                adminDisplayName = progress.adminDisplayName,
                tossDecision = progress.tossDecision,
                llmPreset = progress.llmPreset,
                canFinishSharedKeys = progress.canFinishSharedKeys,
                credentials = progress.credentials.map(CredentialSummary::from),
            )
    }
}

data class CredentialSummary(
    val kind: CredentialKind,
    val group: String,
    val scope: String,
    val required: Boolean,
    val fields: List<String>,
    val status: String,
    val last4: String?,
    val verifiedAt: Instant?,
    val statusDetail: String?,
    val detail: Map<String, String>,
) {
    companion object {
        fun from(meta: CredentialMeta) =
            CredentialSummary(
                kind = meta.kind,
                group = meta.kind.group.name,
                scope = meta.scope.name,
                required = meta.kind.required,
                fields = meta.kind.fields,
                status = meta.status.name,
                last4 = meta.last4,
                verifiedAt = meta.verifiedAt,
                statusDetail = meta.statusDetail,
                detail = meta.detail,
            )
    }
}

data class CreateAdminRequest(
    val displayName: String,
    val password: String,
    val passwordConfirmation: String,
)

data class AdminCreatedResponse(
    val userId: String,
    val totpQrPngBase64: String,
    val totpManualKey: String,
)

data class TotpCodeRequest(val code: String)

data class CredentialRequest(val fields: Map<String, String>)

data class CredentialCheckResponse(
    val result: String,
    val reason: String?,
    val detail: Map<String, String>,
) {
    companion object {
        fun from(check: CredentialCheck) =
            when (check) {
                is CredentialCheck.Ok -> CredentialCheckResponse("OK", null, check.detail)
                is CredentialCheck.Rejected ->
                    CredentialCheckResponse("REJECTED", check.reason, emptyMap())
                is CredentialCheck.Unreachable ->
                    CredentialCheckResponse("UNREACHABLE", check.reason, emptyMap())
            }
    }
}

data class FinishSharedKeysRequest(val llmPreset: LlmPreset = LlmPreset.BALANCED)

data class TossDecisionRequest(val decision: TossDecision)

data class WizardSessionResponse(val progress: SetupStateResponse, val wizardToken: String)

/** 마법사 ② 화면의 키 종류 목록. 값·상태 없이 종류 메타만. */
data class CredentialKindInfo(
    val kind: CredentialKind,
    val group: String,
    val scope: String,
    val required: Boolean,
    val fields: List<String>,
    val isSecret: Boolean,
    val isLlm: Boolean,
) {
    companion object {
        fun from(kind: CredentialKind) =
            CredentialKindInfo(
                kind,
                kind.group.name,
                kind.scope.name,
                kind.required,
                kind.fields,
                kind.isSecret,
                kind.isLlm,
            )
    }
}

data class WizardSessionRequest(val password: String, val totpCode: String)
