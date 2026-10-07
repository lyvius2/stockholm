import { act, render, waitFor } from '@testing-library/react'
import type { ApiRequest } from '../../src/preload/bridge'
import { localClient } from '@renderer/data/client/LocalClient'
import { useStockStore } from '@renderer/data/store/stock'
import { useStreamStore } from '@renderer/data/stream/store'
import { useCurrentStock } from '@renderer/data/stock/useCurrentStock'
import { installBridge, ok, status } from '../support/bridge'

const samsung = { market: 'KR', code: '005930' } as const
const nvidia = { market: 'US', code: 'NVDA' } as const
// 테스트에서는 디바운스를 짧게 둠(설계는 2초)
const DEBOUNCE_MS = 60

function Host({ isActive }: { readonly isActive: boolean }) {
  useCurrentStock(isActive, localClient, DEBOUNCE_MS)
  return null
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

describe('useCurrentStock', () => {
  const requests: ApiRequest[] = []

  beforeEach(() => {
    requests.length = 0
    useStockStore.getState().reset()
    useStreamStore.getState().reset()
    installBridge((request) => {
      requests.push(request)
      if (request.path === '/session/start-stock')
        return ok({ symbol: samsung, reason: 'LAST_VIEWED' })
      if (request.path.startsWith('/stocks/KR/005930'))
        return ok({
          symbol: samsung,
          name: '삼성전자',
          englishName: 'Samsung',
          board: 'KOSPI',
          securityType: 'STOCK',
          isPreferred: false,
        })
      if (request.path === '/session/last-viewed-stock') return ok(null)
      return status(404)
    })
  })

  afterEach(() => vi.useRealTimers())

  const savedSymbols = () =>
    requests
      .filter((r) => r.path === '/session/last-viewed-stock')
      .map((r) => (r.body as { symbol: typeof samsung }).symbol)

  it('로그인하면 시작 종목을 받아 현재 종목·요약·실시간 구독을 채우고, 디바운스 뒤 직전 종목을 저장함', async () => {
    vi.useFakeTimers()
    const view = render(<Host isActive />)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(useStockStore.getState().current).toEqual(samsung)
    expect(useStockStore.getState().startReason).toBe('LAST_VIEWED')
    expect(useStockStore.getState().summary?.name).toBe('삼성전자')
    expect(useStreamStore.getState().watched).toEqual([samsung])
    expect(savedSymbols()).toEqual([])

    await act(async () => {
      await vi.advanceTimersByTimeAsync(DEBOUNCE_MS - 1)
    })
    expect(savedSymbols()).toEqual([])
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1)
    })
    expect(savedSymbols()).toEqual([samsung])

    view.unmount()
    expect(useStockStore.getState().current).toBeNull()
    expect(useStreamStore.getState().watched).toEqual([])
  })

  it('디바운스 안에 종목을 다시 바꾸면 마지막 종목만 저장됨', async () => {
    render(<Host isActive />)
    await waitFor(() => expect(useStockStore.getState().current).toEqual(samsung))

    act(() => useStockStore.getState().select(nvidia))
    await sleep(DEBOUNCE_MS / 2)
    act(() => useStockStore.getState().select(samsung))

    await waitFor(() => expect(savedSymbols()).toEqual([samsung]))
    await sleep(DEBOUNCE_MS)
    expect(savedSymbols()).toEqual([samsung])
    expect(useStreamStore.getState().watched).toEqual([samsung])
  })
})
