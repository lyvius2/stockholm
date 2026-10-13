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
  /**
   * 종목별로 받은 진행 1분봉을 분(openTime)마다 마지막 값으로 쌓아 둠.
   * 차트가 조회 뒤에 생긴 분들을 전부 합치는 데 씀(최신 한 분만 두면 직전 분의 고가·거래량을 잃음).
   */
  readonly liveMinutes: LatestBySymbol<Readonly<Record<string, StreamLiveCandle>>>
  /** 이 화면이 보는 종목 전체(네 영역 종목 + 서랍이 더한 종목). 바뀌면 데몬에 다시 선언됨. */
  readonly watched: readonly StreamSymbol[]
  /** 네 영역이 보는 종목. */
  readonly primary: readonly StreamSymbol[]
  /** 서랍 같은 부가 화면이 더 보려는 종목(출처별). */
  readonly extras: Readonly<Record<string, readonly StreamSymbol[]>>
  applyMessages(messages: readonly StreamServerMessage[]): void
  setConnection(connection: StreamConnectionState): void
  /** 네 영역의 종목을 선언함. 서랍이 더한 종목은 그대로 둠. */
  setWatched(symbols: readonly StreamSymbol[]): void
  /** 부가 화면이 더 볼 종목을 출처 이름으로 선언함. 빈 목록이면 거둠. */
  setExtraWatched(source: string, symbols: readonly StreamSymbol[]): void
  reset(): void
}

/** 데몬이 받는 화면당 구독 상한. 네 영역 종목이 먼저, 서랍 종목은 남는 자리만 */
export const WATCH_LIMIT = 20

function composeWatched(
  primary: readonly StreamSymbol[],
  extras: Readonly<Record<string, readonly StreamSymbol[]>>,
): StreamSymbol[] {
  const seen = new Set<string>()
  const merged: StreamSymbol[] = []
  for (const symbol of [...primary, ...Object.values(extras).flat()]) {
    const key = symbolKey(symbol)
    if (seen.has(key) || merged.length >= WATCH_LIMIT) continue
    seen.add(key)
    merged.push(symbol)
  }
  return merged
}

const EMPTY = {
  connection: 'closed' as const,
  feed: null,
  indexTicker: null,
  quotes: {},
  orderBooks: {},
  liveCandles: {},
  liveMinutes: {},
  watched: [],
  primary: [],
  extras: {},
}

/** 종목 하나에 쌓아 두는 분의 상한. 하루 정규장(390분)을 넘기면 오래된 분부터 버림 */
export const LIVE_MINUTES_CAP = 600

function withMinute(
  minutes: Readonly<Record<string, StreamLiveCandle>> | undefined,
  candle: StreamLiveCandle,
): Readonly<Record<string, StreamLiveCandle>> {
  const next: Record<string, StreamLiveCandle> = { ...(minutes ?? {}), [candle.openTime]: candle }
  const keys = Object.keys(next)
  if (keys.length <= LIVE_MINUTES_CAP) return next
  for (const key of keys.sort().slice(0, keys.length - LIVE_MINUTES_CAP)) delete next[key]
  return next
}

function pick<T>(latest: LatestBySymbol<T>, keep: Set<string>): LatestBySymbol<T> {
  return Object.fromEntries(Object.entries(latest).filter(([key]) => keep.has(key)))
}

// 보는 종목이 같으면 watched 참조를 유지해 데몬에 다시 선언하지 않음
function rewatch(
  state: StreamStoreState,
  primary: readonly StreamSymbol[],
  extras: Readonly<Record<string, readonly StreamSymbol[]>>,
): Partial<StreamStoreState> {
  const watched = composeWatched(primary, extras)
  const isSame =
    watched.length === state.watched.length &&
    watched.every(
      (s, i) => symbolKey(s) === symbolKey(state.watched[i] ?? s) && state.watched[i] !== undefined,
    )
  const keep = new Set(watched.map(symbolKey))
  return {
    primary,
    extras,
    watched: isSame ? state.watched : watched,
    quotes: pick(state.quotes, keep),
    orderBooks: pick(state.orderBooks, keep),
    liveCandles: pick(state.liveCandles, keep),
    liveMinutes: pick(state.liveMinutes, keep),
  }
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
      const liveMinutes = { ...state.liveMinutes }
      let feed = state.feed
      let indexTicker = state.indexTicker
      const watched = new Set(state.watched.map(symbolKey))
      for (const message of messages) {
        const key = message.symbol === undefined ? null : symbolKey(message.symbol)
        if (key !== null && !watched.has(key)) continue
        if (message.type === 'quote' && key !== null && message.quote !== undefined)
          quotes[key] = message.quote
        else if (message.type === 'orderBook' && key !== null && message.orderBook !== undefined)
          orderBooks[key] = message.orderBook
        else if (
          message.type === 'liveCandle' &&
          key !== null &&
          message.liveCandle !== undefined
        ) {
          liveCandles[key] = message.liveCandle
          liveMinutes[key] = withMinute(liveMinutes[key], message.liveCandle)
        } else if (message.type === 'feedState' && message.feedState !== undefined)
          feed = message.feedState
        else if (message.type === 'indexTicker' && message.indexTicker !== undefined)
          indexTicker = message.indexTicker
      }
      return { quotes, orderBooks, liveCandles, liveMinutes, feed, indexTicker }
    }),
  setConnection: (connection) => set({ connection }),
  // 더는 보지 않는 종목의 마지막 값은 버림.
  // 나중에 그 종목으로 돌아오면 새 체결이 올 때까지 "—" 로 두어 옛 가격이 실시간 값처럼 보이지 않게 함
  setWatched: (symbols) => set((state) => rewatch(state, [...symbols], state.extras)),
  setExtraWatched: (source, symbols) =>
    set((state) => {
      const extras = { ...state.extras }
      if (symbols.length === 0) delete extras[source]
      else extras[source] = [...symbols]
      return rewatch(state, state.primary, extras)
    }),
  reset: () => set(EMPTY),
}))
