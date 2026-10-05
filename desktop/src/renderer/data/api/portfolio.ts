import type { ApiPortfolioValuation } from '@renderer/generated/api-portfolio-valuation'
import type { ApiStartStock } from '@renderer/generated/api-start-stock'
import type { Client } from '../client/Client'
import type { StreamSymbol } from '../stream/MarketStream'

/** 본인 계좌의 평가(F18)와 시작 종목·직전 종목(F19). 세션 사용자 것만 옴. */
export function portfolioApi(client: Client) {
  return {
    valuation: () => client.request<ApiPortfolioValuation>('GET', '/portfolio/valuation'),
    startStock: () => client.request<ApiStartStock>('GET', '/session/start-stock'),
    recordLastViewed: (symbol: StreamSymbol) =>
      client.request<null>('PUT', '/session/last-viewed-stock', { symbol }),
  }
}
