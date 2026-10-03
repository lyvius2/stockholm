package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.account.UserSetting
import banghak.stock.core.domain.account.UserSettingKey
import banghak.stock.core.domain.identity.Ulid
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.core.port.UserSettingsPort
import banghak.stock.support.EngineDatabaseTest
import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/** 사용자 설정이 사용자·키마다 한 행이고 다른 사용자에게 보이지 않음. */
class UserSettingsPersistenceTest : EngineDatabaseTest() {
    @Autowired private lateinit var store: UserSettingsPort
    @Autowired private lateinit var engineWriteDataSource: HikariDataSource

    private val write by lazy { JdbcTemplate(engineWriteDataSource) }
    private val user = TradingFixtures.user
    private val other = UserId.from(Ulid.of(TradingFixtures.now, ByteArray(10) { 9 }))
    private val at = Instant.parse("2026-10-02T00:00:00Z")

    @BeforeEach
    fun clearTables() {
        write.update("delete from user_setting")
        write.update("delete from app_user")
        listOf(user, other).forEach {
            write.update(
                "insert into app_user (user_id, role, display_name, password_hash, status, toss_key_decision, created_at, updated_at) values (?, 'ADMIN', 'w', 'h', 'ACTIVE', 'NONE', '2026-10-02T00:00:00.000Z', '2026-10-02T00:00:00.000Z')",
                it.value,
            )
        }
    }

    @Test
    @DisplayName("같은 키는 덮어쓰고, 갱신 시각과 디바이스가 남으며, 다른 사용자의 설정은 보이지 않음")
    fun overwritesPerUserAndKey() {
        store.save(setting(user, """{"market":"KR"}""", at), TradingFixtures.device)
        store.save(setting(user, """{"market":"US"}""", at.plusSeconds(60)), TradingFixtures.device)

        assertThat(store.find(user, UserSettingKey.DEFAULT_MARKET))
            .isEqualTo(setting(user, """{"market":"US"}""", at.plusSeconds(60)))
        assertThat(store.find(other, UserSettingKey.DEFAULT_MARKET)).isNull()
        assertThat(store.find(user, UserSettingKey.LAST_VIEWED_STOCK)).isNull()
        assertThat(write.queryForObject("select count(*) from user_setting", Int::class.java))
            .isEqualTo(1)
        assertThat(
                write.queryForObject(
                    "select updated_by_device_id from user_setting",
                    String::class.java,
                )
            )
            .isEqualTo(TradingFixtures.device.value)
    }

    private fun setting(userId: UserId, value: String, updatedAt: Instant) =
        UserSetting(userId, UserSettingKey.DEFAULT_MARKET, value, updatedAt)
}
