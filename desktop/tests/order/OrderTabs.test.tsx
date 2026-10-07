import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ApiRequest } from '../../src/preload/bridge'
import { useOrderUiStore } from '@renderer/data/store/orderUi'
import { useStreamStore } from '@renderer/data/stream/store'
import { ActiveOrderModal } from '@renderer/features/order/ActiveOrderModal'
import { OrderTabs } from '@renderer/features/order/OrderTabs'
import { installBridge, ok, status } from '../support/bridge'

const samsung = { market: 'KR', code: '005930' } as const

const openOrder = {
  brokerOrderId: 'B-1',
  replacesBrokerOrderId: null,
  symbol: samsung,
  side: 'BUY',
  kind: 'LIMIT',
  timeInForce: 'DAY',
  limitPrice: { amount: '74200', currency: 'KRW' },
  quantity: '10',
  orderAmount: null,
  status: 'PARTIALLY_FILLED',
  filledQuantity: '4',
  remaining: '6',
  averageFilledPrice: { amount: '74200', currency: 'KRW' },
  filledAmount: { amount: '296800', currency: 'KRW' },
  origin: 'AUTO_BUY',
  isPlacedByStockholm: true,
  canAmend: true,
  canCancel: true,
  orderedAt: '2026-10-07T01:00:00Z',
  updatedAt: '2026-10-07T01:00:00Z',
  closedAt: null,
}
const inFlight = {
  ...openOrder,
  brokerOrderId: 'B-2',
  status: 'PENDING_CANCEL',
  canAmend: false,
  canCancel: false,
}
const marketOrder = {
  ...openOrder,
  brokerOrderId: 'B-M',
  kind: 'MARKET',
  limitPrice: null,
  status: 'PENDING',
  filledQuantity: '0',
  remaining: '10',
  canAmend: false,
  canCancel: true,
}
const valuation = {
  byMarket: [
    {
      market: 'KR',
      marketValue: { amount: '742000', currency: 'KRW' },
      purchaseAmount: { amount: '700000', currency: 'KRW' },
      profitLoss: { amount: '42000', currency: 'KRW' },
      returnRate: '0.06',
      todayChange: null,
      risers: 1,
      fallers: 0,
      holdings: [
        {
          symbol: samsung,
          quantity: '10',
          lastPrice: { amount: '74200', currency: 'KRW' },
          purchaseAmount: { amount: '700000', currency: 'KRW' },
          marketValue: { amount: '742000', currency: 'KRW' },
          profitLoss: { amount: '42000', currency: 'KRW' },
          previousClose: null,
          todayChange: null,
        },
      ],
    },
  ],
  totalMarketValueKrw: null,
  totalProfitLossKrw: null,
  totalReturnRate: null,
  fxUsdKrw: null,
  cashBuyingPower: {},
  isDelayed: false,
  asOf: '2026-10-07T01:00:00Z',
}

type AmendBody = { clientOrderId: string }

