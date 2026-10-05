import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import type { StreamIndexEntry, StreamIndexTicker } from '@renderer/generated/stream-server-message'
import type { ApiIndexTicker } from '@renderer/generated/api-index-ticker'
import { marketApi } from '@renderer/data/api/market'
import { localClient } from '@renderer/data/client/LocalClient'
import { useIndexTicker } from '@renderer/data/stream/useMarketStream'
import { formatDecimal, formatPercentFromRatio, signOf } from '@renderer/shared/format/decimal'

const DISPLAY_NAMES: Record<string, string> = {
  KOSPI: 'KOSPI',
  KOSDAQ: 'KOSDAQ',
  NIKKEI225: 'NIKKEI',
  DJIA: 'DJIA',
  NASDAQ: 'NASDAQ',
  SP500: 'S&P 500',
}

/**
 * 상단 바 1행의 지수 세 개(F23).
 * 첫 값은 REST 로 받고 이후는 스트림이 5분마다 밀어 줌.
 * 값이 바뀐 항목만 왼쪽에서 오른쪽으로 슬라이딩하며 교체됨.
 */
export function IndexTicker() {
  const streamed = useIndexTicker()
  const initial = useQuery({
    queryKey: ['market', 'index-ticker'],
    queryFn: () => marketApi(localClient).indexTicker(),
    staleTime: Infinity,
    retry: false,
  })
  const ticker: StreamIndexTicker | ApiIndexTicker | null = streamed ?? initial.data ?? null
  if (ticker === null) {
    return (
      <span className="ticker" aria-label="지수 티커">
        지수 —
      </span>
    )
  }
  return (
    <span className="ticker" aria-label="지수 티커" data-delayed={ticker.isDelayed}>
      {ticker.entries.map((entry) => (
        <IndexEntry key={entry.code} entry={entry} isClosedSet={ticker.state === 'CLOSE'} />
      ))}
      {ticker.isDelayed && <span className="chip">지연</span>}
    </span>
  )
}

function IndexEntry({ entry, isClosedSet }: { entry: StreamIndexEntry; isClosedSet: boolean }) {
  const text = entryText(entry)
  const slideKey = useSlideKey(text)
  const sign =
    entry.quote?.change === null || entry.quote === null ? 'flat' : signOf(entry.quote.change)
  return (
    <span className="ticker-item" data-sign={sign}>
      <span className="ticker-name">{DISPLAY_NAMES[entry.code] ?? entry.code}</span>
      <span className="ticker-sep">|</span>
      <span key={slideKey} className="ticker-value slide-in">
        {text}
      </span>
      {entry.quote?.proxy != null && <span className="chip">{entry.quote.proxy} 프록시</span>}
      {(isClosedSet || entry.quote?.isClosed === true) && <span className="chip">종가</span>}
      {entry.state === 'UNCONFIGURED' && <span className="chip">출처 미설정</span>}
    </span>
  )
}

/** 지수 값과 등락을 한 문자열로. 값이 없으면 "—". */
export function entryText(entry: StreamIndexEntry): string {
  const quote = entry.quote
  if (quote === null) return '—'
  const value = formatDecimal(quote.value)
  if (quote.change === null || quote.changeRatio === null) return `${value} | —`
  const sign = signOf(quote.change)
  const arrow = sign === 'up' ? '▲' : sign === 'down' ? '▼' : '—'
  const change = formatDecimal(quote.change.replace('-', ''))
  const ratio = formatPercentFromRatio(quote.changeRatio.replace('-', ''))
  const ratioSign = sign === 'up' ? '+' : sign === 'down' ? '−' : ''
  return `${value} | ${arrow} ${change} (${ratioSign}${ratio})`
}

// 문자열이 바뀔 때마다 키를 올려 슬라이드 애니메이션이 다시 돌게 함
function useSlideKey(text: string): number {
  const [key, setKey] = useState(0)
  const previous = useRef(text)
  useEffect(() => {
    if (previous.current !== text) {
      previous.current = text
      setKey((k) => k + 1)
    }
  }, [text])
  return key
}
