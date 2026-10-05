package banghak.stock.engine.adapter.out.credential

import banghak.stock.core.domain.account.SecretValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class RevealedSecretsTest {
    @Test
    @DisplayName("블록 안에서는 값을 쓰고, 끝나면 복사본이 0 으로 채워지고 원본은 그대로임")
    fun wipesCopyAfterUse() {
        val value = SecretValue.of("MARKER-secret")
        var handed: CharArray? = null

        val result =
            RevealedSecrets.withRevealed(value) { revealed ->
                handed = revealed
                String(revealed).length
            }

        assertThat(result).isEqualTo("MARKER-secret".length)
        assertThat(handed).containsOnly(Char.MIN_VALUE)
        assertThat(String(value.reveal())).isEqualTo("MARKER-secret")
    }

    @Test
    @DisplayName("블록이 예외를 던져도 복사본을 지우고 예외는 그대로 올라감")
    fun wipesEvenOnException() {
        var handed: CharArray? = null

        assertThatThrownBy {
                RevealedSecrets.withRevealed(SecretValue.of("MARKER-secret")) {
                    handed = it
                    throw IllegalStateException("boom")
                }
            }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(handed).containsOnly(Char.MIN_VALUE)
    }

    @Test
    @DisplayName("문자열 사본을 돌려주고 원본은 그대로임")
    fun asStringKeepsOriginal() {
        val value = SecretValue.of("id-1")

        assertThat(RevealedSecrets.asString(value)).isEqualTo("id-1")
        assertThat(String(value.reveal())).isEqualTo("id-1")
    }
}
