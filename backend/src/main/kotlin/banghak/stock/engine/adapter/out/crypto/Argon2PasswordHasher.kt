package banghak.stock.engine.adapter.out.crypto

import banghak.stock.core.port.PasswordHasherPort
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Profile
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Component

/** Argon2id. spring-security-crypto 만 쓰고 Spring Security 전체는 들이지 않음. */
@Component
@Profile(RuntimeProfiles.ENGINE)
class Argon2PasswordHasher : PasswordHasherPort {
    private val encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()

    override fun hash(password: CharArray): String =
        requireNotNull(encoder.encode(String(password))) { "Argon2 인코더가 null 을 돌려줌" }

    override fun matches(password: CharArray, hash: String): Boolean =
        encoder.matches(String(password), hash)
}
