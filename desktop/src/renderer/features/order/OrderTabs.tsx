import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import type { ApiOrderListing } from '@renderer/generated/api-order-listing'
import type { ApiPortfolioValuation } from '@renderer/generated/api-portfolio-valuation'
import { portfolioApi } from '@renderer/data/api/portfolio'
import { tradingApi } from '@renderer/data/api/trading'
import { localClient } from '@renderer/data/client/LocalClient'
import type { StreamSymbol } from '@renderer/data/stream/MarketStream'
import { useOrderUiStore, usePendingAmend, usePendingCancel } from '@renderer/data/store/orderUi'
import { formatDecimal, formatMoney, formatPercentFromRatio } from '@renderer/shared/format/decimal'

type Tab = 'holdings' | 'open' | 'today'

/** 미체결은 실시간 주문 채널이 화면까지 오기 전이라 짧게 폴링함. 오늘 체결·보유는 느리게. */
export const OPEN_ORDERS_REFRESH_MS = 5_000
export const TODAY_ORDERS_REFRESH_MS = 30_000
export const HOLDINGS_REFRESH_MS = 30_000

const STATUS_CHIP: Record<ApiOrderListing['status'], string> = {
  PENDING: '체결 대기',
  PARTIALLY_FILLED: '부분 체결',
  PENDING_CANCEL: '처리 중',
  PENDING_AMEND: '처리 중',
  FILLED: '체결',
  CANCELLED: '취소',
  REJECTED: '거부',
  CANCEL_REJECTED: '취소 거부',
  AMEND_REJECTED: '정정 거부',
  REPLACED: '정정됨',
  UNKNOWN: '확인 중',
}
const ORIGIN_CHIP: Record<ApiOrderListing['origin'], string> = {
  MANUAL: '수동',
  AI_RECOMMENDED: 'AI 추천',
  AUTO_BUY: '자동',
  AUTO_SELL: '자동',
}
const SIDE_LABEL = { BUY: '매수', SELL: '매도' } as const

export const ORDER_QUERY_KEYS = {
  open: ['orders', 'open'] as const,
  today: ['orders', 'today'] as const,
  holdings: ['portfolio', 'valuation'] as const,
  ticket: (symbol: StreamSymbol) => ['orders', 'ticket', symbol.market, symbol.code] as const,
}

/**
 * 3번 영역 오른쪽 표: 보유 · 미체결 · 오늘 체결 세 탭(F14).
 * 미체결 행에서 정정(모달)·취소(확인 한 번)를 열고, 처리 중·보내는 중·결과 모름 행은 잠김.
 * 모달 자체는 store 의 열린 모달 하나를 ActiveOrderModal 이 그림.
 */
export function OrderTabs() {
  const [tab, setTab] = useState<Tab>('holdings')
  const holdings = useQuery({
    queryKey: ORDER_QUERY_KEYS.holdings,
    queryFn: () => portfolioApi(localClient).valuation(),
    refetchInterval: HOLDINGS_REFRESH_MS,
    retry: false,
  })
  const open = useQuery({
    queryKey: ORDER_QUERY_KEYS.open,
    queryFn: () => tradingApi(localClient).openOrders(),
    refetchInterval: OPEN_ORDERS_REFRESH_MS,
    retry: false,
  })
  const today = useQuery({
    queryKey: ORDER_QUERY_KEYS.today,
    queryFn: () => tradingApi(localClient).todayOrders(),
    refetchInterval: TODAY_ORDERS_REFRESH_MS,
    retry: false,
  })
  // 미체결 목록을 새로 받으면 결과를 모르던 취소·정정은 행의 상태로 확정된 것이라 거둠.
  // 목록 내용이 같으면 참조도 같아 받은 시각을 함께 봄
  const { data: openOrders, dataUpdatedAt: openUpdatedAt } = open
  useEffect(() => {
    if (openOrders === undefined) return
    useOrderUiStore.getState().reconcileAfterRefresh(openOrders.map((o) => o.brokerOrderId))
  }, [openOrders, openUpdatedAt])

  const holdingCount = holdings.data?.byMarket.reduce((n, m) => n + m.holdings.length, 0) ?? 0
  return (
    <div className="order-tabs">
      <div className="tab-bar" role="tablist">
        <TabButton tab="holdings" current={tab} onSelect={setTab} label={`보유 ${holdingCount}`} />
        <TabButton
          tab="open"
          current={tab}
          onSelect={setTab}
          label={`미체결 ${open.data?.length ?? 0}`}
        />
        <TabButton
          tab="today"
          current={tab}
          onSelect={setTab}
          label={`오늘 체결 ${today.data?.length ?? 0}`}
        />
      </div>
      {tab === 'holdings' && (
        <HoldingsTable valuation={holdings.data ?? null} isError={holdings.isError} />
      )}
      {tab === 'open' && <OpenOrdersTable orders={open.data ?? []} isError={open.isError} />}
      {tab === 'today' && <TodayOrdersTable orders={today.data ?? []} isError={today.isError} />}
    </div>
  )
}

