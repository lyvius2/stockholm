package banghak.stock.core.usecase

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.portfolio.PortfolioValuation

/**
 * 보유주식 평가금액 패널(F18).
 * 본인 토스 키로 본인 계좌만 봄.
 */
interface LookupPortfolioValuationUseCase {
    /**
     * 보유를 증권사에서 받고 현재가·전일 종가·환율로 평가함.
     * 현재가·환율·전일 종가는 받지 못해도 증권사 값이나 빈 값으로 두고 지연을 표시함.
     *
     * @throws banghak.stock.core.domain.error.BrokerUnavailableException 보유를 받지 못하면 발생함
     */
    fun valuation(userId: UserId): PortfolioValuation
}
