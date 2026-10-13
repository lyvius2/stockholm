import { WATCH_LIMIT, useStreamStore } from '@renderer/data/stream/store'

const samsung = { market: 'KR', code: '005930' } as const
const nvidia = { market: 'US', code: 'NVDA' } as const
const hynix = { market: 'KR', code: '000660' } as const

describe('스트림 store 의 보는 종목 합치기', () => {
  beforeEach(() => useStreamStore.getState().reset())

  it('네 영역 종목이 먼저, 서랍 종목은 뒤에, 겹치면 하나로, 상한 20 까지', () => {
    const store = useStreamStore.getState()
    store.setWatched([samsung])
    store.setExtraWatched('watchlist', [samsung, nvidia, hynix])
    expect(useStreamStore.getState().watched).toEqual([samsung, nvidia, hynix])

    const many = Array.from({ length: 30 }, (_, i) => ({
      market: 'KR' as const,
      code: String(i).padStart(6, '0'),
    }))
    store.setExtraWatched('watchlist', many)
    expect(useStreamStore.getState().watched).toHaveLength(WATCH_LIMIT)
    expect(useStreamStore.getState().watched[0]).toEqual(samsung)
  })

  it('네 영역 종목이 바뀌어도 서랍 종목은 남고, 서랍을 거두면 그 종목의 값도 버림', () => {
    const store = useStreamStore.getState()
    store.setWatched([samsung])
    store.setExtraWatched('watchlist', [nvidia])
    store.setConnection('open')
    store.applyMessages([
      {
        type: 'quote',
        symbol: nvidia,
        quote: { last: { amount: '120', currency: 'USD' }, asOf: '2026-10-13T00:00:00Z' },
      },
    ])
    store.setWatched([hynix])
    expect(useStreamStore.getState().watched).toEqual([hynix, nvidia])
    expect(useStreamStore.getState().quotes['US:NVDA']?.last.amount).toBe('120')

    store.setExtraWatched('watchlist', [])
    expect(useStreamStore.getState().watched).toEqual([hynix])
    expect(useStreamStore.getState().quotes['US:NVDA']).toBeUndefined()
  })

  it('보는 종목이 같으면 watched 참조를 유지해 데몬에 다시 선언하지 않음', () => {
    const store = useStreamStore.getState()
    store.setWatched([samsung])
    const before = useStreamStore.getState().watched
    store.setExtraWatched('movers', [])
    store.setWatched([samsung])
    expect(useStreamStore.getState().watched).toBe(before)
  })
})
