import type { StreamOrderBook, StreamQuote } from '@renderer/generated/stream-server-message'
import type { StreamSymbol } from './MarketStream'
import { usePollStore } from './pollStore'
import { symbolKey, useStreamStore } from './store'
import { useIsFeedDelayed } from './useMarketStream'

export interface LiveQuote {
  readonly quote: StreamQuote | undefined
  /** 스트림이 끊겼거나 이 종목의 구독이 거절돼 폴링 중. */
  readonly isDelayed: boolean
  /** 값이 지금 것이라 믿을 수 있는지. 스트림이 살아 있거나 폴링으로 방금 받은 값이면 true. */
  readonly isFresh: boolean
}

/**
 * 가격 영역과 주문 모달이 같이 쓰는 현재가.
 * 스트림이 정상이면 스트림 값, 지연이면 폴링 값(없으면 옛 스트림 값이되 isFresh=false).
 */
export function useLiveQuote(symbol: StreamSymbol): LiveQuote {
  const key = symbolKey(symbol)
  const streamed = useStreamStore((state) => state.quotes[key])
  const polled = usePollStore((state) => state.polled[key]?.quote ?? null)
  const isDelayed = useIsFeedDelayed(symbol)
  if (!isDelayed) return { quote: streamed, isDelayed: false, isFresh: streamed !== undefined }
  if (polled !== null)
    return { quote: { last: polled.last, asOf: polled.asOf }, isDelayed: true, isFresh: true }
  return { quote: streamed, isDelayed: true, isFresh: false }
}

export function useLiveOrderBook(symbol: StreamSymbol): StreamOrderBook | undefined {
  const key = symbolKey(symbol)
  const streamed = useStreamStore((state) => state.orderBooks[key])
  const polled = usePollStore((state) => state.polled[key]?.orderBook ?? null)
  const isDelayed = useIsFeedDelayed(symbol)
  if (!isDelayed || polled === null) return streamed
  return { asks: polled.asks, bids: polled.bids, asOf: polled.asOf }
}
