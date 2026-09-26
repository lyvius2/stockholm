package banghak.stock.core.domain.account

object CredentialFields {
    const val VALUE = "VALUE"
    const val CLIENT_ID = "CLIENT_ID"
    const val CLIENT_SECRET = "CLIENT_SECRET"
}

/** 마법사 ② 화면의 그룹. */
enum class CredentialGroup {
    LLM,
    DISCLOSURE,
    MARKET_DATA,
    NEWS,
    PUBLIC_DATA,
    NOTIFICATION,
    KFTC,
    CACHE,
    BROKER,
}

/**
 * 자격(키) 종류. 값은 필드마다 Keychain 항목 하나(`{kind}_{field}` 또는 필드가 하나면 `{kind}`).
 *
 * @property fields 입력 필드 이름. 대부분 하나(`VALUE`)이며 id·secret 쌍은 둘
 * @property required 필수 여부. LLM 은 그룹 전체에서 1개 이상이 필수라 개별로는 false
 */
enum class CredentialKind(
    val group: CredentialGroup,
    val scope: SecretScope,
    val fields: List<String>,
    val required: Boolean = false,
    val isSecret: Boolean = true,
) {
    OPENAI(CredentialGroup.LLM, SecretScope.SHARED, listOf(CredentialFields.VALUE)),
    ANTHROPIC(CredentialGroup.LLM, SecretScope.SHARED, listOf(CredentialFields.VALUE)),
    DEEPSEEK(CredentialGroup.LLM, SecretScope.SHARED, listOf(CredentialFields.VALUE)),
    OLLAMA(
        CredentialGroup.LLM,
        SecretScope.SHARED,
        listOf(CredentialFields.VALUE),
        isSecret = false,
    ),
    DART(
        CredentialGroup.DISCLOSURE,
        SecretScope.SHARED,
        listOf(CredentialFields.VALUE),
        required = true,
    ),
    KRX(CredentialGroup.MARKET_DATA, SecretScope.SHARED, listOf(CredentialFields.VALUE)),
    MASSIVE(CredentialGroup.MARKET_DATA, SecretScope.SHARED, listOf(CredentialFields.VALUE)),
    NAVER(
        CredentialGroup.NEWS,
        SecretScope.SHARED,
        listOf(CredentialFields.CLIENT_ID, CredentialFields.CLIENT_SECRET),
    ),
    ODCLOUD(CredentialGroup.PUBLIC_DATA, SecretScope.SHARED, listOf(CredentialFields.VALUE)),
    SEC_CONTACT_EMAIL(
        CredentialGroup.PUBLIC_DATA,
        SecretScope.SHARED,
        listOf(CredentialFields.VALUE),
        isSecret = false,
    ),
    FRED(CredentialGroup.PUBLIC_DATA, SecretScope.SHARED, listOf(CredentialFields.VALUE)),
    SLACK(CredentialGroup.NOTIFICATION, SecretScope.SHARED, listOf(CredentialFields.VALUE)),
    KFTC_APP(
        CredentialGroup.KFTC,
        SecretScope.SHARED,
        listOf(CredentialFields.CLIENT_ID, CredentialFields.CLIENT_SECRET),
    ),
    CACHE_SERVER(CredentialGroup.CACHE, SecretScope.SHARED, listOf(CredentialFields.VALUE)),
    TOSS(
        CredentialGroup.BROKER,
        SecretScope.USER,
        listOf(CredentialFields.CLIENT_ID, CredentialFields.CLIENT_SECRET),
        required = true,
    );

    val isLlm: Boolean
        get() = group == CredentialGroup.LLM

    /** 필드별 Keychain 항목 이름. */
    fun secretName(field: String): String {
        require(field in fields) { "$this 에 없는 필드: $field" }
        return if (fields.size == 1) name else "${name}_$field"
    }

    companion object {
        val sharedKinds: List<CredentialKind> = entries.filter { it.scope == SecretScope.SHARED }
    }
}
