import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { tradingApi } from '@renderer/data/api/trading'
import { localClient } from '@renderer/data/client/LocalClient'
import { useStockStore } from '@renderer/data/store/stock'
import type { StreamSymbol } from '@renderer/data/stream/MarketStream'
import { symbolKey } from '@renderer/data/stream/store'
import { useLiveQuote } from '@renderer/data/stream/useLivePrices'
import { formatDecimal, formatMoney } from '@renderer/shared/format/decimal'
import { OrderModal, type OrderSide } from './OrderModal'

/** 주문 가능 정보는 주문 직후·30초마다 다시 받음(증권사 ACCOUNT 그룹 초당 1회). */
const TICKET_REFRESH_MS = 30_000

/**
 * 3번 영역 왼쪽: 현재가·매수 가능 금액·보유 요약과 매수/매도 버튼.
 * 주문 입력은 모달에서 함. 오른쪽 세 탭 표는 다음 조각.
 */
export function OrderArea() {
  const symbol = useStockStore((s) => s.current)
  if (symbol === null) return <p className="placeholder">종목을 고르면 주문할 수 있습니다</p>
  // 종목이 바뀌면 통째로 다시 만들어 열려 있던 모달·확인 창이 남지 않게 함
  return <OrderOf key={symbolKey(symbol)} symbol={symbol} />
}

function OrderOf({ symbol }: { readonly symbol: StreamSymbol }) {
  const { quote, isDelayed } = useLiveQuote(symbol)
  const [side, setSide] = useState<OrderSide | null>(null)
  const ticket = useQuery({
    queryKey: ['orders', 'ticket', symbol.market, symbol.code],
    queryFn: () => tradingApi(localClient).ticket(symbol),
    refetchInterval: TICKET_REFRESH_MS,
    retry: false,
  })
  const currency = symbol.market === 'KR' ? 'KRW' : 'USD'
  return (
    <div className="order-area">
      <dl className="order-summary">
        <dt>현재가</dt>
        <dd className="num">
          {quote === undefined ? '—' : formatMoney(quote.last.amount, quote.last.currency)}
          {isDelayed && <span className="chip warn-chip">지연</span>}
        </dd>
        <dt>매수 가능</dt>
        <dd className="num">
          {ticket.data === undefined ? '—' : formatMoney(ticket.data.buyingPower.amount, currency)}
        </dd>
        <dt>매도 가능</dt>
        <dd className="num">
          {ticket.data === undefined ? '—' : `${formatDecimal(ticket.data.sellableQuantity)}주`}
        </dd>
        {ticket.data?.priceLimits && (
          <>
            <dt>상·하한</dt>
            <dd className="num">
              {ticket.data.priceLimits.upper
                ? formatDecimal(ticket.data.priceLimits.upper.amount)
                : '—'}
              {' / '}
              {ticket.data.priceLimits.lower
                ? formatDecimal(ticket.data.priceLimits.lower.amount)
                : '—'}
            </dd>
          </>
        )}
      </dl>
      {ticket.isError && <p className="warn">주문 가능 정보를 받지 못했습니다</p>}
      <div className="order-buttons">
        <button type="button" className="buy" onClick={() => setSide('BUY')}>
          매수
        </button>
        <button type="button" className="sell" onClick={() => setSide('SELL')}>
          매도
        </button>
      </div>
      {side !== null && (
        <OrderModal
          key={side}
          symbol={symbol}
          side={side}
          ticket={ticket.data ?? null}
          onClose={() => setSide(null)}
          onPlaced={() => void ticket.refetch()}
        />
      )}
    </div>
  )
}
