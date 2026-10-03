package banghak.stock.engine.application.account

import banghak.stock.core.domain.account.LastViewedStock
import banghak.stock.core.domain.account.UserSetting
import banghak.stock.core.domain.account.UserSettingCodec
import banghak.stock.core.domain.account.UserSettingKey
import banghak.stock.core.domain.eventlog.UserSettingChanged
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.port.EventStore
import banghak.stock.core.port.UserSettingsPort
import banghak.stock.core.usecase.LookupUserSettingsUseCase
import banghak.stock.core.usecase.RecordLastViewedStockUseCase
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import java.time.Instant
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 사용자 설정을 이벤트(동기화 대상)와 projection 에 같은 트랜잭션으로 남김. */
@Service
@Profile(RuntimeProfiles.ENGINE)
class UserSettingsService(
    private val settings: UserSettingsPort,
    private val events: EventStore,
    private val clock: Clock,
) : RecordLastViewedStockUseCase, LookupUserSettingsUseCase {
    @Transactional
    override fun record(userId: UserId, deviceId: DeviceId, symbol: Symbol, viewedAt: Instant) =
        change(
            userId,
            deviceId,
            UserSettingKey.LAST_VIEWED_STOCK,
            UserSettingCodec.lastViewedStock(LastViewedStock(symbol, viewedAt)),
        )

    @Transactional(readOnly = true)
    override fun lastViewedStock(userId: UserId): LastViewedStock? =
        settings.find(userId, UserSettingKey.LAST_VIEWED_STOCK)?.let {
            UserSettingCodec.parseLastViewedStock(it.value)
        }

    @Transactional(readOnly = true)
    override fun defaultMarket(userId: UserId): Market =
        settings.find(userId, UserSettingKey.DEFAULT_MARKET)?.let {
            UserSettingCodec.parseDefaultMarket(it.value)
        } ?: Market.KR

    private fun change(userId: UserId, deviceId: DeviceId, key: UserSettingKey, value: String) {
        val now = clock.instant()
        settings.save(UserSetting(userId, key, value, now), deviceId)
        events.append(userId, deviceId, listOf(UserSettingChanged(key.name, value)), now)
    }
}
