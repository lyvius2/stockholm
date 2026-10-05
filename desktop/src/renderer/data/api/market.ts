import type { ApiChart, Resolution } from '@renderer/generated/api-chart'
import type { ApiIndexTicker } from '@renderer/generated/api-index-ticker'
import type { ApiMoverBoard, Market } from '@renderer/generated/api-mover-board'
import type { ApiStockSummary } from '@renderer/generated/api-stock-summary'
import type { Client } from '../client/Client'
import type { StreamSymbol } from '../stream/MarketStream'

export interface ChartRequest {
  readonly symbol: StreamSymbol
  readonly resolution: Resolution
  readonly before?: string
  readonly count?: number
}

/** 공용 시세·종목 마스터 API. 사용자 데이터가 아니라 세션만 있으면 같은 값을 봄. */
export function marketApi(client: Client) {
  return {
    chart: ({ symbol, resolution, before, count }: ChartRequest) =>
      client.request<ApiChart>(
        'GET',
        `/market/chart?${query({ market: symbol.market, code: symbol.code, resolution, before, count })}`,
      ),
    movers: (market: Market) =>
      client.request<ApiMoverBoard>('GET', `/market/movers?${query({ market })}`),
    indexTicker: () => client.request<ApiIndexTicker>('GET', '/market/index-ticker'),
    searchStocks: (text: string, limit?: number) =>
      client.request<ApiStockSummary[]>('GET', `/stocks?${query({ query: text, limit })}`),
    findStock: (symbol: StreamSymbol) =>
      client.request<ApiStockSummary>(
        'GET',
        `/stocks/${encodeURIComponent(symbol.market)}/${encodeURIComponent(symbol.code)}`,
      ),
  }
}

/** undefined 인 항목은 빼고 쿼리 문자열을 만듦. */
export function query(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) search.set(key, String(value))
  }
  return search.toString()
}
