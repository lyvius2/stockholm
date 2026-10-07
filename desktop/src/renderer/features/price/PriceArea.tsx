import type { StreamOrderBookLevel } from '@renderer/generated/stream-server-message'
import { useStockStore } from '@renderer/data/store/stock'
import { useLiveOrderBook, useLiveQuote } from '@renderer/data/stream/useLivePrices'
import { formatDecimal, formatMoney } from '@renderer/shared/format/decimal'
import type { StreamSymbol } from '@renderer/data/stream/MarketStream'

/** 호가는 매도 3단·매수 3단만 보임(설계). */
const VISIBLE_LEVELS = 3

/**
 * 2번 가격 영역: 현재가를 크게, 아래 호가 3단.
 * 값은 모두 스트림(종목별 최신값, 250ms 묶음)에서 오고 끊기면 "지연" 칩.
 * 종목 정보 서랍(ⓘ)은 자리만 둠.
 */
export function PriceArea() {
  const symbol = useStockStore((s) => s.current)
  if (symbol === null) return <p className="placeholder">종목을 고르면 현재가와 호가가 보입니다</p>
  return <PriceOf symbol={symbol} />
}

function PriceOf({ symbol }: { readonly symbol: StreamSymbol }) {
  // 스트림이 끊기면 PollingFallbackAgent 가 채운 REST 값이 보이고 "지연" 칩이 붙음(D21)
  const { quote, isDelayed } = useLiveQuote(symbol)
  const book = useLiveOrderBook(symbol)
  const asks = (book?.asks ?? []).slice(0, VISIBLE_LEVELS).reverse()
  const bids = (book?.bids ?? []).slice(0, VISIBLE_LEVELS)
  return (
    <div className="price-area">
      <header className="price-head">
        <span className="price-label">현재가</span>
        {isDelayed && <span className="chip warn-chip">지연</span>}
        <span className="spacer" />
        <button type="button" disabled title="종목 정보 서랍은 다음 조각에서">
          ⓘ 정보
        </button>
      </header>
      <div className="price-last num" aria-label="현재가">
        {quote === undefined ? '—' : formatMoney(quote.last.amount, quote.last.currency)}
      </div>
      <table className="order-book" aria-label="호가">
        <tbody>
          {asks.map((level, index) => (
            <LevelRow key={`ask-${index}`} level={level} side="ask" />
          ))}
          {bids.map((level, index) => (
            <LevelRow key={`bid-${index}`} level={level} side="bid" />
          ))}
          {asks.length === 0 && bids.length === 0 && (
            <tr>
              <td className="placeholder" colSpan={2}>
                호가 대기 중
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
}

function LevelRow({ level, side }: { level: StreamOrderBookLevel; side: 'ask' | 'bid' }) {
  return (
    <tr className={`level ${side}`}>
      <td className="num">{formatDecimal(level.price.amount)}</td>
      <td className="num qty">{formatDecimal(level.quantity)}</td>
    </tr>
  )
}
