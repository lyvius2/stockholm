import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import type { ApiStockSummary } from '@renderer/generated/api-stock-summary'
import { marketApi } from '@renderer/data/api/market'
import { localClient } from '@renderer/data/client/LocalClient'
import { useStockStore } from '@renderer/data/store/stock'

const RESULT_LIMIT = 12
// 타자 중에는 묻지 않고 잠시 멈추면 한 번만 물음
const INPUT_DEBOUNCE_MS = 150

/**
 * 종목 검색 팝오버(F4).
 * 상단 바 종목명을 누르거나 ⌘K 로 열고, 이름·코드·초성으로 찾아 고르면 네 영역이 바뀜.
 * Esc·바깥 클릭으로 닫힘.
 */
export function SearchPopover({ onClose }: { readonly onClose: () => void }) {
  const [text, setText] = useState('')
  const [debounced, setDebounced] = useState('')
  const [cursor, setCursor] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)
  const select = useStockStore((s) => s.select)

  useEffect(() => {
    inputRef.current?.focus()
  }, [])

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(text.trim()), INPUT_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [text])

  const results = useQuery({
    queryKey: ['stocks', 'search', debounced],
    queryFn: () => marketApi(localClient).searchStocks(debounced, RESULT_LIMIT),
    enabled: debounced.length > 0,
    staleTime: 60_000,
    retry: false,
  })
  const items: ApiStockSummary[] = results.data ?? []

  function choose(item: ApiStockSummary) {
    select(item.symbol)
    onClose()
  }

  function onKeyDown(event: React.KeyboardEvent) {
    if (event.key === 'Escape') onClose()
    else if (event.key === 'ArrowDown') setCursor((c) => Math.min(c + 1, items.length - 1))
    else if (event.key === 'ArrowUp') setCursor((c) => Math.max(c - 1, 0))
    else if (event.key === 'Enter') {
      const item = items[cursor]
      if (item !== undefined) choose(item)
    }
  }

  return (
    <div className="popover-backdrop" role="presentation" onMouseDown={onClose}>
      <div
        className="popover search-popover"
        role="dialog"
        aria-label="종목 검색"
        onMouseDown={(e) => e.stopPropagation()}
        onKeyDown={onKeyDown}
      >
        <input
          ref={inputRef}
          type="search"
          aria-label="종목 검색어"
          placeholder="종목명 · 코드 · 초성 (ㅅㅅㅈㅈ)"
          value={text}
          onChange={(e) => {
            setText(e.target.value)
            setCursor(0)
          }}
        />
        <ul role="listbox" aria-label="검색 결과">
          {items.map((item, index) => (
            <li
              key={`${item.symbol.market}:${item.symbol.code}`}
              role="option"
              aria-selected={index === cursor}
              className={index === cursor ? 'active' : undefined}
              onMouseEnter={() => setCursor(index)}
              onClick={() => choose(item)}
            >
              <span className="stock-name">{item.name}</span>
              <span className="stock-code num">{item.symbol.code}</span>
              <span className="chip">{item.board}</span>
            </li>
          ))}
          {debounced.length > 0 && !results.isPending && items.length === 0 && (
            <li className="placeholder">찾는 종목이 없습니다</li>
          )}
        </ul>
      </div>
    </div>
  )
}
