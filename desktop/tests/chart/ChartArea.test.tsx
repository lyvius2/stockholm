import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ApiRequest } from '../../src/preload/bridge'
import { useChartViewStore } from '@renderer/data/store/chartView'
import { useStockStore } from '@renderer/data/store/stock'
import { useStreamStore } from '@renderer/data/stream/store'
import { ChartArea } from '@renderer/features/chart/ChartArea'
import { installBridge, ok, status } from '../support/bridge'

// vi.mock 은 파일 맨 위로 끌어올려지므로 가짜 모듈도 함께 끌어올려 같은 인스턴스를 씀
const { createdCharts, fakeLightweightCharts } = await vi.hoisted(
  () => import('../support/fakeLightweightCharts'),
)
vi.mock('lightweight-charts', () => fakeLightweightCharts())

const samsung = { market: 'KR', code: '005930' } as const
const money = (amount: string) => ({ amount, currency: 'KRW' as const })

function bar(openTime: string, close: string) {
  return {
    openTime,
    open: money(close),
    high: money(close),
    low: money(close),
    close: money(close),
    volume: '10',
  }
}

function chartBody(resolution: string, bars: ReturnType<typeof bar>[]) {
  return {
    symbol: samsung,
    resolution,
    bars,
    closeAverages: { '5': bars.map(() => null), '20': [], '60': [], '120': [] },
    volumeAverage: bars.map(() => null),
    nextBefore: null,
    isDelayed: false,
  }
}

