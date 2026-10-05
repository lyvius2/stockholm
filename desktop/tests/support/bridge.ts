import { vi } from 'vitest'
import type { ApiRequest, ApiResponse, StockholmBridge } from '../../src/preload/bridge'

export type Route = (request: ApiRequest) => ApiResponse | Promise<ApiResponse>

/** 테스트용 브리지. 경로별 응답을 정해 두고 window.stockholm 에 꽂음. */
export function installBridge(route: Route, daemonReachable = true): StockholmBridge {
  const bridge: StockholmBridge = {
    daemon: {
      status: vi.fn(async () => ({ reachable: daemonReachable, baseUrl: 'http://127.0.0.1:2609' })),
    },
    theme: { current: vi.fn(async () => 'light' as const) },
    api: { request: vi.fn(async (request: ApiRequest) => route(request)) },
    session: {
      hasSession: vi.fn(async () => false),
      clear: vi.fn(async () => undefined),
      onEnded: vi.fn(() => () => undefined),
    },
    stream: {
      subscribe: vi.fn(async () => undefined),
      close: vi.fn(async () => undefined),
      state: vi.fn(async () => 'closed' as const),
      onMessages: vi.fn(() => () => undefined),
      onState: vi.fn(() => () => undefined),
    },
    app: { version: vi.fn(async () => '0.1.0-test'), expandForMain: vi.fn(async () => undefined) },
  }
  Object.defineProperty(window, 'stockholm', { configurable: true, value: bridge })
  return bridge
}

export const ok = (body: unknown): ApiResponse => ({ status: 200, body })
export const status = (code: number, body: unknown = null): ApiResponse => ({ status: code, body })
