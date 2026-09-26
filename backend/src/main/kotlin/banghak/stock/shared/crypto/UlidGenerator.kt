package banghak.stock.shared.crypto

import banghak.stock.core.domain.identity.Ulid
import java.security.SecureRandom
import java.time.Clock
import org.springframework.stereotype.Component

/** 새 ULID. 시계와 난수는 여기서만 만나고 core 의 `Ulid` 는 순수함. */
@Component
class UlidGenerator(private val clock: Clock) {
    private val random = SecureRandom()

    fun next(): Ulid =
        Ulid.of(clock.instant(), ByteArray(Ulid.ENTROPY_BYTES).also(random::nextBytes))

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}