describe('ChartArea', () => {
  const requests: ApiRequest[] = []

  beforeEach(() => {
    requests.length = 0
    createdCharts.length = 0
    useStockStore.getState().reset()
    useStreamStore.getState().reset()
    useChartViewStore.getState().reset()
    installBridge((request) => {
      requests.push(request)
      if (request.path === '/session/chart-view' && request.method === 'GET')
        return ok({ isDetailed: false, simpleRange: 'INTRADAY', detailedResolution: 'DAY' })
      if (request.path === '/session/chart-view' && request.method === 'PUT')
        return ok(request.body)
      if (request.path.startsWith('/market/chart')) {
        const resolution = new URL(`http://x${request.path}`).searchParams.get('resolution') ?? ''
        const bars = [
          bar('2026-10-05T00:00:00Z', '100'),
          bar('2026-10-06T00:00:00Z', '110'),
          bar('2026-10-06T00:01:00Z', '105'),
        ]
        // 같은 조건을 다시 받으면 닫힌 봉이 하나 더 온 것으로 침(REST 보정)
        const isRefetch = requests.filter((r) => r.path === request.path).length > 1
        return ok(
          chartBody(resolution, isRefetch ? [...bars, bar('2026-10-06T00:02:00Z', '103')] : bars),
        )
      }
      return status(404)
    })
    act(() => {
      void useChartViewStore.getState().load()
      useStockStore.getState().select(samsung)
      useStreamStore.getState().setConnection('open')
      useStreamStore.getState().setWatched([samsung])
      useStreamStore
        .getState()
        .applyMessages([{ type: 'feedState', feedState: { isLive: true, unavailableSymbols: [] } }])
    })
  })

  function renderArea() {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const rendered = render(
      <QueryClientProvider client={client}>
        <ChartArea />
      </QueryClientProvider>,
    )
    return { ...rendered, client }
  }

  function chartRequests() {
    return requests.filter((r) => r.path.startsWith('/market/chart')).map((r) => r.path)
  }

  it('간단 모드는 장중 탭의 1분봉을 선 하나로 그리고 오늘 봉만 남기며 실시간 칩을 보임', async () => {
    renderArea()
    await waitFor(() => expect(createdCharts[0]?.series[0]?.setData).toHaveBeenCalled())
    expect(chartRequests()[0]).toContain('resolution=MINUTE_1')
    expect(screen.getByRole('tab', { name: '장중' }).getAttribute('aria-selected')).toBe('true')
    expect(screen.getByText('실시간')).toBeDefined()
    expect(screen.getByLabelText('차트 라이브러리 귀속').textContent).toContain('TradingView')

    const line = createdCharts[0]?.series[0]
    expect(line?.kind).toBe('Line')
    const points = line?.setData.mock.calls[0]?.[0] as { value: number }[]
    expect(points.map((p) => p.value)).toEqual([110, 105])
    expect(line?.createPriceLine).toHaveBeenCalledWith(expect.objectContaining({ price: 105 }))
    expect(createdCharts[0]?.markers[0]).toHaveBeenLastCalledWith([
      expect.objectContaining({ text: '최고 110' }),
      expect.objectContaining({ text: '최저 105' }),
    ])
  })

  it('진행 봉이 오면 마지막 점만 update 하고, 스트림이 끊기면 지연 칩', async () => {
    // 조회 시각을 봉 시각 근처로 고정해 그 뒤의 진행 분이 합쳐지게 함(타이머는 실제로 둠)
    vi.useFakeTimers({ toFake: ['Date'], now: Date.parse('2026-10-06T00:01:20Z') })
    renderArea()
    await waitFor(() => expect(createdCharts[0]?.series[0]?.setData).toHaveBeenCalled())
    act(() =>
      useStreamStore.getState().applyMessages([
        {
          type: 'liveCandle',
          symbol: samsung,
          liveCandle: {
            openTime: '2026-10-06T00:01:30Z',
            open: money('105'),
            high: money('108'),
            low: money('104'),
            close: money('107'),
            volume: '3',
          },
        },
      ]),
    )
    const line = createdCharts[0]?.series[0]
    expect(line?.update).toHaveBeenCalledWith(expect.objectContaining({ value: 107 }))
    expect(line?.setData).toHaveBeenCalledTimes(1)

    act(() => useStreamStore.getState().setConnection('closed'))
    expect(screen.getByText('지연')).toBeDefined()
    vi.useRealTimers()
  })

  it('REST 를 다시 받으면 전부 다시 그리되 확대·이동은 건드리지 않음', async () => {
    const { client } = renderArea()
    await waitFor(() => expect(createdCharts[0]?.series[0]?.setData).toHaveBeenCalled())
    const chart = createdCharts[0]
    expect(chart?.fitContent).toHaveBeenCalledTimes(1)

    await act(() => client.invalidateQueries({ queryKey: ['market', 'chart'] }))
    await waitFor(() => expect(chart?.series[0]?.setData).toHaveBeenCalledTimes(2))
    const points = chart?.series[0]?.setData.mock.calls[1]?.[0] as { value: number }[]
    expect(points.map((p) => p.value)).toEqual([110, 105, 103])
    expect(chart?.fitContent).toHaveBeenCalledTimes(1)
    expect(createdCharts).toHaveLength(1)
  })

  it('토글을 켜면 캔들·이동평균 4개·거래량으로 바뀌고 설정이 저장되며, 기간 탭은 모드별로 기억함', async () => {
    renderArea()
    await waitFor(() => expect(useChartViewStore.getState().isLoaded).toBe(true))
    fireEvent.click(screen.getByRole('tab', { name: '1년' }))
    await waitFor(() => expect(chartRequests().at(-1)).toContain('resolution=DAY'))
    expect(chartRequests().at(-1)).toContain('count=250')

    // 장중(1분봉) → 1년(일봉) → 자세한 차트(일봉): 봉 단위나 모드가 바뀔 때마다 차트를 새로 만듦
    fireEvent.click(screen.getByLabelText('자세한 차트'))
    await waitFor(() => expect(createdCharts).toHaveLength(3))
    const detailed = createdCharts.at(-1)
    expect(detailed?.series.map((s) => s.kind)).toEqual([
      'Candlestick',
      'Line',
      'Line',
      'Line',
      'Line',
      'Histogram',
      'Line',
    ])
    expect(screen.getByLabelText('이동평균선').textContent).toContain('120')
    expect(screen.getByRole('tab', { name: '일' }).getAttribute('aria-selected')).toBe('true')

    fireEvent.change(screen.getByLabelText('분봉'), { target: { value: 'MINUTE_10' } })
    await waitFor(() => expect(chartRequests().at(-1)).toContain('resolution=MINUTE_10'))

    const saves = requests.filter((r) => r.path === '/session/chart-view' && r.method === 'PUT')
    expect(saves.at(-1)?.body).toEqual({
      isDetailed: true,
      simpleRange: 'YEAR_1',
      detailedResolution: 'MINUTE_10',
    })

    fireEvent.click(screen.getByLabelText('자세한 차트'))
    expect(screen.getByRole('tab', { name: '1년' }).getAttribute('aria-selected')).toBe('true')
  })

  it('설정을 읽기 전의 변경은 저장하지 않아 옛 설정을 기본값으로 덮지 않음', async () => {
    installBridge((request) => {
      requests.push(request)
      if (request.path === '/session/chart-view') return status(503)
      return status(404)
    })
    useChartViewStore.getState().reset()
    await act(() => useChartViewStore.getState().load())
    renderArea()
    fireEvent.click(screen.getByRole('tab', { name: '1주' }))
    await waitFor(() => expect(screen.getByText('차트를 받지 못했습니다')).toBeDefined())
    expect(requests.some((r) => r.method === 'PUT')).toBe(false)
  })
})
