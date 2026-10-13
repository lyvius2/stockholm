import { useEffect, useRef } from 'react'
import {
  CandlestickSeries,
  ColorType,
  CrosshairMode,
  HistogramSeries,
  LineSeries,
  LineStyle,
  createChart,
  createSeriesMarkers,
  type IChartApi,
  type IPriceLine,
  type ISeriesApi,
  type ISeriesMarkersPluginApi,
  type SeriesMarker,
  type Time,
  type UTCTimestamp,
} from 'lightweight-charts'
import type { ApiChart, ApiChartBar, Resolution } from '@renderer/generated/api-chart'
import type { StreamSymbol } from '@renderer/data/stream/MarketStream'
import { formatDecimal } from '@renderer/shared/format/decimal'
import {
  extremes,
  isIntraday,
  percentChange,
  sessionDate,
  toAverageLine,
  toCandles,
  toCloseLine,
  toVolumes,
  type ChartPoint,
} from './chartData'

export interface PriceChartProps {
  readonly symbol: StreamSymbol
  /** 지금 요청한 봉 단위. 응답이 오기 전에도 차트를 한 번만 만들려고 응답이 아니라 요청에서 받음. */
  readonly resolution: Resolution
  readonly chart: ApiChart | undefined
  /** 그릴 봉(세션 날짜로 거르고 진행 봉을 합친 것). */
  readonly bars: readonly ApiChartBar[]
  readonly isDetailed: boolean
}

/** 이동평균 기간과 색 토큰. 설계서 고정 색(5 초록 · 20 상승색 · 60 경고색 · 120 보라). */
export const AVERAGE_WINDOWS: ReadonlyArray<{ window: number; token: string }> = [
  { window: 5, token: '--ma5' },
  { window: 20, token: '--up' },
  { window: 60, token: '--warn' },
  { window: 120, token: '--ma120' },
]

interface Palette {
  readonly ink: string
  readonly ink2: string
  readonly line: string
  readonly up: string
  readonly down: string
  readonly averages: readonly string[]
  readonly font: string
}

/** 화면은 토큰만 쓰므로 차트 색도 CSS 변수에서 읽음. 테마가 바뀌면 다시 읽어 적용함. */
function readPalette(): Palette {
  const style = getComputedStyle(document.documentElement)
  const token = (name: string) => style.getPropertyValue(name).trim()
  return {
    ink: token('--ink'),
    ink2: token('--ink2'),
    line: token('--line'),
    up: token('--up'),
    down: token('--down'),
    averages: AVERAGE_WINDOWS.map((a) => token(a.token)),
    font: token('--sans'),
  }
}

const asTime = (seconds: number): UTCTimestamp => seconds as UTCTimestamp
const withAlpha = (hex: string, alpha: string) => `color-mix(in srgb, ${hex} ${alpha}, transparent)`

interface Scene {
  readonly chart: IChartApi
  readonly line: ISeriesApi<'Line'> | null
  readonly candles: ISeriesApi<'Candlestick'> | null
  readonly averages: readonly ISeriesApi<'Line'>[]
  readonly volume: ISeriesApi<'Histogram'> | null
  readonly volumeAverage: ISeriesApi<'Line'> | null
  readonly markers: ISeriesMarkersPluginApi<Time>
  currentPriceLine: IPriceLine | null
}

/**
 * Lightweight Charts 래퍼(F3).
 * 간단 모드는 종가 선 하나(구간 최고·최저 표시, 현재가 점선), 자세한 모드는 캔들 + 이동평균 4개 + 아래 칸 거래량.
 * REST 응답이 바뀌면 setData 로 전부, 스트림 진행 봉만 바뀌면 끝 봉부터 update 로 그려 체결마다 다시 그리지 않음.
 * 라이선스(Apache-2.0)의 링크 조건은 라이브러리 기본 로고(attributionLogo)로, 귀속 문구는 차트 헤더와 NOTICE 파일로 지킴.
 */
