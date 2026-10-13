import type { ApiChartBar } from '@renderer/generated/api-chart'
import {
  bucketStart,
  extremes,
  keepRecentSessions,
  mergeLiveMinutes,
  percentChange,
  sessionDate,
  toAverageLine,
  toChartTime,
} from '@renderer/features/chart/chartData'

function bar(openTime: string, close: string, overrides: Partial<ApiChartBar> = {}): ApiChartBar {
  const money = (amount: string) => ({ amount, currency: 'KRW' as const })
  return {
    openTime,
    open: money(close),
    high: money(close),
    low: money(close),
    close: money(close),
    volume: '10',
    ...overrides,
  }
}

describe('chartData', () => {
  it('차트 시각은 시장 현지 벽시계를 UTC 로 옮긴 초이고, 서머타임도 반영함', () => {
    // 2026-10-06 09:00 KST = 00:00Z
    expect(toChartTime('2026-10-06T00:00:00Z', 'KR')).toBe(Date.UTC(2026, 9, 6, 9, 0) / 1000)
    // 2026-10-06 13:30Z = 09:30 EDT(서머타임)
    expect(toChartTime('2026-10-06T13:30:00Z', 'US')).toBe(Date.UTC(2026, 9, 6, 9, 30) / 1000)
    // 2026-12-01 14:30Z = 09:30 EST
    expect(toChartTime('2026-12-01T14:30:00Z', 'US')).toBe(Date.UTC(2026, 11, 1, 9, 30) / 1000)
    expect(sessionDate('2026-10-05T23:30:00Z', 'KR')).toBe('2026-10-06')
    expect(sessionDate('2026-10-05T23:30:00Z', 'US')).toBe('2026-10-05')
  })

  it('최근 거래일만 남기기는 현지 날짜로 세고 순서를 유지함', () => {
    const bars = [
      bar('2026-10-02T00:00:00Z', '1'),
      bar('2026-10-02T01:00:00Z', '2'),
      bar('2026-10-05T00:00:00Z', '3'),
      bar('2026-10-06T00:00:00Z', '4'),
    ]
    expect(keepRecentSessions(bars, 'KR', 1).map((b) => b.close.amount)).toEqual(['4'])
    expect(keepRecentSessions(bars, 'KR', 2).map((b) => b.close.amount)).toEqual(['3', '4'])
    expect(keepRecentSessions(bars, 'KR', undefined)).toHaveLength(4)
  })

  it('조회 뒤의 진행 분만 합침: 겹치는 분은 고저종만, 그 뒤 분은 거래량도 더하고, 다음 묶음은 새 봉', () => {
    // REST 는 00:05 묶음(00:05~00:07 진행 중)까지 담아 00:07:20 에 받음
    const bars = [bar('2026-10-06T00:00:00Z', '100'), bar('2026-10-06T00:05:00Z', '101')]
    const fetchedAt = Date.parse('2026-10-06T00:07:20Z')
    const money = (amount: string) => ({ amount, currency: 'KRW' as const })
    const minute = (
      openTime: string,
      high: string,
      low: string,
      close: string,
      volume: string,
    ) => ({
      openTime,
      open: money(close),
      high: money(high),
      low: money(low),
      close: money(close),
      volume,
    })
    const minutes = {
      // 조회 전 분: REST 에 이미 있음 → 무시
      '2026-10-06T00:06:00Z': minute('2026-10-06T00:06:00Z', '150', '50', '101', '100'),
      // 조회한 분(겹침): 고저종만, 거래량은 더하지 않음
      '2026-10-06T00:07:00Z': minute('2026-10-06T00:07:00Z', '105', '99', '104', '30'),
      // 그 뒤 분: 전부 더함
      '2026-10-06T00:08:00Z': minute('2026-10-06T00:08:00Z', '106', '103', '103', '7'),
      '2026-10-06T00:09:00Z': minute('2026-10-06T00:09:00Z', '104', '98', '102', '5'),
    }

    const merged = mergeLiveMinutes(bars, minutes, 'MINUTE_5', fetchedAt)
    expect(merged).toHaveLength(2)
    expect(merged[1]?.high.amount).toBe('106')
    expect(merged[1]?.low.amount).toBe('98')
    expect(merged[1]?.close.amount).toBe('102')
    expect(merged[1]?.volume).toBe('22')

    const appended = mergeLiveMinutes(
      bars,
      {
        ...minutes,
        '2026-10-06T00:11:00Z': minute('2026-10-06T00:11:00Z', '110', '101', '109', '4'),
      },
      'MINUTE_5',
      fetchedAt,
    )
    expect(appended).toHaveLength(3)
    expect(appended[2]?.openTime).toBe('2026-10-06T00:10:00.000Z')
    expect(appended[2]?.volume).toBe('4')
    expect(appended[2]?.open.amount).toBe('109')

    expect(mergeLiveMinutes(bars, {}, 'MINUTE_5', fetchedAt)).toHaveLength(2)
  })

  it('일·주·월·년 묶음은 한국 시간 날짜 기준이고 60분봉은 시계 정렬', () => {
    // 2026-10-07 목요일 10:20 KST
    const at = '2026-10-07T01:20:00Z'
    expect(bucketStart(at, 'MINUTE_60')).toBe('2026-10-07T01:00:00.000Z')
    expect(bucketStart(at, 'DAY')).toBe('2026-10-06T15:00:00.000Z')
    expect(bucketStart(at, 'WEEK')).toBe('2026-10-04T15:00:00.000Z')
    expect(bucketStart(at, 'MONTH')).toBe('2026-09-30T15:00:00.000Z')
    expect(bucketStart(at, 'YEAR')).toBe('2025-12-31T15:00:00.000Z')
  })

  it('구간 최고·최저는 같은 값이면 먼저 나온 봉이고, 평균의 빈자리는 점을 만들지 않음', () => {
    const bars = [
      bar('2026-10-06T00:00:00Z', '10', { high: { amount: '12', currency: 'KRW' } }),
      bar('2026-10-06T00:01:00Z', '11', { low: { amount: '9', currency: 'KRW' } }),
      bar('2026-10-06T00:02:00Z', '12', { high: { amount: '12', currency: 'KRW' } }),
    ]
    expect(extremes(bars)).toEqual({ highest: 0, lowest: 1 })
    expect(extremes([])).toBeNull()
    expect(
      toAverageLine(bars, [null, { amount: '10.5' }, '11.0'], 'KR').map((p) => p.value),
    ).toEqual([10.5, 11])
    expect(percentChange('100', '103.5')).toBe('+3.50%')
    expect(percentChange('100', '98')).toBe('-2.00%')
    expect(percentChange('0', '98')).toBe('—')
  })
})
