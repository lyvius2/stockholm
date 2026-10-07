import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ApiRequest } from '../../src/preload/bridge'
import { useOrderUiStore } from '@renderer/data/store/orderUi'
import { useStockStore } from '@renderer/data/store/stock'
import { useStreamStore } from '@renderer/data/stream/store'
import { OrderArea } from '@renderer/features/order/OrderArea'
import { installBridge, ok, status } from '../support/bridge'

const samsung = { market: 'KR', code: '005930' } as const
const nvidia = { market: 'US', code: 'NVDA' } as const

type Body = { clientOrderId: string }

describe('OrderArea', () => {
  const placed: ApiRequest[] = []
  let onPlace: () => ReturnType<typeof ok> | Promise<never>

  beforeEach(() => {
    placed.length = 0
    useStockStore.getState().reset()
    useStreamStore.getState().reset()
    useOrderUiStore.getState().reset()
    onPlace = () => ok({ clientOrderId: 'x', state: 'ACCEPTED', brokerOrderId: 'B-1' })
    installBridge((request) => {
      if (request.path.startsWith('/orders/ticket'))
        return ok({
          symbol: samsung,
          buyingPower: { amount: '1000000', currency: 'KRW' },
          sellableQuantity: '10',
          priceLimits: null,
          commissionRate: null,
        })
      if (request.method === 'POST' && request.path === '/orders') {
        placed.push(request)
        return onPlace()
      }
      if (request.path === '/orders/open' || request.path === '/orders/today') return ok([])
      return status(404)
    })
  })

  function renderArea() {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    return render(
      <QueryClientProvider client={client}>
        <OrderArea />
      </QueryClientProvider>,
    )
  }

  function feedFreshQuote() {
    act(() => {
      useStreamStore.getState().setConnection('open')
      useStreamStore.getState().setWatched([samsung])
      useStreamStore.getState().applyMessages([
        { type: 'feedState', feedState: { isLive: true, unavailableSymbols: [] } },
        {
          type: 'quote',
          symbol: samsung,
          quote: { last: { amount: '74300', currency: 'KRW' }, asOf: '2026-10-07T01:00:00Z' },
        },
      ])
    })
  }

  it('모달을 연 채 종목이 바뀌면 모달과 입력이 사라지고, 방향을 바꾸면 새 모달로 시작함', () => {
    act(() => useStockStore.getState().select(samsung))
    renderArea()
    fireEvent.click(screen.getByRole('button', { name: '매수' }))
    const buyModal = screen.getByRole('dialog', { name: '매수 주문' })
    fireEvent.change(screen.getByLabelText('수량 (주)', { exact: false }), {
      target: { value: '7' },
    })
    expect(buyModal.textContent).toContain('005930')

    act(() => useStockStore.getState().select(nvidia))
    expect(screen.queryByRole('dialog')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: '매도' }))
    const sellModal = screen.getByRole('dialog', { name: '매도 주문' })
    expect(sellModal.textContent).toContain('NVDA')
    expect(screen.getByLabelText('수량 (주)', { exact: false })).toHaveProperty('value', '')

    fireEvent.click(screen.getByRole('button', { name: '매수' }))
    expect(screen.getByRole('dialog', { name: '매수 주문' })).toBeDefined()
    expect(screen.queryByRole('dialog', { name: '매도 주문' })).toBeNull()
  })

  it('응답을 못 받은 주문은 모달을 닫아도 안내가 남고, 결과 확인은 같은 키로만 다시 보냄', async () => {
    let calls = 0
    onPlace = () => {
      calls += 1
      return calls === 1
        ? Promise.reject(new Error('IPC 끊김'))
        : ok({ clientOrderId: 'x', state: 'ACCEPTED', brokerOrderId: 'B-9' })
    }
    act(() => useStockStore.getState().select(samsung))
    feedFreshQuote()
    renderArea()
    fireEvent.click(screen.getByRole('button', { name: '매수' }))
    fireEvent.change(screen.getByLabelText('수량 (주)', { exact: false }), {
      target: { value: '1' },
    })
    fireEvent.click(screen.getByRole('button', { name: '현재가 즉시 매수' }))
    await screen.findByRole('alert')

    fireEvent.click(screen.getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    const banner = screen.getByRole('status')
    expect(banner.textContent).toContain('005930 매수 1주')

    fireEvent.click(screen.getByRole('button', { name: '결과 확인' }))
    expect(screen.getByLabelText('수량 (주)', { exact: false })).toHaveProperty('disabled', true)
    fireEvent.click(screen.getByRole('button', { name: '같은 주문으로 결과 확인' }))

    await waitFor(() => expect(placed).toHaveLength(2))
    expect((placed[1]?.body as Body).clientOrderId).toBe((placed[0]?.body as Body).clientOrderId)
    await waitFor(() => expect(screen.queryByText(/결과를 모릅니다/)).toBeNull())
  })
})
