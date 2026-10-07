import { create } from 'zustand'
import type { ApiOrderBook } from '@renderer/generated/api-order-book'
import type { ApiQuote } from '@renderer/generated/api-quote'
import type { SymbolKey } from './store'

export interface PolledPrices {
  readonly quote: ApiQuote | null
  readonly orderBook: ApiOrderBook | null
}

interface PollState {
  /** 스트림이 끊긴 동안 REST 로 받은 종목별 최신값. 스트림이 돌아오면 비움. */
  readonly polled: Readonly<Record<SymbolKey, PolledPrices>>
  setPolled(key: SymbolKey, prices: PolledPrices): void
  clear(): void
}

/**
 * 폴링 값은 가격 영역이 아니라 한곳(PollingFallbackAgent)이 채우고, 가격 영역·주문 모달이 같이 읽음.
 * 모달이 옛 스트림 값으로 "현재가 즉시" 를 내지 않게 하기 위함.
 */
export const usePollStore = create<PollState>((set) => ({
  polled: {},
  setPolled: (key, prices) => set((state) => ({ polled: { ...state.polled, [key]: prices } })),
  clear: () => set({ polled: {} }),
}))