describe('OrderTabs', () => {
  const requests: ApiRequest[] = []
  let onCancel: () => ReturnType<typeof ok> | Promise<never>
  let onAmend: () => ReturnType<typeof ok> | Promise<never>
  // 미체결 목록 응답을 붙잡아 두는 문. 결과를 모르는 취소가 목록이 올 때까지 잠기는지 보려고 씀
  let holdOpenList: Promise<void> | null

  beforeEach(() => {
    requests.length = 0
    holdOpenList = null
    useOrderUiStore.getState().reset()
    useStreamStore.getState().reset()
    onCancel = () => ok({ state: 'REQUESTED', cancelBrokerOrderId: 'B-9' })
    onAmend = () => ok({ clientOrderId: 'x', state: 'ACCEPTED', brokerOrderId: 'B-7' })
    installBridge(async (request) => {
      requests.push(request)
      if (request.path === '/orders/open') {
        if (holdOpenList !== null) await holdOpenList
        return ok([openOrder, inFlight, marketOrder])
      }
      if (request.path === '/orders/today')
        return ok([
          {
            ...openOrder,
            brokerOrderId: 'B-0',
            status: 'CANCELLED',
            canAmend: false,
            canCancel: false,
            closedAt: '2026-10-07T02:00:00Z',
          },
        ])
      if (request.path === '/portfolio/valuation') return ok(valuation)
      if (request.path === '/orders/B-1/cancel') return onCancel()
      if (request.path === '/orders/B-1/amend') return onAmend()
      return status(404)
    })
  })

  function renderTabs() {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    return render(
      <QueryClientProvider client={client}>
        <OrderTabs />
        <ActiveOrderModal />
      </QueryClientProvider>,
    )
  }

  async function openTab() {
    fireEvent.click(await screen.findByRole('tab', { name: '미체결 3' }))
  }

  function row(brokerOrderId: string): HTMLElement {
    const found = document.querySelector(`[data-order-id="${brokerOrderId}"]`)
    if (!(found instanceof HTMLElement)) throw new Error(`행 없음: ${brokerOrderId}`)
    return found
  }

  function buttonIn(brokerOrderId: string, name: string): HTMLButtonElement {
    const button = Array.from(row(brokerOrderId).querySelectorAll('button')).find(
      (b) => b.textContent === name,
    )
    if (button === undefined) throw new Error(`버튼 없음: ${brokerOrderId} ${name}`)
    return button
  }

  it('세 탭의 건수를 보이고, 미체결 행은 체결/잔량·출처·상태 칩과 함께 처리 중이면 버튼이 잠김', async () => {
    renderTabs()
    expect(await screen.findByRole('tab', { name: '보유 1' })).toBeDefined()
    expect(await screen.findByRole('tab', { name: '미체결 3' })).toBeDefined()
    expect(screen.getByRole('tab', { name: '오늘 체결 1' })).toBeDefined()

    await openTab()
    expect(row('B-1').textContent).toContain('4 / 6')
    expect(row('B-1').textContent).toContain('자동')
    expect(row('B-1').textContent).toContain('부분 체결')
    expect(row('B-2').textContent).toContain('처리 중')
    expect(buttonIn('B-1', '취소').disabled).toBe(false)
    expect(buttonIn('B-2', '정정').disabled).toBe(true)
    expect(buttonIn('B-2', '취소').disabled).toBe(true)
  })

  it('시장가 미체결은 정정은 잠기고 취소만 열림', async () => {
    renderTabs()
    await openTab()
    expect(row('B-M').textContent).toContain('시장가')
    expect(buttonIn('B-M', '정정').disabled).toBe(true)
    expect(buttonIn('B-M', '취소').disabled).toBe(false)
  })

  it('취소는 확인 창을 한 번 거친 뒤 보내고 목록을 다시 받음', async () => {
    renderTabs()
    await openTab()
    fireEvent.click(buttonIn('B-1', '취소'))

    const confirm = screen.getByRole('alertdialog', { name: '취소 확인' })
    expect(confirm.textContent).toContain('잔량 6주')
    expect(requests.some((r) => r.path.endsWith('/cancel'))).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: '주문 취소' }))

    await waitFor(() =>
      expect(requests.filter((r) => r.path === '/orders/B-1/cancel')).toHaveLength(1),
    )
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(requests.filter((r) => r.path === '/orders/open').length).toBeGreaterThanOrEqual(2)
  })

  it('취소 응답을 못 받으면 다시 보내지 않고 목록이 새로 올 때까지 그 행을 잠금', async () => {
    onCancel = () => Promise.reject(new Error('IPC 끊김'))
    renderTabs()
    await openTab()
    let releaseOpenList: () => void = () => undefined
    holdOpenList = new Promise<void>((resolve) => {
      releaseOpenList = resolve
    })
    fireEvent.click(buttonIn('B-1', '취소'))
    fireEvent.click(screen.getByRole('button', { name: '주문 취소' }))

    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(row('B-1').textContent).toContain('취소 확인 중')
    expect(buttonIn('B-1', '취소').disabled).toBe(true)
    expect(buttonIn('B-1', '정정').disabled).toBe(true)
    expect(requests.filter((r) => r.path === '/orders/B-1/cancel')).toHaveLength(1)

    releaseOpenList()
    await waitFor(() => expect(row('B-1').textContent).not.toContain('취소 확인 중'))
    expect(buttonIn('B-1', '취소').disabled).toBe(false)
  })

  it('데몬이 거부한 취소는 확인 창에 사유를 보이고 행을 잠그지 않음', async () => {
    onCancel = () => status(409, { code: 'ConflictException', message: '이미 체결됨' })
    renderTabs()
    await openTab()
    fireEvent.click(buttonIn('B-1', '취소'))
    fireEvent.click(screen.getByRole('button', { name: '주문 취소' }))

    expect((await screen.findByRole('alert')).textContent).toContain('이미 체결됨')
    fireEvent.click(screen.getByRole('button', { name: '돌아가기' }))
    expect(row('B-1').textContent).not.toContain('취소 확인 중')
    expect(buttonIn('B-1', '취소').disabled).toBe(false)
  })

  it('정정 응답을 못 받으면 모달을 닫고 탭을 오가도 같은 키로만 결과를 확인함', async () => {
    let calls = 0
    onAmend = () => {
      calls += 1
      return calls === 1
        ? Promise.reject(new Error('IPC 끊김'))
        : ok({ clientOrderId: 'x', state: 'ACCEPTED', brokerOrderId: 'B-7' })
    }
    renderTabs()
    await openTab()
    fireEvent.click(buttonIn('B-1', '정정'))
    fireEvent.click(screen.getByRole('button', { name: '정정 주문' }))
    fireEvent.click(screen.getByRole('button', { name: '정정 주문' }))
    expect((await screen.findByRole('alert')).textContent).toContain('접수됐을 수 있으니')

    fireEvent.click(screen.getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    fireEvent.click(screen.getByRole('tab', { name: '보유 1' }))
    fireEvent.click(screen.getByRole('tab', { name: '미체결 3' }))

    expect(row('B-1').textContent).toContain('정정 확인 필요')
    expect(buttonIn('B-1', '취소').disabled).toBe(true)
    fireEvent.click(buttonIn('B-1', '정정 확인'))
    expect(screen.getByLabelText('새 지정가 (KRW)')).toHaveProperty('disabled', true)
    fireEvent.click(screen.getByRole('button', { name: '같은 요청으로 결과 확인' }))

    await waitFor(() =>
      expect(requests.filter((r) => r.path === '/orders/B-1/amend')).toHaveLength(2),
    )
    const amends = requests.filter((r) => r.path === '/orders/B-1/amend')
    expect((amends[1]?.body as AmendBody).clientOrderId).toBe(
      (amends[0]?.body as AmendBody).clientOrderId,
    )
    expect((await screen.findByRole('status')).textContent).toContain('B-7')
    fireEvent.click(screen.getAllByRole('button', { name: '닫기' }).at(-1) as HTMLElement)
    await waitFor(() => expect(row('B-1').textContent).not.toContain('정정 확인 필요'))
  })

  it('모달은 한 번에 하나만 열리고, 보내는 중에는 다른 모달을 열지 못함', async () => {
    onAmend = () => new Promise<never>(() => undefined)
    renderTabs()
    await openTab()
    fireEvent.click(buttonIn('B-1', '정정'))
    expect(screen.getByRole('dialog', { name: '매수 정정' })).toBeDefined()
    fireEvent.click(buttonIn('B-M', '취소'))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.getByRole('alertdialog', { name: '취소 확인' })).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: '돌아가기' }))

    fireEvent.click(buttonIn('B-1', '정정'))
    fireEvent.click(screen.getByRole('button', { name: '정정 주문' }))
    fireEvent.click(screen.getByRole('button', { name: '정정 주문' }))
    await waitFor(() => expect(requests.some((r) => r.path === '/orders/B-1/amend')).toBe(true))

    fireEvent.click(buttonIn('B-M', '취소'))
    expect(screen.getByRole('dialog', { name: '매수 정정' })).toBeDefined()
    expect(screen.queryByRole('alertdialog', { name: '취소 확인' })).toBeNull()
  })

  it('정정은 모달을 열고, 오늘 체결 탭은 취소된 부분 체결을 그렇게 표시함', async () => {
    renderTabs()
    await openTab()
    fireEvent.click(buttonIn('B-1', '정정'))
    expect(screen.getByRole('dialog', { name: '매수 정정' })).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('dialog')).toBeNull()

    fireEvent.click(screen.getByRole('tab', { name: '오늘 체결 1' }))
    expect(screen.getByRole('table', { name: '오늘 체결' }).textContent).toContain(
      '취소 · 부분 체결',
    )
  })
})
