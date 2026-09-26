package banghak.stock.engine.adapter.out.persistence

import banghak.stock.shared.config.RuntimeProfiles
import banghak.stock.shared.config.StockholmProperties
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import jakarta.persistence.EntityManagerFactory
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DataSourceConnectionProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource

/**
 * engine 의 SQLite 영속성 조립. Boot 자동 구성 대신 명시적으로 둠(relay 가 별도 DataSource 를 갖기 때문). 같은 파일에 쓰기 풀(1개)과 읽기
 * 풀(여러 개, 읽기 전용 연결)을 두고 트랜잭션의 readOnly 로 고름. 쓰기 연결이 하나뿐인 것이 주문·lease·노출액 계산의 직렬화 지점임.
 */
@Configuration
@Profile(RuntimeProfiles.ENGINE)
@EnableTransactionManagement
@EnableConfigurationProperties(DbProperties::class)
@EnableJpaRepositories(
    basePackages = ["banghak.stock.engine.adapter.out.persistence.repository"],
    entityManagerFactoryRef = "engineEntityManagerFactory",
    transactionManagerRef = "engineTransactionManager",
)
class SqliteConfig {
    @Bean
    fun engineWriteDataSource(stockholm: StockholmProperties, db: DbProperties): HikariDataSource =
        pool(
            "engine-write",
            databasePath(stockholm, db),
            readOnly = false,
            maxPoolSize = WRITE_POOL_SIZE,
        )

    /** 파일과 스키마가 있어야 읽기 전용으로 열 수 있으므로 Flyway 뒤에 만듦. */
    @Bean
    fun engineReadDataSource(
        stockholm: StockholmProperties,
        db: DbProperties,
        engineFlyway: Flyway,
    ): HikariDataSource =
        pool(
            "engine-read",
            databasePath(stockholm, db),
            readOnly = true,
            maxPoolSize = db.readPoolSize,
        )

    @Bean
    @Primary
    fun engineDataSource(
        engineWriteDataSource: HikariDataSource,
        engineReadDataSource: HikariDataSource,
    ): DataSource =
        LazyConnectionDataSourceProxy(
            ReadWriteRoutingDataSource(engineWriteDataSource, engineReadDataSource)
        )

    @Bean
    fun engineFlyway(engineWriteDataSource: HikariDataSource, db: DbProperties): Flyway =
        Flyway.configure()
            .dataSource(engineWriteDataSource)
            .locations("classpath:db/engine/common", "classpath:db/engine/sqlite")
            .placeholders(db.placeholders)
            .load()
            .also { it.migrate() }

    @Bean
    fun engineEntityManagerFactory(
        engineDataSource: DataSource,
        engineFlyway: Flyway,
    ): LocalContainerEntityManagerFactoryBean =
        LocalContainerEntityManagerFactoryBean().apply {
            dataSource = engineDataSource
            setPackagesToScan(ENTITY_PACKAGE, CONVERTER_PACKAGE)
            jpaVendorAdapter = HibernateJpaVendorAdapter()
            setJpaPropertyMap(
                mapOf(
                    "hibernate.dialect" to "org.hibernate.community.dialect.SQLiteDialect",
                    "hibernate.hbm2ddl.auto" to "none",
                )
            )
        }

    @Bean
    fun engineTransactionManager(
        engineEntityManagerFactory: EntityManagerFactory
    ): PlatformTransactionManager = JpaTransactionManager(engineEntityManagerFactory)

    /** Join·Bulk 전용 jOOQ. JPA 와 같은 DataSource·트랜잭션을 씀. */
    @Bean
    fun engineDsl(engineDataSource: DataSource): DSLContext =
        DSL.using(
            DataSourceConnectionProvider(TransactionAwareDataSourceProxy(engineDataSource)),
            SQLDialect.SQLITE,
        )

    private fun databasePath(stockholm: StockholmProperties, db: DbProperties): Path {
        Files.createDirectories(stockholm.dataDir)
        return stockholm.dataDir.resolve(db.fileName)
    }

    private fun pool(
        name: String,
        path: Path,
        readOnly: Boolean,
        maxPoolSize: Int,
    ): HikariDataSource {
        val sqlite =
            SQLiteDataSource(
                SQLiteConfig().apply {
                    setJournalMode(SQLiteConfig.JournalMode.WAL)
                    setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
                    enforceForeignKeys(true)
                    setBusyTimeout(BUSY_TIMEOUT_MILLIS)
                    setReadOnly(readOnly)
                }
            )
        sqlite.url = "jdbc:sqlite:$path"
        return HikariDataSource(
            HikariConfig().apply {
                poolName = name
                dataSource = sqlite
                maximumPoolSize = maxPoolSize
                // SQLite 는 연결 뒤 read-only 플래그를 못 바꾸므로 풀의 플래그를 연결과 같게 맞춤
                isReadOnly = readOnly
            }
        )
    }

    companion object {
        private const val ENTITY_PACKAGE = "banghak.stock.engine.adapter.out.persistence.entity"
        private const val CONVERTER_PACKAGE =
            "banghak.stock.engine.adapter.out.persistence.converter"
        private const val WRITE_POOL_SIZE = 1
        private const val BUSY_TIMEOUT_MILLIS = 5_000
    }
}
