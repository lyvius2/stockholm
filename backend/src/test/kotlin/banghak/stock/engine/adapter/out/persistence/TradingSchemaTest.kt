package banghak.stock.engine.adapter.out.persistence

import banghak.stock.support.EngineDatabaseTest
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate

/** V2 주문·보유·알림 표의 FK 정책·유일 제약·인덱스가 설계대로 동작함. */
class TradingSchemaTest : EngineDatabaseTest() {
    @Autowired private lateinit var engineWriteDataSource: HikariDataSource

    private val write by lazy { JdbcTemplate(engineWriteDataSource) }

    @Test
    @DisplayName("사용자를 지우면 lot 과 그 lot 의 소진 기록이 함께 지워짐")
    fun deletingUserCascadesLotsAndDisposals() {
        write.update(INSERT_USER, "u_lot")
        write.update(INSERT_LOT, "l_1", "u_lot")
        write.update(INSERT_DISPOSAL, "x_1", "u_lot", "l_1")

        write.update("delete from app_user where user_id = 'u_lot'")

        assertThat(count("lot where lot_id = 'l_1'")).isZero()
        assertThat(count("lot_disposal where disposal_id = 'x_1'")).isZero()
    }

    @Test
    @DisplayName("증권사 주문 캐시와 알림은 FK 가 없어 등록부에 없는 사용자로도 들어가고, 사용자를 지워도 남음")
    fun cacheAndNotificationHaveNoForeignKey() {
        write.update(INSERT_ORDER, "o_ghost", "u_ghost")
        write.update(INSERT_NOTIFICATION, "n_ghost", "u_ghost", "k1")

        assertThat(count("broker_order where broker_order_id = 'o_ghost'")).isEqualTo(1)
        assertThat(count("notification where notification_id = 'n_ghost'")).isEqualTo(1)
    }

    @Test
    @DisplayName("같은 사용자·종류·중복 키의 알림은 한 번만 들어감")
    fun notificationIsDeduplicated() {
        write.update(INSERT_NOTIFICATION, "n_1", "u_dup", "same")

        assertThatThrownBy { write.update(INSERT_NOTIFICATION, "n_2", "u_dup", "same") }
            .isInstanceOf(DataAccessException::class.java)
            // SQLite 드라이버의 오류 코드는 Spring 이 번역하지 못해 메시지로 제약 종류를 확인함
            .hasMessageContaining("UNIQUE constraint failed")
        write.update(INSERT_NOTIFICATION, "n_3", "u_other", "same")
    }

    @Test
    @DisplayName("봉 범위 조회는 복합 PK 인덱스로, 사용자 주문·손익 조회는 user_id 로 시작하는 인덱스로 됨")
    fun rangeQueriesUseIndexes() {
        assertThat(
                plan(
                    "select * from candle where market = 'KR' and code = '005930' and interval = 'M1' and open_time between '2026-09-01T00:00:00.000Z' and '2026-09-30T00:00:00.000Z'"
                )
            )
            .contains("sqlite_autoindex_candle_1")
        assertThat(
                plan(
                    "select * from broker_order where user_id = 'u' and market = 'KR' and code = '005930' and ordered_at >= '2026-09-01T00:00:00.000Z'"
                )
            )
            .contains("idx_broker_order_user_symbol_time")
        assertThat(
                plan(
                    "select * from lot_disposal where user_id = 'u' and disposed_at between '2026-01-01T00:00:00.000Z' and '2026-12-31T00:00:00.000Z'"
                )
            )
            .contains("idx_lot_disposal_user_time")
    }

    private fun count(fromWhere: String): Int =
        write.queryForObject("select count(*) from $fromWhere", Int::class.java) ?: 0

    private fun plan(sql: String): String =
        write.queryForList("explain query plan $sql").joinToString { it["detail"].toString() }

    companion object {
        private const val NOW = "'2026-09-30T00:00:00.000Z'"
        private const val INSERT_USER =
            "insert into app_user (user_id, role, display_name, password_hash, status, toss_key_decision, created_at, updated_at) values (?, 'ADMIN', 'w', 'h', 'ACTIVE', 'NONE', $NOW, $NOW)"
        private const val INSERT_LOT =
            "insert into lot (lot_id, user_id, market, code, bought_quantity, remaining_quantity, unit_cost_amount, unit_cost_currency, bought_at, origin, created_at, updated_at) values (?, ?, 'KR', '005930', '10', '10', '70000', 'KRW', $NOW, 'MANUAL', $NOW, $NOW)"
        private const val INSERT_DISPOSAL =
            "insert into lot_disposal (disposal_id, user_id, lot_id, broker_order_id, market, code, quantity, sell_price_amount, sell_price_currency, buy_unit_cost_amount, buy_unit_cost_currency, fee_amount, tax_amount, realized_amount, realized_currency, holding_days, lot_origin, disposed_at, created_at) values (?, ?, ?, 'B-1', 'KR', '005930', '5', '80000', 'KRW', '70000', 'KRW', '0', '0', '50000', 'KRW', 3, 'MANUAL', $NOW, $NOW)"
        private const val INSERT_ORDER =
            "insert into broker_order (broker_order_id, user_id, market, code, side, kind, time_in_force, status, filled_quantity, origin, trigger_type, ordered_at, updated_at, fetched_at) values (?, ?, 'KR', '005930', 'BUY', 'LIMIT', 'DAY', 'PENDING', '0', 'MANUAL', 'ManualTrigger', $NOW, $NOW, $NOW)"
        private const val INSERT_NOTIFICATION =
            "insert into notification (notification_id, user_id, kind, dedupe_key, title, created_at) values (?, ?, 'FILL', ?, 't', $NOW)"
    }
}
