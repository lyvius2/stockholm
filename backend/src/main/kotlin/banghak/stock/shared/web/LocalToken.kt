package banghak.stock.shared.web

import banghak.stock.shared.config.StockholmProperties
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import org.springframework.stereotype.Component

/**
 * 데몬이 기동할 때 만드는 로컬 토큰. 파일은 소유자만 읽을 수 있고 Electron 셸만 읽어 헤더로 보냄. 다른 프로세스가 로컬 API 를 부르지 못하게 하는 첫 방어선임.
 */
@Component
class LocalToken(properties: StockholmProperties) {
    val file: Path = properties.dataDir.resolve(FILE_NAME)
    private val value: String =
        HexFormat.of().formatHex(ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) })

    init {
        Files.createDirectories(properties.dataDir)
        Files.writeString(file, value)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
    }

    fun matches(presented: String?): Boolean =
        presented != null && MessageDigest.isEqual(presented.toByteArray(), value.toByteArray())

    companion object {
        const val HEADER = "X-Stockholm-Local-Token"
        const val FILE_NAME = "local-token"
        private const val TOKEN_BYTES = 32
    }
}
