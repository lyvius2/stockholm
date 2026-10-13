import { useQuery } from '@tanstack/react-query'
import type { ApiChart, ApiChartBar, Resolution } from '@renderer/generated/api-chart'
import { marketApi } from '@renderer/data/api/market'
import { localClient } from '@renderer/data/client/LocalClient'
import type { StreamSymbol } from '@renderer/data/stream/MarketStream'
import { useLiveMinutes } from '@renderer/data/stream/useLivePrices'
import { useIsFeedDelayed } from '@renderer/data/stream/useMarketStream'
import { isIntraday, mergeLiveMinutes, visibleBars, type RangeRequest } from './chartData'

/** 닫힌 봉의 정확한 값은 다시 받아야 함(진행 봉은 근사값). 분봉은 1분, 그 밖은 5분마다. */
export const INTRADAY_REFRESH_MS = 60_000
export const DAILY_REFRESH_MS = 5 * 60_000

export interface ChartData {
  readonly chart: ApiChart | undefined
  /** 그릴 봉: 세션 날짜로 거르고 진행 중인 1분봉을 합친 것. */
  readonly bars: readonly ApiChartBar[]
  readonly isError: boolean
  /** 데몬이 저장해 둔 봉을 준 것이거나 스트림이 끊김. */
  readonly isDelayed: boolean
}

export function chartQueryKey(symbol: StreamSymbol, resolution: Resolution, count: number) {
  return ['market', 'chart', symbol.market, symbol.code, resolution, count] as const
}

/** 한 종목·한 기간의 차트. 조회 결과에 스트림의 진행 봉들을 얹어 마지막 봉이 체결마다 움직이게 함. */
export function useChartData(symbol: StreamSymbol, request: RangeRequest): ChartData {
  const query = useQuery({
    queryKey: chartQueryKey(symbol, request.resolution, request.count),
    queryFn: () =>
      marketApi(localClient).chart({
        symbol,
        resolution: request.resolution,
        count: request.count,
      }),
    refetchInterval: isIntraday(request.resolution) ? INTRADAY_REFRESH_MS : DAILY_REFRESH_MS,
    retry: false,
  })
  const minutes = useLiveMinutes(symbol)
  const isFeedDelayed = useIsFeedDelayed(symbol)
  const chart = query.data
  // 조회 응답에 든 분까지는 REST 값을 믿고, 그 뒤에 받은 진행 분들만 얹음
  const bars =
    chart === undefined
      ? []
      : mergeLiveMinutes(
          visibleBars(chart, request),
          minutes,
          request.resolution,
          query.dataUpdatedAt,
        )
  return {
    chart,
    bars,
    isError: query.isError,
    isDelayed: isFeedDelayed || chart?.isDelayed === true,
  }
}
