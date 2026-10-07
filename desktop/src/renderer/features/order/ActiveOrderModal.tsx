import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useState } from 'react'
import type { ApiOrderListing } from '@renderer/generated/api-order-listing'
import { tradingApi } from '@renderer/data/api/trading'
import { ApiError } from '@renderer/data/client/Client'
import { localClient } from '@renderer/data/client/LocalClient'
import type { StreamSymbol } from '@renderer/data/stream/MarketStream'
import { symbolKey } from '@renderer/data/stream/store'
import {
  pendingKey,
  useOrderUiStore,
  type OrderSide,
  type PendingPlace,
} from '@renderer/data/store/orderUi'
import { formatDecimal } from '@renderer/shared/format/decimal'
import { AmendModal } from './AmendModal'
import { OrderModal } from './OrderModal'
import { ORDER_QUERY_KEYS } from './OrderTabs'

/** 주문 가능 정보는 주문 직후·30초마다 다시 받음(증권사 ACCOUNT 그룹 초당 1회). */
export const TICKET_REFRESH_MS = 30_000

const SIDE_LABEL: Record<OrderSide, string> = { BUY: '매수', SELL: '매도' }

/**
 * 지금 열린 모달 하나(주문·정정·취소 확인)를 store 에서 읽어 그림.
 * 모달은 한 번에 하나만 열리므로 여는 쪽은 store 의 open 을 부르고, 닫는 쪽은 close 를 부름.
 */
export function ActiveOrderModal() {
  const active = useOrderUiStore((s) => s.active)
  const close = useOrderUiStore((s) => s.close)
  const queryClient = useQueryClient()
  const refreshOrders = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: ORDER_QUERY_KEYS.open })
    void queryClient.invalidateQueries({ queryKey: ORDER_QUERY_KEYS.today })
  }, [queryClient])

  if (active === null) return null
  if (active.kind === 'order') {
    return (
      <PlaceOrder
        key={`${active.side}:${symbolKey(active.symbol)}:${active.resume?.key ?? ''}`}
        symbol={active.symbol}
        side={active.side}
        resume={active.resume}
        onClose={close}
        onPlaced={refreshOrders}
      />
    )
  }
  if (active.kind === 'amend') {
    return (
      <AmendModal
        key={`${active.order.brokerOrderId}:${active.resume?.key ?? ''}`}
        order={active.order}
        resume={active.resume}
        onClose={close}
        onChanged={refreshOrders}
      />
    )
  }
  return <CancelConfirm order={active.order} onClose={close} onChanged={refreshOrders} />
}

function PlaceOrder({
  symbol,
  side,
  resume,
  onClose,
  onPlaced,
}: {
  readonly symbol: StreamSymbol
  readonly side: OrderSide
  readonly resume: PendingPlace | undefined
  readonly onClose: () => void
  readonly onPlaced: () => void
}) {
  const queryClient = useQueryClient()
  const ticket = useQuery({
    queryKey: ORDER_QUERY_KEYS.ticket(symbol),
    queryFn: () => tradingApi(localClient).ticket(symbol),
    refetchInterval: TICKET_REFRESH_MS,
    retry: false,
  })
  return (
    <OrderModal
      symbol={symbol}
      side={side}
      ticket={ticket.data ?? null}
      resume={resume}
      onClose={onClose}
      onPlaced={() => {
        void queryClient.invalidateQueries({ queryKey: ORDER_QUERY_KEYS.ticket(symbol) })
        onPlaced()
      }}
    />
  )
}

/**
 * 취소 확인 창(F14, 확인 한 번).
 * 응답을 못 받으면 같은 주문을 다시 보내지 않고 "취소 확인 중" 으로 잠근 뒤 목록을 새로 받아 상태로 확정함.
 */
function CancelConfirm({
  order,
  onClose,
  onChanged,
}: {
  readonly order: ApiOrderListing
  readonly onClose: () => void
  readonly onChanged: () => void
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function confirmCancel() {
    const key = pendingKey('cancel', order.brokerOrderId)
    const store = useOrderUiStore.getState()
    store.begin({ kind: 'cancel', key, brokerOrderId: order.brokerOrderId, state: 'sending' })
    setBusy(true)
    setError(null)
    try {
      await tradingApi(localClient).cancel(order.brokerOrderId)
      useOrderUiStore.getState().resolve(key)
      onClose()
      onChanged()
    } catch (e) {
      if (e instanceof ApiError) {
        useOrderUiStore.getState().resolve(key)
        setError(`취소하지 못했습니다: ${e.message}`)
        return
      }
      useOrderUiStore.getState().markUnresolved(key)
      onClose()
      onChanged()
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="order-modal" role="alertdialog" aria-label="취소 확인" data-side={order.side}>
      <p>
        {order.symbol.code} {SIDE_LABEL[order.side]} 잔량 {formatDecimal(order.remaining)}주를
        취소합니다.
      </p>
      {error !== null && (
        <p className="warn" role="alert">
          {error}
        </p>
      )}
      <div className="actions">
        <button type="button" disabled={busy} onClick={() => void confirmCancel()}>
          주문 취소
        </button>
        <button type="button" disabled={busy} onClick={onClose}>
          돌아가기
        </button>
      </div>
    </div>
  )
}
