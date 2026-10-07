import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { useStockStore } from '@renderer/data/store/stock'
import { useStreamStore } from '@renderer/data/stream/store'
import { OrderArea } from '@renderer/features/order/OrderArea'
import { installBridge, ok, status } from '../support/bridge'

const samsung = { market: 'KR', code: '005930' } as const
const nvidia = { market: 'US', code: 'NVDA' } as const

describe('OrderArea', () => {
  beforeEach(() => {
    useStockStore.getState().reset()
    useStreamStore.getState().reset()
    installBridge(({ path }) =>
      path.startsWith('/orders/ticket')
        ? ok({
            symbol: samsung,
            buyingPower: { amount: '1000000', currency: 'KRW' },
            sellableQuantity: '10',
            priceLimits: null,
            commissionRate: null,
          })
        : status(404),
    )
  })

  function renderArea() {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    return render(
      <QueryClientProvider client={client}>
        <OrderArea />
      </QueryClientProvider>,
    )
  }

  it('모달을 연 채 종목이 바뀌면 모달과 입력이 사라지고, 방향을 바꾸면 새 모달로 시작함', () => {
    act(() => useStockStore.getState().select(samsung))
    renderArea()
    fireEvent.click(screen.getByRole('button', { name: '매수' }))
    const buyModal = screen.getByRole('dialog', { name: '매수 주문' })
    fireEvent.change(screen.getByLabelText('수량 (주)'), { target: { value: '7' } })
    expect(buyModal.textContent).toContain('005930')

    act(() => useStockStore.getState().select(nvidia))
    expect(screen.queryByRole('dialog')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: '매도' }))
    const sellModal = screen.getByRole('dialog', { name: '매도 주문' })
    expect(sellModal.textContent).toContain('NVDA')
    expect(screen.getByLabelText('수량 (주)')).toHaveProperty('value', '')

    fireEvent.click(screen.getByRole('button', { name: '매수' }))
    expect(screen.getByRole('dialog', { name: '매수 주문' })).toBeDefined()
    expect(screen.queryByRole('dialog', { name: '매도 주문' })).toBeNull()
  })
})
