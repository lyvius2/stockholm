package banghak.stock.core.usecase

import banghak.stock.core.domain.account.LastViewedStock
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.StartStock
import java.time.Instant

/**
 * 로그인 직후 네 영역에 띄울 종목(F19).
 * 후보마다 외부 조회는 2초 안에 답이 없으면 포기하고 다음으로 감.
 */
interface LookupStartStockUseCase {
    /** 항상 값을 돌려줌(마지막 후보는 상수). */
    fun startStock(userId: UserId): StartStock
}

/**
 * 네 영역의 종목이 바뀌었음을 기록함(시작 종목 ⑴).
 * 화면이 2초 디바운스로 보내므로 데몬은 그대로 저장함.
 */
interface RecordLastViewedStockUseCase {
    fun record(userId: UserId, deviceId: DeviceId, symbol: Symbol, viewedAt: Instant)
}

/** 시작 종목 규칙이 읽는 사용자 설정. */
interface LookupUserSettingsUseCase {
    fun lastViewedStock(userId: UserId): LastViewedStock?

    /** 설정이 없으면 국내. */
    fun defaultMarket(userId: UserId): Market
}
