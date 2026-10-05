import { create } from 'zustand'
import type { ApiStockSummary } from '@renderer/generated/api-stock-summary'
import type { StreamSymbol } from '../stream/MarketStream'

export type StartReason = 'LAST_VIEWED' | 'LARGEST_POSITION' | 'DEFAULT'

interface StockState {
  /** 네 영역이 보고 있는 종목. 로그인 직후 시작 종목 규칙이 채움. */
  readonly current: StreamSymbol | null
  readonly summary: ApiStockSummary | null
  /** 시작 종목을 고른 사유. 첫 진입 토스트에만 쓰고 지움. */
  readonly startReason: StartReason | null
  select(symbol: StreamSymbol): void
  setSummary(summary: ApiStockSummary | null): void
  start(symbol: StreamSymbol, reason: StartReason): void
  consumeStartReason(): void
  reset(): void
}

export function isSameSymbol(a: StreamSymbol | null, b: StreamSymbol | null): boolean {
  return a !== null && b !== null && a.market === b.market && a.code === b.code
}

/** 현재 종목. 로그아웃하면 비워 직전 사용자의 종목이 남지 않게 함. */
export const useStockStore = create<StockState>((set, get) => ({
  current: null,
  summary: null,
  startReason: null,
  select: (symbol) => {
    if (isSameSymbol(get().current, symbol)) return
    set({ current: symbol, summary: null })
  },
  setSummary: (summary) => set({ summary }),
  start: (symbol, reason) => set({ current: symbol, summary: null, startReason: reason }),
  consumeStartReason: () => set({ startReason: null }),
  reset: () => set({ current: null, summary: null, startReason: null }),
}))
