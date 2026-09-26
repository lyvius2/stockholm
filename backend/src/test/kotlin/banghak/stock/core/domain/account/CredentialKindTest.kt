package banghak.stock.core.domain.account

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CredentialKindTest {
    @Test
    @DisplayName("Keychain 항목 이름은 필드가 하나면 종류 이름, 둘이면 종류_필드")
    fun secretNames() {
        assertThat(CredentialKind.DART.secretName(CredentialFields.VALUE)).isEqualTo("DART")
        assertThat(CredentialKind.TOSS.secretName(CredentialFields.CLIENT_SECRET))
            .isEqualTo("TOSS_CLIENT_SECRET")
        assertThat(CredentialKind.NAVER.secretName(CredentialFields.CLIENT_ID))
            .isEqualTo("NAVER_CLIENT_ID")
    }

    @Test
    @DisplayName("토스만 개인 키이고 DART 와 토스가 필수, LLM 은 개별 필수가 아님")
    fun scopesAndRequirements() {
        assertThat(CredentialKind.entries.filter { it.scope == SecretScope.USER })
            .containsExactly(CredentialKind.TOSS)
        assertThat(CredentialKind.entries.filter { it.required })
            .containsExactlyInAnyOrder(CredentialKind.DART, CredentialKind.TOSS)
        assertThat(CredentialKind.entries.filter { it.isLlm }).allMatch { !it.required }
        assertThat(CredentialKind.entries.filter { it.isLlm }).hasSize(4)
    }
}
