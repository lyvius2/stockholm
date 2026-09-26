package banghak.stock.engine.adapter.out.persistence

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * engine DB 설정.
 *
 * @property fileName 앱 데이터 폴더 안의 SQLite 파일 이름
 * @property readPoolSize 읽기 전용 풀 크기. 쓰기 풀은 항상 1(SQLite 쓰기 단일)
 * @property placeholders Flyway 자리표시자 값. 벤더별 형식(TEXT·INTEGER …)
 */
@ConfigurationProperties("stockholm.db")
data class DbProperties(
    val fileName: String = "stockholm.db",
    val readPoolSize: Int = 4,
    val placeholders: Map<String, String> = emptyMap(),
)
