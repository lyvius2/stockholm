package banghak.stock.core.port

import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.identity.UserId
import banghak.stock.engine.adapter.out.keychain.SecretReader
import banghak.stock.support.fakes.MemorySecretStore
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** `SecretStorePort` 구현이 지켜야 할 계약. 메모리 fake 로 검증하고 Keychain 구현이 상속해 같은 계약을 돌림. */
open class SecretStoreContractTest {
    protected open fun newStore(): SecretStorePort = MemorySecretStore()

    private lateinit var store: SecretStorePort
    private val user = UserId.from(Ulid.of(Instant.parse("2026-09-27T00:00:00Z"), ByteArray(10)))
    private val shared = SecretKey.shared("CONTRACT_TEST_SHARED")
    private val personal = SecretKey.user(user, "CONTRACT_TEST_TOSS")

    @BeforeEach
    fun createStore() {
        store = newStore()
    }

    @AfterEach
    fun cleanUp() {
        store.delete(shared)
        store.delete(personal)
    }

    @Test
    @DisplayName("넣으면 있고, 지우면 없고, 없는 것을 지워도 실패하지 않음")
    fun putExistsDelete() {
        assertThat(store.exists(shared)).isFalse()
        store.put(shared, SecretValue.of("marker-one"))
        assertThat(store.exists(shared)).isTrue()
        store.delete(shared)
        assertThat(store.exists(shared)).isFalse()
        store.delete(shared)
    }

    @Test
    @DisplayName("같은 키에 다시 넣으면 덮어씀")
    fun putOverwrites() {
        store.put(shared, SecretValue.of("marker-one"))
        store.put(shared, SecretValue.of("marker-two"))
        assertThat(readText(shared)).isEqualTo("marker-two")
    }

    @Test
    @DisplayName("공유 키와 개인 키는 서로 다른 항목임")
    fun scopesDoNotCollide() {
        store.put(shared, SecretValue.of("marker-shared"))
        assertThat(store.exists(personal)).isFalse()
        store.put(personal, SecretValue.of("marker-personal"))
        assertThat(readText(personal)).isEqualTo("marker-personal")
        assertThat(readText(shared)).isEqualTo("marker-shared")
    }

    @Test
    @DisplayName("없는 키를 읽으면 null")
    fun readMissingIsNull() {
        assertThat((store as SecretReader).read(shared)).isNull()
    }

    private fun readText(key: SecretKey): String? =
        (store as SecretReader).read(key)?.let { String(it.reveal()) }
}
