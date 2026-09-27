package banghak.stock.engine.application.account

import banghak.stock.core.domain.account.CredentialCheck
import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.CredentialStatus
import banghak.stock.core.domain.account.Device
import banghak.stock.core.domain.account.LlmPreset
import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.domain.account.SetupState
import banghak.stock.core.domain.account.TossDecision
import banghak.stock.core.domain.error.IllegalSetupTransitionException
import banghak.stock.core.domain.error.TotpRejectedException
import banghak.stock.core.domain.error.WeakPasswordException
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.usecase.CreateAdminCommand
import banghak.stock.engine.adapter.out.credential.FormatCredentialVerifier
import banghak.stock.shared.crypto.UlidGenerator
import banghak.stock.support.fakes.FakePasswordHasher
import banghak.stock.support.fakes.FakeTokenGenerator
import banghak.stock.support.fakes.FakeTotpPort
import banghak.stock.support.fakes.MemoryAuditLogPort
import banghak.stock.support.fakes.MemoryCredentialMetaPort
import banghak.stock.support.fakes.MemoryDevicePort
import banghak.stock.support.fakes.MemoryInstallationPort
import banghak.stock.support.fakes.MemoryRecoveryCodePort
import banghak.stock.support.fakes.MemorySecretStore
import banghak.stock.support.fakes.MemorySessionPort
import banghak.stock.support.fakes.MemoryUserAccountPort
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 마법사 상태 기계를 fake 포트로 끝까지 돌림. 검증 실패 값은 저장되지 않고, 어떤 출력에도 값이 없음. */
class SetupServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-27T09:00:00Z"), ZoneOffset.UTC)
    private val installations = MemoryInstallationPort()
    private val users = MemoryUserAccountPort()
    private val credentials = MemoryCredentialMetaPort()
    private val secrets = MemorySecretStore()
    private val audit = MemoryAuditLogPort()
    private val totp = FakeTotpPort()
    private val service =
        SetupService(
            installations,
            users,
            credentials,
            secrets,
            CredentialKind.entries.map(::FormatCredentialVerifier),
            FakePasswordHasher(),
            totp,
            audit,
            UlidGenerator(clock),
            clock,
            LoginService(
                users,
                MemorySessionPort(),
                localDevice(),
                MemoryRecoveryCodePort(),
                FakePasswordHasher(),
                totp,
                FakeTokenGenerator(),
                audit,
                clock,
            ),
        )
    private val marker = "MARKER-KEY-VALUE-7d3a1"

    @Test
    @DisplayName("설치 직후 상태는 NOT_STARTED 이고 admin 이 없음")
    fun freshInstallation() {
        val progress = service.progress()
        assertThat(progress.state).isEqualTo(SetupState.NOT_STARTED)
        assertThat(progress.adminDisplayName).isNull()
        assertThat(progress.credentials).isEmpty()
    }

    @Test
    @DisplayName("진행 조회는 설치 행을 만들지 않고, 첫 쓰기가 만든 설치 ID 가 그 뒤로 유지됨")
    fun progressDoesNotCreateInstallation() {
        service.progress()
        assertThat(installations.installation).isNull()
        service.createAdmin(
            CreateAdminCommand(
                "월터",
                "correct-horse-battery".toCharArray(),
                "correct-horse-battery".toCharArray(),
            )
        )
        val id = installations.installation?.installationId
        service.progress()
        assertThat(installations.installation?.installationId).isEqualTo(id)
    }

    @Test
    @DisplayName("① admin 생성 → TOTP 확인 → ADMIN_CREATED")
    fun createAdminThenConfirmTotp() {
        val created =
            service.createAdmin(
                CreateAdminCommand(
                    "월터",
                    "correct-horse-battery".toCharArray(),
                    "correct-horse-battery".toCharArray(),
                )
            )
        assertThat(service.progress().state).isEqualTo(SetupState.NOT_STARTED)
        assertThat(totp.pending).containsExactly(created.userId)
        assertThatThrownBy { service.confirmAdminTotp("000000") }
            .isInstanceOf(TotpRejectedException::class.java)
        val progress = service.confirmAdminTotp("123456")
        assertThat(progress.state).isEqualTo(SetupState.ADMIN_CREATED)
        assertThat(progress.adminDisplayName).isEqualTo("월터")
        assertThat(users.accounts.values.single().isTotpEnrolled).isTrue()
        assertThat(users.accounts.values.single().passwordHash)
            .doesNotContain("correct-horse-battery")
    }

    @Test
    @DisplayName("약한 비밀번호는 admin 을 만들지 않음")
    fun weakPasswordRejected() {
        assertThatThrownBy {
                service.createAdmin(
                    CreateAdminCommand("월터", "short".toCharArray(), "short".toCharArray())
                )
            }
            .isInstanceOf(WeakPasswordException::class.java)
        assertThat(users.count()).isZero()
    }

    @Test
    @DisplayName("② 공유 키는 ADMIN_CREATED 뒤에만, 검증 성공 값만 Keychain 에 저장됨")
    fun sharedKeysRequireAdminAndStoreOnlyVerified() {
        assertThatThrownBy {
                service.registerSharedCredential(
                    CredentialKind.DART,
                    mapOf("VALUE" to SecretValue.of(marker)),
                )
            }
            .isInstanceOf(IllegalSetupTransitionException::class.java)
        adminCreated()
        val rejected =
            service.registerSharedCredential(
                CredentialKind.DART,
                mapOf("VALUE" to SecretValue.of("bad key")),
            )
        assertThat(rejected).isInstanceOf(CredentialCheck.Rejected::class.java)
        assertThat(secrets.exists(SecretKey.shared("DART"))).isFalse()
        assertThat(credentials.findShared().single().status).isEqualTo(CredentialStatus.REJECTED)

        val ok =
            service.registerSharedCredential(
                CredentialKind.DART,
                mapOf("VALUE" to SecretValue.of(marker)),
            )
        assertThat(ok.isOk).isTrue()
        assertThat(secrets.exists(SecretKey.shared("DART"))).isTrue()
        val meta = credentials.findShared().single()
        assertThat(meta.status).isEqualTo(CredentialStatus.VERIFIED)
        assertThat(meta.last4).isEqualTo("7d3a1".takeLast(4))
    }

    @Test
    @DisplayName("② 를 넘으려면 LLM 1개 이상과 DART 가 검증되어야 함")
    fun finishSharedKeysNeedsLlmAndDart() {
        adminCreated()
        service.registerSharedCredential(
            CredentialKind.DART,
            mapOf("VALUE" to SecretValue.of(marker)),
        )
        assertThatThrownBy { service.finishSharedKeys(LlmPreset.BALANCED) }
            .isInstanceOf(IllegalSetupTransitionException::class.java)
        service.registerSharedCredential(
            CredentialKind.OLLAMA,
            mapOf("VALUE" to SecretValue.of("http://127.0.0.1:11434")),
        )
        val progress = service.finishSharedKeys(LlmPreset.LOCAL)
        assertThat(progress.state).isEqualTo(SetupState.SHARED_KEYS_DONE)
        assertThat(progress.llmPreset).isEqualTo(LlmPreset.LOCAL)
    }

    @Test
    @DisplayName("③ 토스는 등록(검증 필요) 또는 나중에, ④ 완료 뒤에는 키 등록이 닫힘")
    fun tossDecisionAndComplete() {
        sharedKeysDone()
        assertThatThrownBy { service.decideToss(TossDecision.REGISTERED) }
            .isInstanceOf(IllegalSetupTransitionException::class.java)
        val check =
            service.registerTossCredential(
                mapOf(
                    "CLIENT_ID" to SecretValue.of("client-id-0001"),
                    "CLIENT_SECRET" to SecretValue.of(marker),
                )
            )
        assertThat(check.isOk).isTrue()
        val admin = users.accounts.values.single()
        assertThat(secrets.exists(SecretKey.user(admin.userId, "TOSS_CLIENT_SECRET"))).isTrue()
        assertThat(service.decideToss(TossDecision.REGISTERED).state)
            .isEqualTo(SetupState.TOSS_DECIDED)
        assertThat(users.accounts.values.single().tossKeyDecision)
            .isEqualTo(TossDecision.REGISTERED)
        assertThat(service.complete().state).isEqualTo(SetupState.COMPLETE)
        assertThatThrownBy {
                service.registerSharedCredential(
                    CredentialKind.FRED,
                    mapOf("VALUE" to SecretValue.of(marker)),
                )
            }
            .isInstanceOf(IllegalSetupTransitionException::class.java)
        assertThatThrownBy { service.complete() }
            .isInstanceOf(IllegalSetupTransitionException::class.java)
    }

    @Test
    @DisplayName("'나중에' 는 토스 키 없이 TOSS_DECIDED 로 감")
    fun tossLater() {
        sharedKeysDone()
        assertThat(service.decideToss(TossDecision.LATER).state).isEqualTo(SetupState.TOSS_DECIDED)
        assertThat(service.progress().tossDecision).isEqualTo(TossDecision.LATER)
    }

    @Test
    @DisplayName("감사 로그·메타·진행 정보 어디에도 키 값이 없음")
    fun secretNeverLeaks() {
        sharedKeysDone()
        val everything = buildString {
            audit.entries.forEach { append(it.toString()) }
            credentials.metas.values.forEach { append(it.toString()) }
            append(service.progress().toString())
        }
        assertThat(everything).doesNotContain(marker)
        assertThat(audit.entries.map { it.action.name })
            .contains(
                "SETUP_ADMIN_CREATED",
                "SETUP_TOTP_CONFIRMED",
                "KEY_VERIFY",
                "KEY_SET",
                "SETUP_KEYS_DONE",
            )
    }

    private fun localDevice() =
        MemoryDevicePort().apply {
            save(
                Device(
                    DeviceId.from(Ulid.of(clock.instant(), ByteArray(10))),
                    "pk",
                    "mac",
                    null,
                    clock.instant(),
                    null,
                )
            )
        }

    private fun adminCreated() {
        service.createAdmin(
            CreateAdminCommand(
                "월터",
                "correct-horse-battery".toCharArray(),
                "correct-horse-battery".toCharArray(),
            )
        )
        service.confirmAdminTotp("123456")
    }

    private fun sharedKeysDone() {
        adminCreated()
        service.registerSharedCredential(
            CredentialKind.DART,
            mapOf("VALUE" to SecretValue.of(marker)),
        )
        service.registerSharedCredential(
            CredentialKind.OPENAI,
            mapOf("VALUE" to SecretValue.of("sk-" + marker)),
        )
        service.finishSharedKeys(LlmPreset.BALANCED)
    }
}
