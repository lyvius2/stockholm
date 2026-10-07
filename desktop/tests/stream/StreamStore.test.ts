import type { StreamServerMessage } from '@renderer/generated/stream-server-message'
import type {
  MarketStream,
  StreamConnectionState,
  StreamSymbol,
} from '@renderer/data/stream/MarketStream'
import { symbolKey, useStreamStore } from '@renderer/data/stream/store'
import { attachMarketStream } from '@renderer/data/stream/useMarketStream'

const samsung = { market: 'KR', code: '005930' } as const
const nvidia = { market: 'US', code: 'NVDA' } as const

function quote(symbol: StreamSymbol, amount: string): StreamServerMessage {
  return {
    type: 'quote',
    symbol,
    quote: {
      last: { amount, currency: symbol.market === 'KR' ? 'KRW' : 'USD' },
      asOf: '2026-10-05T01:00:00Z',
    },
  }
}

/** 손으로 만든 스트림. 선언·닫기를 기록하고 테스트가 메시지·상태를 밀어 넣음. */
class FakeMarketStream implements MarketStream {
  readonly declared: StreamSymbol[][] = []
  closeCount = 0
  private messageListeners: Array<(messages: readonly StreamServerMessage[]) => void> = []
  private stateListeners: Array<(state: StreamConnectionState) => void> = []

  async subscribe(symbols: readonly StreamSymbol[]): Promise<void> {
    this.declared.push([...symbols])
  }

  async close(): Promise<void> {
    this.closeCount += 1
  }

  async state(): Promise<StreamConnectionState> {
    return 'connecting'
  }

  onMessages(listener: (messages: readonly StreamServerMessage[]) => void): () => void {
    this.messageListeners.push(listener)
    return () => {
      this.messageListeners = this.messageListeners.filter((l) => l !== listener)
    }
  }

  onState(listener: (state: StreamConnectionState) => void): () => void {
    this.stateListeners.push(listener)
    return () => {
      this.stateListeners = this.stateListeners.filter((l) => l !== listener)
    }
  }

  push(messages: StreamServerMessage[]): void {
    this.messageListeners.forEach((listener) => listener(messages))
  }

  changeState(state: StreamConnectionState): void {
    this.stateListeners.forEach((listener) => listener(state))
  }

  listenerCount(): number {
    return this.messageListeners.length + this.stateListeners.length
  }
}

describe('useStreamStore', () => {
  beforeEach(() => useStreamStore.getState().reset())

  it('한 묶음의 종목별 최신값·시세 연결 상태·지수 티커를 반영하고, 다음 묶음은 온 종목만 덮음', () => {
    const feedState: StreamServerMessage = {
      type: 'feedState',
      feedState: { isLive: true, unavailableSymbols: [nvidia] },
    }
    const indexTicker: StreamServerMessage = {
      type: 'indexTicker',
      indexTicker: {
        market: 'KR',
        state: 'OPEN',
        entries: [],
        asOf: '2026-10-05T01:00:00Z',
        isDelayed: false,
      },
    }

    useStreamStore.getState().setWatched([samsung, nvidia])
    useStreamStore
      .getState()
      .applyMessages([quote(samsung, '71000'), quote(nvidia, '120.50'), feedState, indexTicker])
    useStreamStore.getState().applyMessages([quote(nvidia, '121.00')])

    const state = useStreamStore.getState()
    expect(state.quotes[symbolKey(samsung)]?.last.amount).toBe('71000')
    expect(state.quotes[symbolKey(nvidia)]?.last.amount).toBe('121.00')
    expect(state.feed).toEqual(feedState.feedState)
    expect(state.indexTicker).toEqual(indexTicker.indexTicker)
  })
})

describe('useStreamStore.setWatched', () => {
  beforeEach(() => useStreamStore.getState().reset())

  it('보는 종목에서 빠진 종목의 마지막 값은 버리고, 남은 종목의 값은 유지함', () => {
    useStreamStore.getState().setWatched([samsung, nvidia])
    useStreamStore.getState().applyMessages([quote(samsung, '71000'), quote(nvidia, '120.50')])

    useStreamStore.getState().setWatched([nvidia])

    expect(useStreamStore.getState().quotes[symbolKey(samsung)]).toBeUndefined()
    expect(useStreamStore.getState().quotes[symbolKey(nvidia)]?.last.amount).toBe('120.50')
  })
})

describe('구독 해제 뒤의 메시지', () => {
  beforeEach(() => useStreamStore.getState().reset())

  it('늦게 온 옛 종목 시세를 버리고 다시 선택해도 새 메시지까지 비워 둠', () => {
    const store = useStreamStore.getState()
    store.setWatched([samsung])
    store.applyMessages([quote(samsung, '71000')])
    store.setWatched([nvidia])
    store.applyMessages([
      quote(samsung, '72000'),
      {
        type: 'orderBook',
        symbol: samsung,
        orderBook: { asks: [], bids: [], asOf: '2026-10-06T01:00:00Z' },
      },
    ])
    store.setWatched([samsung])
    expect(useStreamStore.getState().quotes[symbolKey(samsung)]).toBeUndefined()
    expect(useStreamStore.getState().orderBooks[symbolKey(samsung)]).toBeUndefined()
    store.applyMessages([quote(samsung, '73000')])
    expect(useStreamStore.getState().quotes[symbolKey(samsung)]?.last.amount).toBe('73000')
  })
})

describe('attachMarketStream', () => {
  beforeEach(() => useStreamStore.getState().reset())

  it('붙이면 보는 종목을 선언하고, 보는 종목이 바뀌면 다시 선언하고, 받은 것을 store 에 넣음', async () => {
    const stream = new FakeMarketStream()

    const detach = attachMarketStream(stream)
    await Promise.resolve()
    expect(stream.declared).toEqual([[]])
    expect(useStreamStore.getState().connection).toBe('connecting')

    useStreamStore.getState().setWatched([samsung])
    expect(stream.declared).toEqual([[], [samsung]])

    stream.changeState('open')
    stream.push([quote(samsung, '71000')])
    expect(useStreamStore.getState().connection).toBe('open')
    expect(useStreamStore.getState().quotes[symbolKey(samsung)]?.last.amount).toBe('71000')

    detach()
  })

  it('떼면 연결을 닫고 리스너를 거두고 store 를 비움', () => {
    const stream = new FakeMarketStream()
    const detach = attachMarketStream(stream)
    useStreamStore.getState().setWatched([samsung])
    stream.push([quote(samsung, '71000')])

    detach()

    expect(stream.closeCount).toBe(1)
    expect(stream.listenerCount()).toBe(0)
    expect(useStreamStore.getState().quotes).toEqual({})
    expect(useStreamStore.getState().watched).toEqual([])
    useStreamStore.getState().setWatched([nvidia])
    expect(stream.declared).toEqual([[], [samsung]])
  })
})
