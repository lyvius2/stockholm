import type { StockholmBridge } from './bridge'

declare global {
  interface Window {
    readonly stockholm: StockholmBridge
  }
}

export {}
