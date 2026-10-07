import { useEffect } from 'react'
import { useStockStore } from '@renderer/data/store/stock'
import { usePollStore } from '@renderer/data/stream/pollStore'
import { symbolKey } from '@renderer/data/stream/store'
import { useIsFeedDelayed } from '@renderer/data/stream/useMarketStream'
import type { StreamSymbol } from '@renderer/data/stream/MarketStream'
import { usePollingFallback } from './usePollingFallback'

/**
 * 셸에 하나만 둠.
 * 현재 종목의 스트림이 지연이면 REST 폴링을 돌려 폴링 store 에 넣고, 돌아오면 비움.
 * 화면은 폴링을 직접 하지 않고 useLiveQuote·useLiveOrderBook 로 읽음.
 */
export function PollingFallbackAgent() {
  const symbol = useStockStore((s) => s.current)
  if (symbol === null) return null
  return <PollingOf symbol={symbol} />
}

function PollingOf({ symbol }: { readonly symbol: StreamSymbol }) {
  const isDelayed = useIsFeedDelayed(symbol)
  const polled = usePollingFallback(symbol, isDelayed)
  const key = symbolKey(symbol)
  useEffect(() => {
    if (isDelayed) usePollStore.getState().setPolled(key, polled)
    else usePollStore.getState().clear()
  }, [key, isDelayed, polled])
  return null
}
