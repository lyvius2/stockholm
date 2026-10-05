package banghak.stock.engine.adapter.out.credential

import banghak.stock.core.domain.account.SecretValue

/**
 * 비밀값을 호출 한 번에만 꺼내 쓰고 복사본을 지움.
 * `reveal()` 은 배열 복사본을 주므로 블록이 끝나면 여기서 0 으로 채움.
 * 블록이 HTTP 클라이언트에 넘기려고 만드는 String 은 불변이라 그 사본까지는 지우지 못함.
 */
internal object RevealedSecrets {
    fun <T> withRevealed(value: SecretValue, block: (CharArray) -> T): T {
        val revealed = value.reveal()
        try {
            return block(revealed)
        } finally {
            revealed.fill(Char.MIN_VALUE)
        }
    }

    /**
     * HTTP 클라이언트에 넘길 String 사본.
     * 배열 복사본은 여기서 바로 지우고, 호출은 서킷 브레이커가 붙은 메서드 본문에서 함.
     */
    fun asString(value: SecretValue): String = withRevealed(value) { String(it) }
}
