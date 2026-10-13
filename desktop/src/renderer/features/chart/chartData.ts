import type { ApiChart, ApiChartBar, Resolution } from '@renderer/generated/api-chart'
import type { SimpleRange } from '@renderer/generated/api-chart-view'
import type { StreamLiveCandle } from '@renderer/generated/stream-server-message'
import type { StreamSymbol } from '@renderer/data/stream/MarketStream'

export type Market = StreamSymbol['market']

/** 차트에 올릴 점 하나. time 은 시장 현지 시각을 UTC 인 것처럼 옮긴 초(라이브러리가 UTC 로 그리므로). */
export interface ChartPoint {
  readonly time: number
  readonly value: number
}
export interface CandlePoint {
  readonly time: number
  readonly open: number
  readonly high: number
  readonly low: number
  readonly close: number
}
export interface VolumePoint {
  readonly time: number
  readonly value: number
  readonly isUp: boolean
}

/** 기간 탭 하나를 어떤 봉 단위·봉 수로 받을지. 장중·1일·1주는 받은 뒤 최근 세션 날짜로 거름. */
export interface RangeRequest {
  readonly resolution: Resolution
  readonly count: number
  /** 최근 몇 거래일만 남길지. 없으면 전부. */
  readonly sessionDays?: number
}

/** 간단 모드 기간 탭 → 조회 조건 [제안]. 분봉 수는 국내 정규장(390분) 기준으로 잡음. */
export const SIMPLE_RANGE_REQUESTS: Record<SimpleRange, RangeRequest> = {
  INTRADAY: { resolution: 'MINUTE_1', count: 500, sessionDays: 1 },
  DAY_1: { resolution: 'MINUTE_5', count: 300, sessionDays: 2 },
  WEEK_1: { resolution: 'MINUTE_30', count: 200, sessionDays: 5 },
  MONTH_3: { resolution: 'DAY', count: 65 },
  YEAR_1: { resolution: 'DAY', count: 250 },
  YEAR_5: { resolution: 'WEEK', count: 260 },
  ALL: { resolution: 'MONTH', count: 500 },
}

/** 자세한 모드는 봉 단위를 그대로 받음. 화면 폭 하나와 이동평균 120 을 채우는 양. */
export const DETAILED_BAR_COUNT = 300

export const SIMPLE_RANGE_LABEL: Record<SimpleRange, string> = {
  INTRADAY: '장중',
  DAY_1: '1일',
  WEEK_1: '1주',
  MONTH_3: '3달',
  YEAR_1: '1년',
  YEAR_5: '5년',
  ALL: '전체',
}
export const MINUTE_RESOLUTIONS: readonly Resolution[] = [
  'MINUTE_1',
  'MINUTE_3',
  'MINUTE_5',
  'MINUTE_10',
  'MINUTE_30',
  'MINUTE_60',
]
export const RESOLUTION_LABEL: Record<Resolution, string> = {
  MINUTE_1: '1분',
  MINUTE_3: '3분',
  MINUTE_5: '5분',
  MINUTE_10: '10분',
  MINUTE_30: '30분',
  MINUTE_60: '60분',
  DAY: '일',
  WEEK: '주',
  MONTH: '월',
  YEAR: '년',
}
const RESOLUTION_MINUTES: Partial<Record<Resolution, number>> = {
  MINUTE_1: 1,
  MINUTE_3: 3,
  MINUTE_5: 5,
  MINUTE_10: 10,
  MINUTE_30: 30,
  MINUTE_60: 60,
}

export function isIntraday(resolution: Resolution): boolean {
  return RESOLUTION_MINUTES[resolution] !== undefined
}

// 국내 봉은 한국 시간, 미국 봉은 뉴욕 시간으로 축을 매김(서머타임은 Intl 이 반영)
const MARKET_ZONE: Record<Market, string> = { KR: 'Asia/Seoul', US: 'America/New_York' }

const partsFormatters = new Map<string, Intl.DateTimeFormat>()
function partsFormatter(zone: string): Intl.DateTimeFormat {
  let formatter = partsFormatters.get(zone)
  if (formatter === undefined) {
    formatter = new Intl.DateTimeFormat('en-US', {
      timeZone: zone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    })
    partsFormatters.set(zone, formatter)
  }
  return formatter
}

/** 그 시각의 시장 현지 벽시계를 UTC 로 읽은 초. 라이브러리는 UTC 로만 축을 그리므로 이렇게 옮겨 현지 시각이 보이게 함. */
export function toChartTime(iso: string, market: Market): number {
  const parts = partsFormatter(MARKET_ZONE[market]).formatToParts(new Date(iso))
  const get = (type: Intl.DateTimeFormatPartTypes) =>
    Number(parts.find((p) => p.type === type)?.value ?? '0')
  return (
    Date.UTC(get('year'), get('month') - 1, get('day'), get('hour'), get('minute'), get('second')) /
    1000
  )
}

