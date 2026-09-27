package banghak.stock.engine.application.account

import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.CredentialStatus
import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.domain.error.ForbiddenException
import banghak.stock.core.domain.identity.Role
import banghak.stock.core.port.FormatCredentialVerifier
import banghak.stock.engine.adapter.out.credential.StoredCredentialRechecker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CredentialAdminServiceTest {
    private val f = AuthTestFixture()
    private val verifiers = CredentialKind.entries.map(::FormatCredentialVerifier)
    private val service =
        CredentialAdminService(
            f.credentials,
            f.secrets,
            verifiers,
            StoredCredentialRechecker(f.secrets, verifiers),
            f.audit,
            f.clock,
        )
    private val admin = f.user("월터", Role.ADMIN)
    private val marker = "MARKER-ADMIN-KEY-51ab"

    @Test
    @DisplayName("교체는 검증 성공 값만 저장하고 실패하면 옛 값을 유지함")
    fun replaceKeepsOldValueOnFailure() {
        val principal = f.principal(admin, steppedUp = true)
        assertThat(
                service
                    .replaceShared(
                        principal,
                        CredentialKind.DART,
                        mapOf("VALUE" to SecretValue.of(marker)),
                    )
                    .isOk
            )
            .isTrue()
        assertThat(
                service
                    .replaceShared(
                        principal,
                        CredentialKind.DART,
                        mapOf("VALUE" to SecretValue.of("bad")),
                    )
                    .isOk
            )
            .isFalse()
        assertThat(String(f.secrets.read(SecretKey.shared("DART"))?.reveal() ?: CharArray(0)))
            .isEqualTo(marker)
        assertThat(f.credentials.findShared().single().status).isEqualTo(CredentialStatus.VERIFIED)
    }

    @Test
    @DisplayName("재검증은 저장된 값을 어댑터 안에서만 읽고, 삭제하면 저장된 키가 없다고 답함")
    fun recheckAndDelete() {
        val principal = f.principal(admin, steppedUp = true)
        service.replaceShared(
            principal,
            CredentialKind.FRED,
            mapOf("VALUE" to SecretValue.of(marker)),
        )
        assertThat(service.recheckShared(principal, CredentialKind.FRED).isOk).isTrue()
        service.deleteShared(principal, CredentialKind.FRED)
        assertThat(f.secrets.exists(SecretKey.shared("FRED"))).isFalse()
        assertThat(service.recheckShared(principal, CredentialKind.FRED))
            .isInstanceOf(CredentialCheck.Rejected::class.java)
    }

    @Test
    @DisplayName("구성원은 공유 키를 못 만지고, 본인 토스 키는 step-up 뒤에만 교체함")
    fun memberBoundaries() {
        val member = f.user("구성원")
        assertThatThrownBy { service.sharedCredentials(f.principal(member, steppedUp = true)) }
            .isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy {
                service.replaceOwnToss(
                    f.principal(member),
                    mapOf(
                        "CLIENT_ID" to SecretValue.of("client-0001"),
                        "CLIENT_SECRET" to SecretValue.of(marker),
                    ),
                )
            }
            .isInstanceOf(ForbiddenException::class.java)
        assertThat(
                service
                    .replaceOwnToss(
                        f.principal(member, steppedUp = true),
                        mapOf(
                            "CLIENT_ID" to SecretValue.of("client-0001"),
                            "CLIENT_SECRET" to SecretValue.of(marker),
                        ),
                    )
                    .isOk
            )
            .isTrue()
        assertThat(f.secrets.exists(SecretKey.user(member.userId, "TOSS_CLIENT_SECRET"))).isTrue()
        assertThat(f.audit.entries.joinToString()).doesNotContain(marker)
    }
}
