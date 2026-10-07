import { useQuery } from '@tanstack/react-query'
import { useEffect } from 'react'
import { tradingApi } from '@renderer/data/api/trading'
import { localClient } from '@renderer/data/client/LocalClient'
import { useStockStore } from '@renderer/data/store/stock'
import {
  useIsSending,
  useOrderUiStore,
  useUnresolvedPlaces,
  type OrderSide,
  type PendingPlace,
} from '@renderer/data/store/orderUi'
import type { StreamSymbol } from '@renderer/data/stream/MarketStream'
import { symbolKey } from '@renderer/data/stream/store'
import { useLiveQuote } from '@renderer/data/stream/useLivePrices'
import { formatDecimal, formatMoney } from '@renderer/shared/format/decimal'
import { ActiveOrderModal, TICKET_REFRESH_MS } from './ActiveOrderModal'
import { ORDER_QUERY_KEYS, OrderTabs } from './OrderTabs'

const SIDE_LABEL: Record<OrderSide, string> = { BUY: '매수', SELL: '매도' }

/**
 * 3번 영역: 왼쪽은 현재가·매수 가능 금액·보유 요약과 매수/매도 버튼, 오른쪽은 세 탭 표.
 * 주문·정정·취소 모달은 한 번에 하나만 열리며 store 의 열린 모달을 ActiveOrderModal 이 그림.
 * 종목이 바뀌면 열려 있던 모달은 닫히지만, 보내는 중·결과 모름 요청은 store 에 남음.
 */
export function OrderArea() {
  const symbol = useStockStore((s) => s.current)
  useEffect(() => {
    useOrderUiStore.getState().close()
  }, [symbol])

  return (
    <div className="order-area">
      {symbol === null ? (
        <p className="placeholder">종목을 고르면 주문할 수 있습니다</p>
      ) : (
        <OrderOf key={symbolKey(symbol)} symbol={symbol} />
      )}
      <OrderTabs />
      <ActiveOrderModal />
    </div>
  )
}

function OrderOf({ symbol }: { readonly symbol: StreamSymbol }) {
  const { quote, isDelayed } = useLiveQuote(symbol)
  const open = useOrderUiStore((s) => s.open)
  const isSending = useIsSending()
  const ticket = useQuery({
    queryKey: ORDER_QUERY_KEYS.ticket(symbol),
    queryFn: () => tradingApi(localClient).ticket(symbol),
    refetchInterval: TICKET_REFRESH_MS,
    retry: false,
  })
  const currency = symbol.market === 'KR' ? 'KRW' : 'USD'
  return (
    <div className="order-left">
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
      <UnresolvedPlacesBanner />
      <div className="order-buttons">
        <button
          type="button"
          className="buy"
          disabled={isSending}
          onClick={() => open({ kind: 'order', symbol, side: 'BUY' })}
        >
          매수
        </button>
        <button
          type="button"
          className="sell"
          disabled={isSending}
          onClick={() => open({ kind: 'order', symbol, side: 'SELL' })}
        >
          매도
        </button>
      </div>
    </div>
  )
}

/** 응답을 못 받은 주문 안내. 모달을 닫았어도 같은 멱등 키로만 결과를 확인하게 함. */
function UnresolvedPlacesBanner() {
  const unresolved = useUnresolvedPlaces()
  const open = useOrderUiStore((s) => s.open)
  if (unresolved.length === 0) return null
  return (
    <>
      {unresolved.map((pending) => (
        <div key={pending.key} className="unresolved-banner" role="status">
          <span>{describe(pending)} — 결과를 모릅니다</span>
          <span className="spacer" />
          <button
            type="button"
            onClick={() =>
              open({
                kind: 'order',
                symbol: pending.request.symbol,
                side: pending.request.side,
                resume: pending,
              })
            }
          >
            결과 확인
          </button>
        </div>
      ))}
    </>
  )
}

function describe(pending: PendingPlace): string {
  const { symbol, side, quantity } = pending.request
  return `${symbol.code} ${SIDE_LABEL[side]} ${formatDecimal(quantity ?? '')}주`
}
