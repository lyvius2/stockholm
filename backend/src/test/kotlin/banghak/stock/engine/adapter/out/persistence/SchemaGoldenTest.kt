package banghak.stock.engine.adapter.out.persistence

import banghak.stock.support.EngineDatabaseTest
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 빈 SQLite 에 마이그레이션을 적용한 결과가 골든 파일과 같아야 함. 다르면 `build/schema-actual.sql` 에 실제 스키마를 남기니, 의도한 변경이면 그
 * 내용을 골든 파일로 옮김.
 */
class SchemaGoldenTest : EngineDatabaseTest() {
    @Autowired private lateinit var engineWriteDataSource: HikariDataSource

    @Test
    @DisplayName("마이그레이션 결과 스키마가 골든 파일과 같음")
    fun schemaMatchesGoldenFile() {
        val actual = dumpSchema()
        Files.createDirectories(ACTUAL.parent)
        Files.writeString(ACTUAL, actual)
        val golden = javaClass.getResource(GOLDEN)?.readText()
        assertThat(golden).describedAs("골든 파일 $GOLDEN 이 없음. $ACTUAL 을 검토해 옮길 것").isNotNull()
        assertThat(actual).isEqualTo(golden)
    }

    private fun dumpSchema(): String =
        JdbcTemplate(engineWriteDataSource)
            .queryForList(
                "select type, name, sql from sqlite_master where sql is not null and name not like 'sqlite_%' and name not like 'flyway_%' order by type, name"
            )
            .joinToString("\n") { row ->
                "-- ${row["type"]} ${row["name"]}\n${normalize(row["sql"].toString())};"
            }
            .plus("\n")

    private fun normalize(sql: String): String =
        sql.lines().joinToString(" ") { it.trim() }.replace(Regex("\\s+"), " ")

    companion object {
        private const val GOLDEN = "/schema/golden-v1.sql"
        private val ACTUAL: Path = Path.of("build", "schema-actual.sql")
    }
}