export function PriceChart({ symbol, resolution, chart, bars, isDetailed }: PriceChartProps) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const sceneRef = useRef<Scene | null>(null)
  // 무엇을 그렸는지: 장면 키, 그린 REST 응답, 마지막 봉 시각. REST 가 바뀌면 전부, 스트림만 바뀌면 끝 봉만 그림
  const drawnRef = useRef<{ key: string; chart: ApiChart | null; lastTime: number }>({
    key: '',
    chart: null,
    lastTime: Number.NEGATIVE_INFINITY,
  })

  // 모드·종목·봉 단위가 바뀌면 차트를 새로 만듦(시리즈 구성이 다름)
  const sceneKey = `${isDetailed ? 'D' : 'S'}:${symbol.market}:${symbol.code}:${resolution}`
  useEffect(() => {
    const container = containerRef.current
    if (container === null) return undefined
    const scene = buildScene(container, isDetailed, readPalette())
    sceneRef.current = scene
    drawnRef.current = { key: '', chart: null, lastTime: Number.NEGATIVE_INFINITY }
    const stopTheme = watchTheme(() => applyPalette(scene, readPalette()))
    return () => {
      stopTheme()
      scene.chart.remove()
      sceneRef.current = null
    }
  }, [sceneKey, isDetailed])

  useEffect(() => {
    const scene = sceneRef.current
    if (scene === null || chart === undefined) return
    const market = symbol.market
    const drawn = drawnRef.current
    const palette = readPalette()
    const isNewScene = drawn.key !== sceneKey
    if (isNewScene || drawn.chart !== chart) {
      // REST 응답이 새로 왔으면 과거 봉·이동평균이 보정됐을 수 있어 전부 다시 그림
      setAll(scene, chart, bars, market, palette)
      // 확대·이동은 사용자의 것이라 장면이 새로 생겼을 때만 전체 구간에 맞춤
      if (isNewScene) scene.chart.timeScale().fitContent()
    } else {
      updateFrom(scene, bars, market, drawn.lastTime, palette)
    }
    decorate(scene, bars, market, palette, isDetailed, chart)
    const lastBar = bars[bars.length - 1]
    drawnRef.current = {
      key: sceneKey,
      chart,
      lastTime: lastBar === undefined ? Number.NEGATIVE_INFINITY : Date.parse(lastBar.openTime),
    }
  }, [chart, bars, symbol.market, sceneKey, isDetailed])

  return <div ref={containerRef} className="price-chart" data-testid="price-chart" />
}

function buildScene(container: HTMLElement, isDetailed: boolean, palette: Palette): Scene {
  const chart = createChart(container, {
    autoSize: true,
    layout: {
      background: { type: ColorType.Solid, color: 'transparent' },
      textColor: palette.ink2,
      fontFamily: palette.font,
      fontSize: 11,
      attributionLogo: true,
    },
    grid: {
      vertLines: { color: withAlpha(palette.line, '60%') },
      horzLines: { color: withAlpha(palette.line, '60%') },
    },
    crosshair: { mode: CrosshairMode.Normal },
    rightPriceScale: { borderColor: palette.line },
    timeScale: { borderColor: palette.line, timeVisible: true, secondsVisible: false },
    localization: { locale: 'ko-KR' },
  })
  if (!isDetailed) {
    const line = chart.addSeries(LineSeries, {
      color: palette.up,
      lineWidth: 2,
      priceLineVisible: false,
      lastValueVisible: true,
    })
    return {
      chart,
      line,
      candles: null,
      averages: [],
      volume: null,
      volumeAverage: null,
      markers: createSeriesMarkers(line, []),
      currentPriceLine: null,
    }
  }
  const candles = chart.addSeries(CandlestickSeries, {
    upColor: palette.up,
    downColor: palette.down,
    borderUpColor: palette.up,
    borderDownColor: palette.down,
    wickUpColor: palette.up,
    wickDownColor: palette.down,
    priceLineVisible: false,
  })
  const averages = AVERAGE_WINDOWS.map((_, index) =>
    chart.addSeries(LineSeries, {
      color: palette.averages[index] ?? palette.ink2,
      lineWidth: 1,
      priceLineVisible: false,
      lastValueVisible: false,
      crosshairMarkerVisible: false,
    }),
  )
  const volume = chart.addSeries(
    HistogramSeries,
    { priceFormat: { type: 'volume' }, priceLineVisible: false, lastValueVisible: false },
    1,
  )
  const volumeAverage = chart.addSeries(
    LineSeries,
    {
      color: palette.ink2,
      lineWidth: 1,
      priceLineVisible: false,
      lastValueVisible: false,
      crosshairMarkerVisible: false,
    },
    1,
  )
  // 거래량 칸은 가격 칸의 1/4 높이
  const panes = chart.panes()
  panes[0]?.setStretchFactor(3)
  panes[1]?.setStretchFactor(1)
  return {
    chart,
    line: null,
    candles,
    averages,
    volume,
    volumeAverage,
    markers: createSeriesMarkers(candles, []),
    currentPriceLine: null,
  }
}

