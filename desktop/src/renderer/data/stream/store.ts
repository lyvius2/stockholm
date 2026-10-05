import { create } from 'zustand'
import type {
  StreamFeedState,
  StreamIndexTicker,
  StreamLiveCandle,
  StreamOrderBook,
  StreamQuote,
  StreamServerMessage,
} from '@renderer/generated/stream-server-message'
import type { StreamConnectionState, StreamSymbol } from './MarketStream'

/** 종목을 맵 키로 쓰는 표기. `KR:005930`, `US:NVDA`. */
export type SymbolKey = `${StreamSymbol['market']}:${string}`

export function symbolKey(symbol: StreamSymbol): SymbolKey {
  return `${symbol.market}:${symbol.code}`
}

interface LatestBySymbol<T> {
  readonly [key: SymbolKey]: T
}

export interface StreamStoreState {
  /** main 이 데몬 `/ws` 에 맺은 연결의 상태. */
  readonly connection: StreamConnectionState
  /** 증권사 시세 연결 상태. 받기 전에는 null. isLive 가 false 면 화면은 "지연". */
  readonly feed: StreamFeedState | null
  readonly indexTicker: StreamIndexTicker | null
  readonly quotes: LatestBySymbol<StreamQuote>
  readonly orderBooks: LatestBySymbol<StreamOrderBook>
  readonly liveCandles: LatestBySymbol<StreamLiveCandle>
  /** 이 화면이 보는 종목 전체. 바뀌면 데몬에 다시 선언됨. */
  readonly watched: readonly StreamSymbol[]
  applyMessages(messages: readonly StreamServerMessage[]): void
  setConnection(connection: StreamConnectionState): void
  setWatched(symbols: readonly StreamSymbol[]): void
  reset(): void
}

const EMPTY = {
  connection: 'closed' as const,
  feed: null,
  indexTicker: null,
  quotes: {},
  orderBooks: {},
  liveCandles: {},
  watched: [],
}

function pick<T>(latest: LatestBySymbol<T>, keep: Set<string>): LatestBySymbol<T> {
  return Object.fromEntries(Object.entries(latest).filter(([key]) => keep.has(key)))
}

/**
 * 받은 실시간 값의 최신 상태.
 * 데몬이 종목별 최신값만 묶어 보내므로 여기도 종목별로 마지막 값만 둠.
 * 한 묶음은 한 번의 set 으로 반영해 화면이 묶음마다 한 번만 다시 그림.
 */
export const useStreamStore = create<StreamStoreState>((set) => ({
  ...EMPTY,
  applyMessages: (messages) =>
    set((state) => {
      const quotes = { ...state.quotes }
      const orderBooks = { ...state.orderBooks }
      const liveCandles = { ...state.liveCandles }
      let feed = state.feed
      let indexTicker = state.indexTicker
      for (const message of messages) {
        const key = message.symbol === undefined ? null : symbolKey(message.symbol)
        if (message.type === 'quote' && key !== null && message.quote !== undefined)
          quotes[key] = message.quote
        else if (message.type === 'orderBook' && key !== null && message.orderBook !== undefined)
          orderBooks[key] = message.orderBook
        else if (message.type === 'liveCandle' && key !== null && message.liveCandle !== undefined)
          liveCandles[key] = message.liveCandle
        else if (message.type === 'feedState' && message.feedState !== undefined)
          feed = message.feedState
        else if (message.type === 'indexTicker' && message.indexTicker !== undefined)
          indexTicker = message.indexTicker
      }
      return { quotes, orderBooks, liveCandles, feed, indexTicker }
    }),
  setConnection: (connection) => set({ connection }),
  // 더는 보지 않는 종목의 마지막 값은 버림.
  // 나중에 그 종목으로 돌아오면 새 체결이 올 때까지 "—" 로 두어 옛 가격이 실시간 값처럼 보이지 않게 함
  setWatched: (symbols) =>
    set((state) => {
      const keep = new Set(symbols.map(symbolKey))
      return {
        watched: [...symbols],
        quotes: pick(state.quotes, keep),
        orderBooks: pick(state.orderBooks, keep),
        liveCandles: pick(state.liveCandles, keep),
      }
    }),
  reset: () => set(EMPTY),
}))