/** 시장 현지 날짜(YYYY-MM-DD). 세션 날짜로 거르는 데 씀. */
export function sessionDate(iso: string, market: Market): string {
  const parts = partsFormatter(MARKET_ZONE[market]).formatToParts(new Date(iso))
  const get = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((p) => p.type === type)?.value ?? ''
  return `${get('year')}-${get('month')}-${get('day')}`
}

/** 최근 [days] 거래일의 봉만 남김. 봉은 시각 오름차순이어야 함. */
export function keepRecentSessions(
  bars: readonly ApiChartBar[],
  market: Market,
  days: number | undefined,
): ApiChartBar[] {
  if (days === undefined || bars.length === 0) return [...bars]
  const dates = new Set<string>()
  const kept: ApiChartBar[] = []
  for (let i = bars.length - 1; i >= 0; i -= 1) {
    const bar = bars[i]
    if (bar === undefined) continue
    const date = sessionDate(bar.openTime, market)
    dates.add(date)
    if (dates.size > days) break
    kept.push(bar)
  }
  return kept.reverse()
}

export function toCandles(bars: readonly ApiChartBar[], market: Market): CandlePoint[] {
  return bars.map((bar) => ({
    time: toChartTime(bar.openTime, market),
    open: Number(bar.open.amount),
    high: Number(bar.high.amount),
    low: Number(bar.low.amount),
    close: Number(bar.close.amount),
  }))
}

export function toCloseLine(bars: readonly ApiChartBar[], market: Market): ChartPoint[] {
  return bars.map((bar) => ({
    time: toChartTime(bar.openTime, market),
    value: Number(bar.close.amount),
  }))
}

/** 평균 배열은 봉과 같은 길이. 비어 있는 자리(null)는 점을 만들지 않음. */
export function toAverageLine(
  bars: readonly ApiChartBar[],
  averages: ReadonlyArray<{ amount: string } | string | null>,
  market: Market,
): ChartPoint[] {
  const points: ChartPoint[] = []
  bars.forEach((bar, index) => {
    const average = averages[index]
    if (average === null || average === undefined) return
    points.push({
      time: toChartTime(bar.openTime, market),
      value: Number(typeof average === 'string' ? average : average.amount),
    })
  })
  return points
}

export function toVolumes(bars: readonly ApiChartBar[], market: Market): VolumePoint[] {
  return bars.map((bar) => ({
    time: toChartTime(bar.openTime, market),
    value: Number(bar.volume),
    isUp: Number(bar.close.amount) >= Number(bar.open.amount),
  }))
}

/** 구간 최고·최저 봉의 위치. 같은 값이면 먼저 나온 봉. 봉이 없으면 null. */
export function extremes(
  bars: readonly ApiChartBar[],
): { readonly highest: number; readonly lowest: number } | null {
  if (bars.length === 0) return null
  let highest = 0
  let lowest = 0
  bars.forEach((bar, index) => {
    const high = bars[highest]
    const low = bars[lowest]
    if (high !== undefined && Number(bar.high.amount) > Number(high.high.amount)) highest = index
    if (low !== undefined && Number(bar.low.amount) < Number(low.low.amount)) lowest = index
  })
  return { highest, lowest }
}

/** 등락률(%) 문자열. 기준이 0 이면 "—". */
export function percentChange(from: string, to: string): string {
  const base = Number(from)
  if (base === 0) return '—'
  const rate = ((Number(to) - base) / base) * 100
  return `${rate >= 0 ? '+' : ''}${rate.toFixed(2)}%`
}

/**
 * 진행 중인 1분봉이 들어갈 묶음의 시작 시각(ISO).
 * 데몬의 묶음 규칙과 같음: 분 단위는 시계에 맞추고, 일은 현지 날짜, 주는 월요일, 월은 1일, 년은 1월 1일.
 * 60분봉의 세션 경계 나눔은 데몬만 하므로 그 경계의 진행 봉은 다음 조회가 바로잡음.
 */