function setAll(
  scene: Scene,
  chart: ApiChart,
  bars: readonly ApiChartBar[],
  market: StreamSymbol['market'],
  palette: Palette,
) {
  if (scene.line !== null) {
    scene.line.setData(
      toCloseLine(bars, market).map((p) => ({ time: asTime(p.time), value: p.value })),
    )
    return
  }
  scene.candles?.setData(toCandles(bars, market).map((c) => ({ ...c, time: asTime(c.time) })))
  scene.averages.forEach((series, index) => {
    const window = AVERAGE_WINDOWS[index]?.window ?? 0
    const values = chart.closeAverages[String(window)] ?? []
    series.setData(toLineData(toAverageLine(bars, values, market)))
  })
  scene.volume?.setData(
    toVolumes(bars, market).map((v) => ({
      time: asTime(v.time),
      value: v.value,
      color: withAlpha(v.isUp ? palette.up : palette.down, '55%'),
    })),
  )
  scene.volumeAverage?.setData(toLineData(toAverageLine(bars, chart.volumeAverage, market)))
}

/** 스트림만 바뀐 경우: 마지막으로 그린 봉부터 끝까지 update 로 올림(같은 시각은 고치고 새 시각은 붙음). */
function updateFrom(
  scene: Scene,
  bars: readonly ApiChartBar[],
  market: StreamSymbol['market'],
  fromTime: number,
  palette: Palette,
) {
  const changed = bars.filter((bar) => Date.parse(bar.openTime) >= fromTime)
  for (const bar of changed) {
    const candle = toCandles([bar], market)[0]
    if (candle === undefined) continue
    const time = asTime(candle.time)
    if (scene.line !== null) {
      scene.line.update({ time, value: candle.close })
      continue
    }
    scene.candles?.update({ ...candle, time })
    const volume = toVolumes([bar], market)[0]
    if (volume !== undefined)
      scene.volume?.update({
        time,
        value: volume.value,
        color: withAlpha(volume.isUp ? palette.up : palette.down, '55%'),
      })
  }
}