function TabButton({
  tab,
  current,
  onSelect,
  label,
}: {
  readonly tab: Tab
  readonly current: Tab
  readonly onSelect: (tab: Tab) => void
  readonly label: string
}) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={tab === current}
      className={tab === current ? 'active' : undefined}
      onClick={() => onSelect(tab)}
    >
      {label}
    </button>
  )
}

function HoldingsTable({
  valuation,
  isError,
}: {
  readonly valuation: ApiPortfolioValuation | null
  readonly isError: boolean
}) {
  if (isError) return <p className="warn">보유를 받지 못했습니다</p>
  const rows = valuation?.byMarket.flatMap((m) => m.holdings) ?? []
  return (
    <table className="order-table" aria-label="보유">
      <thead>
        <tr>
          <th>종목</th>
          <th>수량</th>
          <th>현재가</th>
          <th>손익</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((h) => (
          <tr key={`${h.symbol.market}:${h.symbol.code}`}>
            <td>{h.symbol.code}</td>
            <td className="num">{formatDecimal(h.quantity)}</td>
            <td className="num">{formatMoney(h.lastPrice.amount, h.lastPrice.currency)}</td>
            <td className={`num ${h.profitLoss.amount.startsWith('-') ? 'down' : 'up'}`}>
              {formatMoney(h.profitLoss.amount, h.profitLoss.currency)}
            </td>
          </tr>
        ))}
        {rows.length === 0 && (
          <tr>
            <td colSpan={4} className="placeholder">
              보유 종목 없음
            </td>
          </tr>
        )}
      </tbody>
    </table>
  )
}

function OpenOrdersTable({
  orders,
  isError,
}: {
  readonly orders: readonly ApiOrderListing[]
  readonly isError: boolean
}) {
  if (isError) return <p className="warn">미체결을 받지 못했습니다</p>
  return (
    <table className="order-table" aria-label="미체결">
      <thead>
        <tr>
          <th>종목</th>
          <th>구분</th>
          <th>가격</th>
          <th>체결/잔량</th>
          <th>상태</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        {orders.map((o) => (
          <OpenOrderRow key={o.brokerOrderId} order={o} />
        ))}
        {orders.length === 0 && (
          <tr>
            <td colSpan={6} className="placeholder">
              미체결 없음
            </td>
          </tr>
        )}
      </tbody>
    </table>
  )
}

/**
 * 미체결 한 행.
 * 결과를 모르는 정정이 있으면 "정정 확인" 으로 같은 키의 모달을 다시 열고,
 * 결과를 모르는 취소가 있으면 목록이 새로 올 때까지 두 버튼을 잠금.
 */
