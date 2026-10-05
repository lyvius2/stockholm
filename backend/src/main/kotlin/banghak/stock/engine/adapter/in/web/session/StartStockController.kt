package banghak.stock.engine.adapter.`in`.web.session

import banghak.stock.core.domain.account.Principal
import banghak.stock.core.domain.trading.StartStock
import banghak.stock.core.usecase.LookupStartStockUseCase
import banghak.stock.core.usecase.RecordLastViewedStockUseCase
import banghak.stock.engine.adapter.`in`.web.common.SymbolDto
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class StartStockResponse(val symbol: SymbolDto, val reason: String) {
    companion object {
        fun of(startStock: StartStock) =
            StartStockResponse(SymbolDto.of(startStock.symbol), startStock.reason.name)
    }
}

data class LastViewedStockRequest(val symbol: SymbolDto = SymbolDto())

/**
 * 로그인 직후 네 영역에 띄울 종목(F19)과 직전에 본 종목 기록.
 * 화면이 종목 전환을 2초 디바운스로 보내므로 본 시각은 데몬 시계로 찍음.
 */
@RestController
@RequestMapping("/session")
@Profile(RuntimeProfiles.ENGINE)
class StartStockController(
    private val startStocks: LookupStartStockUseCase,
    private val lastViewed: RecordLastViewedStockUseCase,
    private val clock: Clock,
) {
    @GetMapping("/start-stock")
    fun startStock(principal: Principal): StartStockResponse =
        StartStockResponse.of(startStocks.startStock(principal.userId))

    @PutMapping("/last-viewed-stock")
    fun recordLastViewed(principal: Principal, @RequestBody request: LastViewedStockRequest) {
        lastViewed.record(
            principal.userId,
            principal.session.deviceId,
            request.symbol.toSymbol(),
            clock.instant(),
        )
    }
}