/** 구간 최고·최저 표시와 현재가 점선. 간단 모드는 구간 색(오르면 빨강, 내리면 파랑)도 여기서 정함. */
function decorate(
  scene: Scene,
  bars: readonly ApiChartBar[],
  market: StreamSymbol['market'],
  palette: Palette,
  isDetailed: boolean,
  chart: ApiChart,
) {
  const first = bars[0]
  const last = bars[bars.length - 1]
  const edges = extremes(bars)
  if (first === undefined || last === undefined || edges === null) {
    scene.markers.setMarkers([])
    return
  }
  const current = last.close.amount
  const isUpRange = Number(current) >= Number(first.close.amount)
  const rangeColor = isUpRange ? palette.up : palette.down
  if (scene.line !== null) scene.line.applyOptions({ color: rangeColor })

  const series = scene.line ?? scene.candles
  if (series === null) return
  if (scene.currentPriceLine !== null) series.removePriceLine(scene.currentPriceLine)
  scene.currentPriceLine = series.createPriceLine({
    price: Number(current),
    color: rangeColor,
    lineWidth: 1,
    lineStyle: LineStyle.Dashed,
    axisLabelVisible: true,
    title: '현재가',
  })

  const highBar = bars[edges.highest]
  const lowBar = bars[edges.lowest]
  if (highBar === undefined || lowBar === undefined) return
  const labelOf = (bar: ApiChartBar, amount: string) =>
    isDetailed
      ? `${formatDecimal(amount)} (${percentChange(amount, current)}) ${dateLabel(bar, market, chart)}`
      : formatDecimal(amount)
  const markers: SeriesMarker<Time>[] = [
    {
      time: asTime(toCandles([highBar], market)[0]?.time ?? 0),
      position: 'aboveBar',
      shape: 'circle',
      color: palette.up,
      size: 0.6,
      text: `최고 ${labelOf(highBar, highBar.high.amount)}`,
    },
    {
      time: asTime(toCandles([lowBar], market)[0]?.time ?? 0),
      position: 'belowBar',
      shape: 'circle',
      color: palette.down,
      size: 0.6,
      text: `최저 ${labelOf(lowBar, lowBar.low.amount)}`,
    },
  ]
  // 같은 봉이 최고·최저면(봉 하나) 위쪽만 표시
  scene.markers.setMarkers(
    edges.highest === edges.lowest ? markers.slice(0, 1) : sortedByTime(markers),
  )
}

function dateLabel(bar: ApiChartBar, market: StreamSymbol['market'], chart: ApiChart): string {
  const date = sessionDate(bar.openTime, market)
  if (!isIntraday(chart.resolution)) return date.slice(5)
  const time = toCandles([bar], market)[0]?.time ?? 0
  const clock = new Date(time * 1000)
  const hh = String(clock.getUTCHours()).padStart(2, '0')
  const mm = String(clock.getUTCMinutes()).padStart(2, '0')
  return `${date.slice(5)} ${hh}:${mm}`
}

function sortedByTime(markers: SeriesMarker<Time>[]): SeriesMarker<Time>[] {
  return [...markers].sort((a, b) => Number(a.time) - Number(b.time))
}

function toLineData(points: readonly ChartPoint[]) {
  return points.map((p) => ({ time: asTime(p.time), value: p.value }))
}

function applyPalette(scene: Scene, palette: Palette) {
  scene.chart.applyOptions({
    layout: { textColor: palette.ink2 },
    grid: {
      vertLines: { color: withAlpha(palette.line, '60%') },
      horzLines: { color: withAlpha(palette.line, '60%') },
    },
    rightPriceScale: { borderColor: palette.line },
    timeScale: { borderColor: palette.line },
  })
  scene.candles?.applyOptions({
    upColor: palette.up,
    downColor: palette.down,
    borderUpColor: palette.up,
    borderDownColor: palette.down,
    wickUpColor: palette.up,
    wickDownColor: palette.down,
  })
  scene.averages.forEach((series, index) =>
    series.applyOptions({ color: palette.averages[index] ?? palette.ink2 }),
  )
  scene.volumeAverage?.applyOptions({ color: palette.ink2 })
}

/** 테마 전환(루트의 data-theme 또는 OS 설정)을 지켜봄. */
function watchTheme(onChange: () => void): () => void {
  const observer =
    typeof MutationObserver === 'undefined' ? null : new MutationObserver(() => onChange())
  observer?.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
  const media =
    typeof window.matchMedia === 'function'
      ? window.matchMedia('(prefers-color-scheme: dark)')
      : null
  media?.addEventListener('change', onChange)
  return () => {
    observer?.disconnect()
    media?.removeEventListener('change', onChange)
  }
}