function OpenOrderRow({ order }: { readonly order: ApiOrderListing }) {
  const open = useOrderUiStore((s) => s.open)
  const pendingAmend = usePendingAmend(order.brokerOrderId)
  const pendingCancel = usePendingCancel(order.brokerOrderId)
  const isSending = pendingAmend?.state === 'sending' || pendingCancel?.state === 'sending'
  const hasUnresolvedAmend = pendingAmend?.state === 'unresolved'
  const hasUnresolvedCancel = pendingCancel?.state === 'unresolved'
  const isLocked = isSending || hasUnresolvedCancel

  return (
    <tr data-order-id={order.brokerOrderId}>
      <td>
        {order.symbol.code} <span className="chip">{ORIGIN_CHIP[order.origin]}</span>
      </td>
      <td className={order.side === 'BUY' ? 'up' : 'down'}>{SIDE_LABEL[order.side]}</td>
      <td className="num">
        {order.limitPrice
          ? formatDecimal(order.limitPrice.amount)
          : order.orderAmount
            ? formatDecimal(order.orderAmount.amount)
            : '시장가'}
      </td>
      <td className="num">
        {formatDecimal(order.filledQuantity)} / {formatDecimal(order.remaining)}
      </td>
      <td>
        <span className="chip">{STATUS_CHIP[order.status]}</span>
        {hasUnresolvedCancel && <span className="chip warn-chip">취소 확인 중</span>}
        {hasUnresolvedAmend && <span className="chip warn-chip">정정 확인 필요</span>}
      </td>
      <td className="row-actions">
        <button
          type="button"
          disabled={isLocked || (!order.canAmend && !hasUnresolvedAmend)}
          onClick={() =>
            open(
              hasUnresolvedAmend
                ? { kind: 'amend', order, resume: pendingAmend }
                : { kind: 'amend', order },
            )
          }
        >
          {hasUnresolvedAmend ? '정정 확인' : '정정'}
        </button>
        <button
          type="button"
          disabled={isLocked || hasUnresolvedAmend || !order.canCancel}
          onClick={() => open({ kind: 'cancel', order })}
        >
          취소
        </button>
      </td>
    </tr>
  )
}

function TodayOrdersTable({
  orders,
  isError,
}: {
  readonly orders: readonly ApiOrderListing[]
  readonly isError: boolean
}) {
  if (isError) return <p className="warn">오늘 체결을 받지 못했습니다</p>
  return (
    <table className="order-table" aria-label="오늘 체결">
      <thead>
        <tr>
          <th>종목</th>
          <th>구분</th>
          <th>체결가</th>
          <th>수량</th>
          <th>금액</th>
          <th>상태</th>
        </tr>
      </thead>
      <tbody>
        {orders.map((o) => (
          <tr key={o.brokerOrderId}>
            <td>{o.symbol.code}</td>
            <td className={o.side === 'BUY' ? 'up' : 'down'}>{SIDE_LABEL[o.side]}</td>
            <td className="num">
              {o.averageFilledPrice ? formatDecimal(o.averageFilledPrice.amount) : '—'}
            </td>
            <td className="num">
              {formatDecimal(o.filledQuantity)}
              {o.quantity !== null &&
                o.filledQuantity !== o.quantity &&
                ` / ${formatDecimal(o.quantity)}`}
            </td>
            <td className="num">{o.filledAmount ? formatDecimal(o.filledAmount.amount) : '—'}</td>
            <td>
              <span className="chip">
                {STATUS_CHIP[o.status]}
                {o.status === 'CANCELLED' && o.filledQuantity !== '0' && ' · 부분 체결'}
              </span>
            </td>
          </tr>
        ))}
        {orders.length === 0 && (
          <tr>
            <td colSpan={6} className="placeholder">
              오늘 체결 없음
            </td>
          </tr>
        )}
      </tbody>
    </table>
  )
}

/** 보유 탭의 수익률 표기. 평가 응답의 비율 문자열을 그대로 씀. */
export function returnRateText(ratio: string | null): string {
  return ratio === null ? '—' : formatPercentFromRatio(ratio)
}
