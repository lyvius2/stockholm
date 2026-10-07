import { useEffect, useState } from 'react'
import type { ApiOrderBook } from '@renderer/generated/api-order-book'
import type { ApiQuote } from '@renderer/generated/api-quote'
import { marketApi } from '@renderer/data/api/market'
import { localClient } from '@renderer/data/client/LocalClient'
import type { Client } from '@renderer/data/client/Client'
import type { StreamSymbol } from '@renderer/data/stream/MarketStream'
import { symbolKey } from '@renderer/data/stream/store'

/** 스트림이 끊겼을 때의 REST 폴링 간격(가격 1초·호가 2초). */
export const QUOTE_POLL_MS = 1000
export const ORDER_BOOK_POLL_MS = 2000

export interface PolledPrices {
  readonly quote: ApiQuote | null
  readonly orderBook: ApiOrderBook | null
}

interface Polled extends PolledPrices {
  /** 어느 종목을 폴링한 값인지. 종목이 바뀌면 옛 값은 보이지 않음. */
  readonly key: string
}

export interface PollIntervals {
  readonly quoteMs: number
  readonly orderBookMs: number
}

const NOTHING: PolledPrices = { quote: null, orderBook: null }
const DEFAULT_INTERVALS: PollIntervals = { quoteMs: QUOTE_POLL_MS, orderBookMs: ORDER_BOOK_POLL_MS }

/**
 * 스트림이 지연일 때만 REST 로 현재가·호가를 폴링함.
 * 스트림이 돌아오거나 종목이 바뀌면 멈추고 값은 보이지 않게 함(스트림 값이 다시 보임).
 */
export function usePollingFallback(
  symbol: StreamSymbol | null,
  isActive: boolean,
  client: Client = localClient,
  intervals: PollIntervals = DEFAULT_INTERVALS,
): PolledPrices {
  const activeKey = isActive && symbol !== null ? symbolKey(symbol) : null
  const [polled, setPolled] = useState<Polled | null>(null)

  useEffect(() => {
    if (activeKey === null || symbol === null) return undefined
    const api = marketApi(client)
    let isStopped = false
    let isQuotePending = false
    let isBookPending = false
    const keepIfSame = (previous: Polled | null): PolledPrices =>
      previous?.key === activeKey ? previous : NOTHING
    const pollQuote = () => {
      if (isQuotePending) return
      isQuotePending = true
      return api
        .quote(symbol)
        .then((quote) => {
          if (!isStopped) setPolled((p) => ({ ...keepIfSame(p), key: activeKey, quote }))
        })
        .catch(() => undefined)
        .finally(() => {
          isQuotePending = false
        })
    }
    const pollBook = () => {
      if (isBookPending) return
      isBookPending = true
      return api
        .orderBook(symbol)
        .then((orderBook) => {
          if (!isStopped) setPolled((p) => ({ ...keepIfSame(p), key: activeKey, orderBook }))
        })
        .catch(() => undefined)
        .finally(() => {
          isBookPending = false
        })
    }
    void pollQuote()
    void pollBook()
    const quoteTimer = setInterval(() => void pollQuote(), intervals.quoteMs)
    const bookTimer = setInterval(() => void pollBook(), intervals.orderBookMs)
    return () => {
      isStopped = true
      setPolled(null)
      clearInterval(quoteTimer)
      clearInterval(bookTimer)
    }
  }, [activeKey, symbol, client, intervals])

  return polled !== null && polled.key === activeKey ? polled : NOTHING
}
