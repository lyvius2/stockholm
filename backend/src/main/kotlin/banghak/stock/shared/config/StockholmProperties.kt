package banghak.stock.shared.config

import java.nio.file.Path
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 설치 단위 설정. 비밀값은 여기 두지 않음(Keychain 전용).
 *
 * @property dataDir SQLite·Lucene 인덱스·로컬 토큰 파일이 놓이는 앱 데이터 폴더
 */
@ConfigurationProperties("stockholm") data class StockholmProperties(val dataDir: Path)