export function bucketStart(openTimeIso: string, resolution: Resolution): string {
  const minutes = RESOLUTION_MINUTES[resolution]
  if (minutes !== undefined) {
    const size = minutes * 60_000
    const epoch = new Date(openTimeIso).getTime()
    return new Date(Math.floor(epoch / size) * size).toISOString()
  }
  const [year, month, day] = sessionDate(openTimeIso, 'KR').split('-').map(Number) as [
    number,
    number,
    number,
  ]
  // 일봉 이상은 데몬이 시장과 상관없이 한국 시간 날짜로 묶음(토스 일봉 시각 규칙)
  let first = new Date(Date.UTC(year, month - 1, day))
  if (resolution === 'WEEK') {
    const weekday = (first.getUTCDay() + 6) % 7
    first = new Date(first.getTime() - weekday * 86_400_000)
  } else if (resolution === 'MONTH') first = new Date(Date.UTC(year, month - 1, 1))
  else if (resolution === 'YEAR') first = new Date(Date.UTC(year, 0, 1))
  return toKstMidnightIso(first)
}

// 한국 시간 0시는 UTC 전날 15시
function toKstMidnightIso(utcDate: Date): string {
  return new Date(utcDate.getTime() - 9 * 3_600_000).toISOString()
}

/**
 * 조회 뒤에 받은 진행 1분봉들을 봉 목록에 합침.
 * 토스 REST 봉은 조회 시점의 진행 중인 분까지 들어 있으므로, 조회한 분보다 앞선 분은 이미 반영된 것이라 버림.
 * 조회한 분(겹치는 분)은 고가·저가·종가만 보태고 거래량은 더하지 않음(REST 가 그 분을 얼마나 담았는지 모름).
 * 그 뒤의 분은 고가·저가·종가·거래량을 모두 더함.
 * 마지막 봉과 같은 묶음이면 그 봉을 고치고, 다음 묶음이면 새 봉을 붙임.
 * 닫힌 봉의 정확한 값은 다음 조회가 채움.
 */
export function mergeLiveMinutes(
  bars: readonly ApiChartBar[],
  minutes: Readonly<Record<string, StreamLiveCandle>>,
  resolution: Resolution,
  fetchedAtMs: number,
): ApiChartBar[] {
  const fetchedMinute = Math.floor(fetchedAtMs / 60_000) * 60_000
  const fresh = Object.values(minutes)
    .filter((m) => Date.parse(m.openTime) >= fetchedMinute)
    .sort((a, b) => Date.parse(a.openTime) - Date.parse(b.openTime))
  if (fresh.length === 0) return [...bars]

  const result = [...bars]
  const buckets = new Map<string, StreamLiveCandle[]>()
  for (const minute of fresh) {
    const bucket = bucketStart(minute.openTime, resolution)
    buckets.set(bucket, [...(buckets.get(bucket) ?? []), minute])
  }
  for (const [bucket, members] of [...buckets.entries()].sort(([a], [b]) => a.localeCompare(b))) {
    const bucketAt = Date.parse(bucket)
    const last = result[result.length - 1]
    const lastAt = last === undefined ? Number.NEGATIVE_INFINITY : Date.parse(last.openTime)
    if (bucketAt < lastAt) continue
    const latest = members[members.length - 1]
    if (latest === undefined) continue
    if (last !== undefined && bucketAt === lastAt) {
      const added = members.filter((m) => Date.parse(m.openTime) > fetchedMinute)
      result[result.length - 1] = {
        ...last,
        high: members.reduce((acc, m) => maxMoney(acc, m.high), last.high),
        low: members.reduce((acc, m) => minMoney(acc, m.low), last.low),
        close: latest.close,
        volume: sumVolume([last.volume, ...added.map((m) => m.volume)]),
      }
      continue
    }
    const first = members[0]
    if (first === undefined) continue
    result.push({
      openTime: bucket,
      open: first.open,
      high: members.reduce((acc, m) => maxMoney(acc, m.high), first.high),
      low: members.reduce((acc, m) => minMoney(acc, m.low), first.low),
      close: latest.close,
      volume: sumVolume(members.map((m) => m.volume)),
    })
  }
  return result
}

// 거래량은 차트에 수로 올리는 근사값이라 여기서 더함(금액 계산이 아님)
function sumVolume(volumes: readonly string[]): string {
  return String(volumes.reduce((sum, v) => sum + Number(v), 0))
}

type Money = ApiChartBar['close']
const maxMoney = (a: Money, b: Money): Money => (Number(b.amount) > Number(a.amount) ? b : a)
const minMoney = (a: Money, b: Money): Money => (Number(b.amount) < Number(a.amount) ? b : a)

/** 요청 조건으로 받은 응답에서 그릴 봉을 고름(세션 날짜 거르기). */
export function visibleBars(chart: ApiChart, request: RangeRequest): ApiChartBar[] {
  return keepRecentSessions(chart.bars, chart.symbol.market, request.sessionDays)
}
