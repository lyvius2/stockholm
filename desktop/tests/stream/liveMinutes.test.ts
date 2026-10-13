import { LIVE_MINUTES_CAP, useStreamStore } from '@renderer/data/stream/store'

const samsung = { market: 'KR', code: '005930' } as const
const money = (amount: string) => ({ amount, currency: 'KRW' as const })

function candle(openTime: string, close: string) {
  return {
    openTime,
    open: money(close),
    high: money(close),
    low: money(close),
    close: money(close),
    volume: '1',
  }
}

describe('스트림 store 의 진행 분 누적', () => {
  beforeEach(() => {
    useStreamStore.getState().reset()
    useStreamStore.getState().setWatched([samsung])
  })

  it('분마다 마지막 값을 쌓고, 같은 분은 덮어쓰며, 상한을 넘으면 오래된 분부터 버림', () => {
    const store = useStreamStore.getState()
    store.applyMessages([
      { type: 'liveCandle', symbol: samsung, liveCandle: candle('2026-10-06T00:00:00Z', '1') },
      { type: 'liveCandle', symbol: samsung, liveCandle: candle('2026-10-06T00:00:00Z', '2') },
      { type: 'liveCandle', symbol: samsung, liveCandle: candle('2026-10-06T00:01:00Z', '3') },
    ])
    const minutes = useStreamStore.getState().liveMinutes['KR:005930'] ?? {}
    expect(Object.keys(minutes)).toHaveLength(2)
    expect(minutes['2026-10-06T00:00:00Z']?.close.amount).toBe('2')

    const many = Array.from({ length: LIVE_MINUTES_CAP + 1 }, (_, i) => ({
      type: 'liveCandle' as const,
      symbol: samsung,
      liveCandle: candle(new Date(Date.UTC(2026, 9, 7, 0, 0, 0) + i * 60_000).toISOString(), '9'),
    }))
    useStreamStore.getState().applyMessages(many)
    const capped = useStreamStore.getState().liveMinutes['KR:005930'] ?? {}
    expect(Object.keys(capped)).toHaveLength(LIVE_MINUTES_CAP)
    expect(capped['2026-10-06T00:00:00Z']).toBeUndefined()
  })

  it('보지 않게 된 종목의 분은 버림', () => {
    useStreamStore
      .getState()
      .applyMessages([
        { type: 'liveCandle', symbol: samsung, liveCandle: candle('2026-10-06T00:00:00Z', '1') },
      ])
    useStreamStore.getState().setWatched([])
    expect(useStreamStore.getState().liveMinutes).toEqual({})
  })
})
