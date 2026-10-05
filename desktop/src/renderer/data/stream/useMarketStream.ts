import { useEffect } from 'react'
import { localMarketStream, type MarketStream, type StreamSymbol } from './MarketStream'
import { symbolKey, useStreamStore } from './store'

/**
 * 스트림을 store 에 붙임: 받은 묶음과 연결 상태를 반영하고, 보는 종목이 바뀌면 다시 선언함.
 * 돌려주는 함수는 연결을 닫고 store 를 비움(로그아웃 뒤 흐려진 배경에 직전 사용자의 시세가 남지 않게).
 */
export function attachMarketStream(stream: MarketStream): () => void {
  const store = useStreamStore
  const offMessages = stream.onMessages((messages) => store.getState().applyMessages(messages))
  const offState = stream.onState((state) => store.getState().setConnection(state))
  const offWatched = store.subscribe((state, previous) => {
    if (state.watched !== previous.watched) void stream.subscribe(state.watched)
  })
  void stream.state().then((state) => store.getState().setConnection(state))
  void stream.subscribe(store.getState().watched)
  return () => {
    offMessages()
    offState()
    offWatched()
    void stream.close()
    store.getState().reset()
  }
}

/** 로그인해 있는 동안만 스트림을 열어 둠. */
export function useMarketStream(isActive: boolean, stream: MarketStream = localMarketStream): void {
  useEffect(() => {
    if (!isActive) return undefined
    return attachMarketStream(stream)
  }, [isActive, stream])
}

export function useQuote(symbol: StreamSymbol) {
  return useStreamStore((state) => state.quotes[symbolKey(symbol)])
}

export function useOrderBook(symbol: StreamSymbol) {
  return useStreamStore((state) => state.orderBooks[symbolKey(symbol)])
}

export function useLiveCandle(symbol: StreamSymbol) {
  return useStreamStore((state) => state.liveCandles[symbolKey(symbol)])
}

export function useIndexTicker() {
  return useStreamStore((state) => state.indexTicker)
}

/** 증권사 시세가 끊겼거나 데몬 연결이 없으면 true. 화면은 "지연" 을 표시함. */
export function useIsFeedDelayed(): boolean {
  return useStreamStore((state) => state.connection !== 'open' || state.feed?.isLive === false)
}
