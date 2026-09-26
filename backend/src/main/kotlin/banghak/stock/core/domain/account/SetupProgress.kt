package banghak.stock.core.domain.account

data class SetupProgress(
    val state: SetupState,
    val adminDisplayName: String?,
    val credentials: List<CredentialMeta>,
    val tossDecision: TossDecision,
    val llmPreset: LlmPreset?,
) {
    val hasVerifiedLlm: Boolean
        get() = credentials.any { it.kind.isLlm && it.status == CredentialStatus.VERIFIED }

    val hasVerifiedDart: Boolean
        get() = credentials.any {
            it.kind == CredentialKind.DART && it.status == CredentialStatus.VERIFIED
        }

    /** ② 를 넘을 조건: LLM 1개 이상 + DART. */
    val canFinishSharedKeys: Boolean
        get() = hasVerifiedLlm && hasVerifiedDart

    val hasVerifiedToss: Boolean
        get() = credentials.any {
            it.kind == CredentialKind.TOSS && it.status == CredentialStatus.VERIFIED
        }
}
