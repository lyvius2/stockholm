package banghak.stock.engine.application.market

import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.Candle
import banghak.stock.core.domain.trading.LiveCandle
import banghak.stock.core.domain.trading.TradeTick
import banghak.stock.core.port.FeedListener
import banghak.stock.core.port.RealtimeFeedPort
import banghak.stock.core.usecase.LookupLiveCandleUseCase
import banghak.stock.shared.config.RuntimeProfiles
import jakarta.annotation.PostConstruct
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 실시간 체결을 종목별 진행 중인 1분봉으로 모음.
 * 메모리에만 두고 저장하지 않음(시세 채널은 체결이 빠질 수 있어 근사값임).
 * 닫힌 봉의 정확한 값은 차트 조회가 토스 봉으로 다시 받음.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class LiveCandleService(private val feed: RealtimeFeedPort, private val clock: Clock) :
    FeedListener, LookupLiveCandleUseCase {
    // 체결은 연결마다 다른 스레드에서 올 수 있어 종목별 갱신을 원자적으로 함
    private val live = ConcurrentHashMap<Symbol, LiveCandle>()

    @PostConstruct fun listen() = feed.addListener(this)

    override fun onTrade(tick: TradeTick) {
        live.compute(tick.symbol) { _, current ->
            current?.accept(tick)?.current ?: LiveCandle.start(tick)
        }
    }

    override fun liveCandle(symbol: Symbol): Candle? =
        live[symbol]?.takeIf { it.isCurrentAt(clock.instant()) }?.candle
}
