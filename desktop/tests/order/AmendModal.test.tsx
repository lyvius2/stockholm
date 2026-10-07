import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ApiRequest } from '../../src/preload/bridge'
import type { ApiOrderListing } from '@renderer/generated/api-order-listing'
import { useOrderUiStore } from '@renderer/data/store/orderUi'
import { useStreamStore } from '@renderer/data/stream/store'
import { AmendModal } from '@renderer/features/order/AmendModal'
import { installBridge, ok, status } from '../support/bridge'

const samsung = { market: 'KR', code: '005930' } as const
const nvidia = { market: 'US', code: 'NVDA' } as const

function listing(overrides: Partial<ApiOrderListing> = {}): ApiOrderListing {
  return {
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
    averageFilledPrice: null,
    filledAmount: null,
    origin: 'MANUAL',
    isPlacedByStockholm: true,
    canAmend: true,
    canCancel: true,
    orderedAt: '2026-10-07T01:00:00Z',
    updatedAt: '2026-10-07T01:00:00Z',
    closedAt: null,
    ...overrides,
  }
}

type Body = {
  clientOrderId: string
  newLimitPrice?: { amount: string }
  newQuantity?: string
  confirmedRules: string[]
}

describe('AmendModal', () => {
  const amended: ApiRequest[] = []
  let reply: () => ReturnType<typeof ok>

  beforeEach(() => {
    amended.length = 0
    useOrderUiStore.getState().reset()
    useStreamStore.getState().reset()
    reply = () => ok({ clientOrderId: 'x', state: 'ACCEPTED', brokerOrderId: 'B-2' })
    installBridge((request) => {
      if (request.method === 'POST' && request.path.endsWith('/amend')) {
        amended.push(request)
        return reply()
      }
      return status(404)
    })
    act(() => {
      useStreamStore.getState().setConnection('open')
      useStreamStore.getState().setWatched([samsung, nvidia])
      useStreamStore.getState().applyMessages([
        { type: 'feedState', feedState: { isLive: true, unavailableSymbols: [] } },
        {
          type: 'quote',
          symbol: samsung,
          quote: { last: { amount: '74300', currency: 'KRW' }, asOf: '2026-10-07T01:00:00Z' },
        },
      ])
    })
  })

  it('국내 정정은 잔량까지의 수량과 새 가격을 확인 창을 거쳐 보내고, 잔량을 넘으면 막음', async () => {
    const onChanged = vi.fn()
    render(<AmendModal order={listing()} onClose={() => undefined} onChanged={onChanged} />)
    expect(screen.getByLabelText('새 수량 (주)', { exact: false })).toHaveProperty('value', '6')

    fireEvent.change(screen.getByLabelText('새 수량 (주)', { exact: false }), {
      target: { value: '7' },
    })
    fireEvent.click(screen.getByRole('button', { name: '정정 주문' }))
    expect((await screen.findByRole('alert')).textContent).toContain('잔량 6주까지')

    fireEvent.change(screen.getByLabelText('새 수량 (주)', { exact: false }), {
      target: { value: '5' },
    })
    fireEvent.change(screen.getByLabelText('새 지정가 (KRW)'), { target: { value: '74000' } })
    fireEvent.click(screen.getByRole('button', { name: '정정 주문' }))
    const confirm = screen.getByRole('alertdialog', { name: '정정 확인' })
    expect(confirm.textContent).toContain('74,000원')
    expect(confirm.textContent).toContain('5주')
    fireEvent.click(screen.getByRole('button', { name: '정정 주문' }))

    await waitFor(() => expect(amended).toHaveLength(1))
    expect(amended[0]?.path).toBe('/orders/B-1/amend')
    const body = amended[0]?.body as Body
    expect(body.newLimitPrice?.amount).toBe('74000')
    expect(body.newQuantity).toBe('5')
    expect(body.clientOrderId).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/)
    expect(onChanged).toHaveBeenCalledTimes(1)
  })

  it('미국 주문은 수량 입력이 잠기고 가격만 보냄', async () => {
    render(
      <AmendModal
        order={listing({ symbol: nvidia, limitPrice: { amount: '120.00', currency: 'USD' } })}
        onClose={() => undefined}
        onChanged={() => undefined}
      />,
    )
    expect(screen.getByLabelText('새 수량 (주)', { exact: false })).toHaveProperty('disabled', true)

    fireEvent.change(screen.getByLabelText('새 지정가 (USD)'), { target: { value: '121.50' } })
    fireEvent.click(screen.getByRole('button', { name: '정정 주문' }))
    fireEvent.click(screen.getByRole('button', { name: '정정 주문' }))

    await waitFor(() => expect(amended).toHaveLength(1))
    const body = amended[0]?.body as Body
    expect(body.newLimitPrice?.amount).toBe('121.50')
    expect(body.newQuantity).toBeUndefined()
  })

  it('현재가로 정정은 누르는 순간의 현재가를 확인 창에 고정해 그 값으로 보내고, 지연이면 잠김', async () => {
    render(<AmendModal order={listing()} onClose={() => undefined} onChanged={() => undefined} />)
    fireEvent.click(screen.getByRole('button', { name: '현재가로 정정' }))
    const confirm = screen.getByRole('alertdialog', { name: '정정 확인' })
    expect(confirm.textContent).toContain('74,300원')

    act(() =>
      useStreamStore.getState().applyMessages([
        {
          type: 'quote',
          symbol: samsung,
          quote: { last: { amount: '74900', currency: 'KRW' }, asOf: '2026-10-07T01:00:05Z' },
        },
      ]),
    )
    fireEvent.click(screen.getByRole('button', { name: '정정 주문' }))
    await waitFor(() => expect(amended).toHaveLength(1))
    expect((amended[0]?.body as Body).newLimitPrice?.amount).toBe('74300')

    act(() => useStreamStore.getState().setConnection('closed'))
    render(
      <AmendModal
        order={listing({ brokerOrderId: 'B-3' })}
        onClose={() => undefined}
        onChanged={() => undefined}
      />,
    )
    expect(screen.getAllByRole('button', { name: '현재가로 정정' }).at(-1)).toHaveProperty(
      'disabled',
      true,
    )
  })

  it('데몬이 확인을 요구하면(428) 노트를 보인 뒤 같은 키로 다시 보냄', async () => {
    let calls = 0
    reply = () => {
      calls += 1
      return calls === 1
        ? status(428, {
            code: 'ConfirmationRequiredException',
            message: '확인',
            notes: ['AutoExposureOverCap: 자동 매수 한도 초과'],
          })
        : ok({ clientOrderId: 'x', state: 'ACCEPTED', brokerOrderId: 'B-2' })
    }
    render(
      <AmendModal
        order={listing({ origin: 'AUTO_BUY' })}
        onClose={() => undefined}
        onChanged={() => undefined}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: '정정 주문' }))
    fireEvent.click(screen.getByRole('button', { name: '정정 주문' }))

    await screen.findByText('AutoExposureOverCap: 자동 매수 한도 초과')
    fireEvent.click(screen.getByRole('button', { name: '확인하고 정정' }))

    await waitFor(() => expect(amended).toHaveLength(2))
    const first = amended[0]?.body as Body
    const second = amended[1]?.body as Body
    expect(second.clientOrderId).toBe(first.clientOrderId)
    expect(second.confirmedRules).toEqual(['AutoExposureOverCap'])
  })
})
