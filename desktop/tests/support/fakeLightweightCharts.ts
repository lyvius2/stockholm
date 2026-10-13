import { vi } from 'vitest'

/** 테스트에서 캔버스 없이 라이브러리 호출만 기록하는 가짜. `vi.mock('lightweight-charts', fakeLightweightCharts)` 로 씀. */
export interface FakeSeries {
  readonly kind: string
  readonly setData: ReturnType<typeof vi.fn>
  readonly update: ReturnType<typeof vi.fn>
  readonly applyOptions: ReturnType<typeof vi.fn>
  readonly createPriceLine: ReturnType<typeof vi.fn>
  readonly removePriceLine: ReturnType<typeof vi.fn>
}
export interface FakeChart {
  readonly series: FakeSeries[]
  readonly remove: ReturnType<typeof vi.fn>
  readonly applyOptions: ReturnType<typeof vi.fn>
  readonly fitContent: ReturnType<typeof vi.fn>
  readonly markers: ReturnType<typeof vi.fn>[]
}

export const createdCharts: FakeChart[] = []

export function fakeLightweightCharts(): Record<string, unknown> {
  const seriesKind = (definition: { type: string }) => definition.type
  return {
    ColorType: { Solid: 'solid' },
    CrosshairMode: { Normal: 0 },
    LineStyle: { Solid: 0, Dashed: 2 },
    LineSeries: { type: 'Line' },
    CandlestickSeries: { type: 'Candlestick' },
    HistogramSeries: { type: 'Histogram' },
    createSeriesMarkers: (_series: unknown, markers: unknown[]) => {
      const setMarkers = vi.fn()
      createdCharts[createdCharts.length - 1]?.markers.push(setMarkers)
      setMarkers(markers)
      return { setMarkers, markers: () => [], detach: vi.fn() }
    },
    createChart: () => {
      const chart: FakeChart = {
        series: [],
        remove: vi.fn(),
        applyOptions: vi.fn(),
        fitContent: vi.fn(),
        markers: [],
      }
      createdCharts.push(chart)
      return {
        addSeries: (definition: { type: string }) => {
          const series: FakeSeries = {
            kind: seriesKind(definition),
            setData: vi.fn(),
            update: vi.fn(),
            applyOptions: vi.fn(),
            createPriceLine: vi.fn(() => ({})),
            removePriceLine: vi.fn(),
          }
          chart.series.push(series)
          return series
        },
        panes: () => [{ setStretchFactor: vi.fn() }, { setStretchFactor: vi.fn() }],
        timeScale: () => ({ fitContent: chart.fitContent }),
        applyOptions: chart.applyOptions,
        remove: chart.remove,
      }
    },
  }
}
