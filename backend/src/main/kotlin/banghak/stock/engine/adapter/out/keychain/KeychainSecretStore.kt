package banghak.stock.engine.adapter.out.keychain

import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.account.SecretValue
import banghak.stock.core.domain.error.SecretStoreFailureException
import banghak.stock.core.port.SecretStorePort
import banghak.stock.shared.config.RuntimeProfiles
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * macOS Keychain 위의 비밀 저장소. `security` 명령을 자식 프로세스로 부름. 값은 명령 인자가 아니라 표준 입력으로 넘겨 프로세스 목록에 보이지 않게 함.
 * `security add-generic-password -w` 는 값을 두 번(입력·재입력) 읽음.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class KeychainSecretStore(private val properties: KeychainProperties) :
    SecretStorePort, SecretReader {
    override fun put(key: SecretKey, value: SecretValue) {
        val secret = value.reveal()
        try {
            val exitCode =
                run(
                        listOf(
                            "add-generic-password",
                            "-a",
                            properties.account,
                            "-s",
                            key.path,
                            "-U",
                            "-w",
                        ),
                        secret,
                    )
                    .exitCode
            if (exitCode != 0)
                throw SecretStoreFailureException("Keychain 저장 실패($key): exit $exitCode")
        } finally {
            secret.fill(Char.MIN_VALUE)
        }
    }

    override fun exists(key: SecretKey): Boolean =
        when (
            val exitCode =
                run(listOf("find-generic-password", "-a", properties.account, "-s", key.path))
                    .exitCode
        ) {
            0 -> true
            NOT_FOUND -> false
            else -> throw SecretStoreFailureException("Keychain 조회 실패($key): exit $exitCode")
        }

    override fun delete(key: SecretKey) {
        val exitCode =
            run(listOf("delete-generic-password", "-a", properties.account, "-s", key.path))
                .exitCode
        if (exitCode != 0 && exitCode != NOT_FOUND)
            throw SecretStoreFailureException("Keychain 삭제 실패($key): exit $exitCode")
    }

    override fun read(key: SecretKey): SecretValue? {
        val result =
            run(listOf("find-generic-password", "-a", properties.account, "-s", key.path, "-w"))
        return when (result.exitCode) {
            0 -> SecretValue(result.stdout.trimEnd('\n', '\r').toCharArray())
            NOT_FOUND -> null
            else ->
                throw SecretStoreFailureException("Keychain 읽기 실패($key): exit ${result.exitCode}")
        }
    }

    private fun run(arguments: List<String>, stdin: CharArray? = null): CommandResult {
        val process =
            try {
                ProcessBuilder(listOf(SECURITY) + arguments).start()
            } catch (e: IOException) {
                throw SecretStoreFailureException("security 명령을 실행할 수 없음", e)
            }
        OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8).use { writer ->
            if (stdin != null) {
                repeat(PASSWORD_PROMPTS) {
                    writer.write(stdin)
                    writer.write("\n")
                }
            }
        }
        val stdout =
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        // stderr 는 값이 섞일 수 있어 읽어서 버림
        process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw SecretStoreFailureException("security 명령이 ${TIMEOUT_SECONDS}초 안에 끝나지 않음")
        }
        return CommandResult(process.exitValue(), stdout)
    }

    private data class CommandResult(val exitCode: Int, val stdout: String)

    companion object {
        private const val SECURITY = "/usr/bin/security"
        // security 는 항목이 없을 때 44(errSecItemNotFound)로 끝남
        private const val NOT_FOUND = 44
        private const val PASSWORD_PROMPTS = 2
        private const val TIMEOUT_SECONDS = 10L
    }
}
