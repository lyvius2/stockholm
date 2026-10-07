import { useMemo } from 'react'
import { create } from 'zustand'
import type { ApiOrderAmendment } from '@renderer/generated/api-order-amendment'
import type { ApiOrderListing } from '@renderer/generated/api-order-listing'
import type { ApiOrderRequest } from '@renderer/generated/api-order-request'
import type { StreamSymbol } from '../stream/MarketStream'

export type OrderSide = 'BUY' | 'SELL'
export type PendingState = 'sending' | 'unresolved'

/** 보냈거나 보내는 중인 변경. 모달이 닫혀도 남아 같은 멱등 키로만 결과를 확인하게 함. */
export interface PendingPlace {
  readonly kind: 'place'
  readonly key: string
  readonly request: ApiOrderRequest
  readonly state: PendingState
}
export interface PendingAmend {
  readonly kind: 'amend'
  readonly key: string
  readonly brokerOrderId: string
  readonly request: ApiOrderAmendment
  readonly state: PendingState
}
export interface PendingCancel {
  readonly kind: 'cancel'
  readonly key: string
  readonly brokerOrderId: string
  readonly state: PendingState
}
export type PendingChange = PendingPlace | PendingAmend | PendingCancel

/** 한 번에 하나만 열리는 모달(주문·정정·취소 확인). */
export type ActiveModal =
  | {
      readonly kind: 'order'
      readonly symbol: StreamSymbol
      readonly side: OrderSide
      readonly resume?: PendingPlace
    }
  | { readonly kind: 'amend'; readonly order: ApiOrderListing; readonly resume?: PendingAmend }
  | { readonly kind: 'cancel'; readonly order: ApiOrderListing }

interface OrderUiState {
  readonly active: ActiveModal | null
  readonly pending: Readonly<Record<string, PendingChange>>
  /** 모달을 엶. 다른 요청을 보내는 중이면 열지 않고 false. 열리면 이전 모달은 닫힘. */
  open(modal: ActiveModal): boolean
  close(): void
  begin(change: PendingChange): void
  markUnresolved(key: string): void
  resolve(key: string): void
  /**
   * 미체결 목록을 새로 받은 뒤 결과를 모르던 변경을 거둠.
   * 취소는 행의 상태가 곧 결과이고, 정정은 원주문이 목록에서 사라졌으면 더 보낼 수 없음.
   */
  reconcileAfterRefresh(openOrderIds: readonly string[]): void
  reset(): void
}

export const pendingKey = (kind: PendingChange['kind'], id: string): string => `${kind}:${id}`

function isSettledByRefresh(change: PendingChange, openOrderIds: readonly string[]): boolean {
  if (change.state !== 'unresolved') return false
  if (change.kind === 'cancel') return true
  return change.kind === 'amend' && !openOrderIds.includes(change.brokerOrderId)
}

export const useOrderUiStore = create<OrderUiState>((set, get) => ({
  active: null,
  pending: {},
  open: (modal) => {
    if (Object.values(get().pending).some((p) => p.state === 'sending')) return false
    set({ active: modal })
    return true
  },
  close: () => set({ active: null }),
  begin: (change) => set((s) => ({ pending: { ...s.pending, [change.key]: change } })),
  markUnresolved: (key) =>
    set((s) => {
      const change = s.pending[key]
      if (change === undefined) return s
      return { pending: { ...s.pending, [key]: { ...change, state: 'unresolved' } } }
    }),
  resolve: (key) =>
    set((s) => ({
      pending: Object.fromEntries(Object.entries(s.pending).filter(([k]) => k !== key)),
    })),
  reconcileAfterRefresh: (openOrderIds) =>
    set((s) => ({
      pending: Object.fromEntries(
        Object.entries(s.pending).filter(([, p]) => !isSettledByRefresh(p, openOrderIds)),
      ),
    })),
  reset: () => set({ active: null, pending: {} }),
}))

export function usePendingAmend(brokerOrderId: string): PendingAmend | undefined {
  return useOrderUiStore((s) => {
    const p = s.pending[pendingKey('amend', brokerOrderId)]
    return p?.kind === 'amend' ? p : undefined
  })
}

export function usePendingCancel(brokerOrderId: string): PendingCancel | undefined {
  return useOrderUiStore((s) => {
    const p = s.pending[pendingKey('cancel', brokerOrderId)]
    return p?.kind === 'cancel' ? p : undefined
  })
}

// 선택자가 매번 새 배열을 만들면 다시 그리기가 반복되므로 pending 을 고른 뒤 useMemo 로 거름
export function useUnresolvedPlaces(): PendingPlace[] {
  const pending = useOrderUiStore((s) => s.pending)
  return useMemo(
    () =>
      Object.values(pending).filter(
        (p): p is PendingPlace => p.kind === 'place' && p.state === 'unresolved',
      ),
    [pending],
  )
}

export function useIsSending(): boolean {
  return useOrderUiStore((s) => Object.values(s.pending).some((p) => p.state === 'sending'))
}
