package banghak.stock.engine.adapter.out.persistence

import banghak.stock.support.EngineDatabaseTest
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** SQLite 연결 초기화(WAL·FK·읽기 전용 풀)와 FK 정책이 실제로 동작함. */
class SqliteConnectionTest : EngineDatabaseTest() {
    @Autowired private lateinit var engineWriteDataSource: HikariDataSource
    @Autowired private lateinit var engineReadDataSource: HikariDataSource
    @Autowired private lateinit var engineDataSource: DataSource
    @Autowired private lateinit var engineTransactionManager: PlatformTransactionManager
    @Autowired private lateinit var dsl: DSLContext

    private val write by lazy { JdbcTemplate(engineWriteDataSource) }
    private val read by lazy { JdbcTemplate(engineReadDataSource) }

    @Test
    @DisplayName("쓰기 풀은 1개, 읽기 풀은 설정값이며 두 연결 모두 WAL 과 foreign_keys 가 켜져 있음")
    fun poolsAreInitializedWithPragmas() {
        assertThat(engineWriteDataSource.maximumPoolSize).isEqualTo(1)
        assertThat(engineReadDataSource.maximumPoolSize).isEqualTo(4)
        for (template in listOf(write, read)) {
            assertThat(template.queryForObject("pragma journal_mode", String::class.java))
                .isEqualToIgnoringCase("wal")
            assertThat(template.queryForObject("pragma foreign_keys", Int::class.java)).isEqualTo(1)
        }
    }

    @Test
    @DisplayName("읽기 전용 트랜잭션은 읽기 풀로 가서 쓰기가 거부됨")
    fun readOnlyTransactionCannotWrite() {
        val routed = JdbcTemplate(engineDataSource)
        val readOnly = TransactionTemplate(engineTransactionManager).apply { isReadOnly = true }
        assertThatThrownBy {
                readOnly.executeWithoutResult { routed.update(INSERT_DEVICE, "d_read") }
            }
            .hasMessageContaining("readonly")
        val writable = TransactionTemplate(engineTransactionManager)
        writable.executeWithoutResult { routed.update(INSERT_DEVICE, "d_write") }
        assertThat(read.queryForObject("select count(*) from device", Int::class.java)).isEqualTo(1)
    }

    @Test
    @DisplayName("jOOQ 는 같은 DataSource 로 SQLite 방언으로 질의함")
    fun jooqQueriesThroughSharedDataSource() {
        write.update(INSERT_DEVICE, "d_jooq")
        val count =
            dsl.selectCount()
                .from(DSL.table("device"))
                .where(DSL.field("device_id").eq("d_jooq"))
                .fetchOne(0, Int::class.java)
        assertThat(count).isEqualTo(1)
        assertThat(dsl.dialect()).isEqualTo(SQLDialect.SQLITE)
    }

    @Test
    @DisplayName("물리 FK 가 있는 표는 부모 삭제 시 함께 지워짐")
    fun cascadeDeletesChildRows() {
        write.update(INSERT_USER, "u_cascade")
        write.update(
            "insert into recovery_code (recovery_code_id, user_id, code_hash, created_at) values ('r1', 'u_cascade', 'h', '2026-09-27T00:00:00.000Z')"
        )
        write.update("delete from app_user where user_id = 'u_cascade'")
        assertThat(write.queryForObject("select count(*) from recovery_code", Int::class.java))
            .isZero()
    }

    @Test
    @DisplayName("물리 FK 가 없는 이벤트 로그는 부모 없는 사용자 ID 로도 들어감")
    fun eventLogAcceptsUnknownUser() {
        write.update(
            "insert into event_log (user_id, device_id, seq, occurred_at, type, payload_json, sync_scope) values ('u_ghost', 'd_ghost', 1, '2026-09-27T00:00:00.000Z', 'ChartModeChanged', '{}', 'USER')"
        )
        assertThat(
                write.queryForObject(
                    "select count(*) from event_log where user_id = 'u_ghost'",
                    Int::class.java,
                )
            )
            .isEqualTo(1)
    }

    @Test
    @DisplayName("발생 시각 범위 조회는 (user_id, occurred_at) 인덱스를 탐")
    fun occurredAtRangeUsesIndex() {
        val plan =
            write.queryForList(
                "explain query plan select * from event_log where user_id = 'u' and occurred_at between '2026-01-01T00:00:00.000Z' and '2026-12-31T23:59:59.999Z'"
            )
        assertThat(plan.joinToString { it["detail"].toString() })
            .contains("idx_event_log_user_time")
    }

    companion object {
        private const val INSERT_DEVICE =
            "insert into device (device_id, public_key, name, registered_at) values (?, 'pk', 'mac', '2026-09-27T00:00:00.000Z')"
        private const val INSERT_USER =
            "insert into app_user (user_id, role, display_name, password_hash, status, toss_key_decision, created_at, updated_at) values (?, 'ADMIN', 'w', 'h', 'ACTIVE', 'NONE', '2026-09-27T00:00:00.000Z', '2026-09-27T00:00:00.000Z')"
    }
}
