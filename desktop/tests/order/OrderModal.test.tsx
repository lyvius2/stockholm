import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ApiRequest } from '../../src/preload/bridge'
import { useStreamStore } from '@renderer/data/stream/store'
import { OrderModal } from '@renderer/features/order/OrderModal'
import { installBridge, ok, status } from '../support/bridge'

const samsung = { market: 'KR', code: '005930' } as const

function feedQuote(amount: string) {
  useStreamStore.getState().applyMessages([
    {
      type: 'quote',
      symbol: samsung,
      quote: { last: { amount, currency: 'KRW' }, asOf: '2026-10-06T01:00:00Z' },
    },
  ])
}

type Body = {
  clientOrderId: string
  confirmedRules: string[]
  limitPrice: { amount: string }
  quantity: string
}

describe('OrderModal', () => {
  const placed: ApiRequest[] = []
  let reply: (body: Body) => ReturnType<typeof ok>

  beforeEach(() => {
    placed.length = 0
    useStreamStore.getState().reset()
    useStreamStore.getState().setWatched([samsung])
    reply = () => ok({ clientOrderId: 'x', state: 'ACCEPTED', brokerOrderId: 'B-1' })
    installBridge((request) => {
      if (request.method === 'POST' && request.path === '/orders') {
        placed.push(request)
        return reply(request.body as Body)
      }
      return status(404)
    })
    act(() => {
      useStreamStore.getState().setConnection('open')
      feedQuote('74300')
    })
  })

  function renderModal(side: 'BUY' | 'SELL' = 'BUY') {
    const onPlaced = vi.fn()
    render(
      <OrderModal
        symbol={samsung}
        side={side}
        ticket={null}
        onClose={() => undefined}
        onPlaced={onPlaced}
      />,
    )
    return onPlaced
  }

  it('지정가 주문은 확인 창을 거치고, 가격을 비우면 현재가로 내며, 멱등 키는 ULID 26자', async () => {
    const onPlaced = renderModal()
    fireEvent.change(screen.getByLabelText('수량 (주)'), { target: { value: '3' } })
    fireEvent.click(screen.getByRole('button', { name: '지정가 매수' }))

    const confirm = screen.getByRole('alertdialog', { name: '주문 확인' })
    expect(confirm.textContent).toContain('매수 3주 · 74,300원')
    expect(placed).toHaveLength(0)
    fireEvent.click(screen.getByRole('button', { name: '매수 주문' }))

    await waitFor(() => expect(placed).toHaveLength(1))
    const body = placed[0]?.body as Body
    expect(body.limitPrice.amount).toBe('74300')
    expect(body.quantity).toBe('3')
    expect(body.clientOrderId).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/)
    expect(body.confirmedRules).toEqual([])
    expect(await screen.findByRole('status')).toHaveProperty('textContent', '접수됨 · 주문번호 B-1')
    expect(onPlaced).toHaveBeenCalledTimes(1)
  })

  it('현재가 즉시 주문은 확인 창 없이 바로 내고, 데몬이 확인을 요구하면(428) 노트를 보인 뒤 같은 키로 다시 냄', async () => {
    let calls = 0
    reply = () => {
      calls += 1
      return calls === 1
        ? status(428, {
            code: 'ConfirmationRequiredException',
            message: '확인',
            notes: ['HighValue: 1억원 이상 주문'],
          })
        : ok({ clientOrderId: 'x', state: 'PENDING', brokerOrderId: null })
    }
    renderModal('SELL')
    fireEvent.change(screen.getByLabelText('수량 (주)'), { target: { value: '2000' } })

    fireEvent.click(screen.getByRole('button', { name: '현재가 즉시 매도' }))

    await screen.findByRole('alertdialog', { name: '주문 확인' })
    expect(screen.getByText('HighValue: 1억원 이상 주문')).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: '확인하고 주문' }))

    await waitFor(() => expect(placed).toHaveLength(2))
    const first = placed[0]?.body as Body
    const second = placed[1]?.body as Body
    expect(second.clientOrderId).toBe(first.clientOrderId)
    expect(second.confirmedRules).toEqual(['HighValue'])
    expect((await screen.findByRole('status')).textContent).toContain('확인 중')
  })

  it('가드레일 위반(422)은 사유를 보이고 버튼을 잠그며, 입력을 바꾸면 풀림', async () => {
    reply = () =>
      status(422, {
        code: 'GuardrailViolationException',
        message: '가드레일 위반',
        violations: ['MarketOrderScope: 국내 시장가 금지'],
      })
    renderModal()
    fireEvent.change(screen.getByLabelText('수량 (주)'), { target: { value: '1' } })
    fireEvent.click(screen.getByRole('button', { name: '현재가 즉시 매수' }))

    expect((await screen.findByRole('alert')).textContent).toContain('MarketOrderScope')
    expect(screen.getByRole('button', { name: '지정가 매수' })).toHaveProperty('disabled', true)

    fireEvent.change(screen.getByLabelText('수량 (주)'), { target: { value: '2' } })
    expect(screen.getByRole('button', { name: '지정가 매수' })).toHaveProperty('disabled', false)
  })

  it('응답을 받지 못하면 입력과 새 제출을 잠그고, 같은 멱등 키로만 결과를 다시 확인함', async () => {
    let calls = 0
    installBridge((request) => {
      if (request.method === 'POST' && request.path === '/orders') {
        placed.push(request)
        calls += 1
        if (calls === 1) return Promise.reject(new Error('IPC 끊김'))
        return ok({ clientOrderId: 'x', state: 'ACCEPTED', brokerOrderId: 'B-9' })
      }
      return status(404)
    })
    renderModal()
    fireEvent.change(screen.getByLabelText('수량 (주)'), { target: { value: '1' } })
    fireEvent.click(screen.getByRole('button', { name: '현재가 즉시 매수' }))

    expect((await screen.findByRole('alert')).textContent).toContain('접수됐을 수 있으니')
    expect(screen.getByLabelText('수량 (주)')).toHaveProperty('disabled', true)
    expect(screen.queryByRole('button', { name: '지정가 매수' })).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: '같은 주문으로 결과 확인' }))

    await waitFor(() => expect(placed).toHaveLength(2))
    expect((placed[1]?.body as Body).clientOrderId).toBe((placed[0]?.body as Body).clientOrderId)
    expect((await screen.findByRole('status')).textContent).toContain('B-9')
  })

  it('가드레일 거부 뒤에도 입력은 열려 있어 고칠 수 있음', async () => {
    reply = () =>
      status(422, {
        code: 'GuardrailViolationException',
        message: '위반',
        violations: ['DailyLimit: 한도'],
      })
    renderModal()
    fireEvent.change(screen.getByLabelText('수량 (주)'), { target: { value: '1' } })
    fireEvent.click(screen.getByRole('button', { name: '현재가 즉시 매수' }))
    await screen.findByRole('alert')

    expect(screen.getByLabelText('수량 (주)')).toHaveProperty('disabled', false)
    expect(screen.getByLabelText('지정가 (KRW)')).toHaveProperty('disabled', false)
    expect(screen.getByRole('button', { name: '현재가 즉시 매수' })).toHaveProperty(
      'disabled',
      true,
    )
  })

  it('확인 창이 떠 있는 동안 입력을 바꾸면 확인 창이 닫혀 옛 요청이 나가지 않음', () => {
    renderModal()
    fireEvent.change(screen.getByLabelText('수량 (주)'), { target: { value: '3' } })
    fireEvent.click(screen.getByRole('button', { name: '지정가 매수' }))
    expect(screen.getByRole('alertdialog', { name: '주문 확인' })).toBeDefined()

    fireEvent.change(screen.getByLabelText('지정가 (KRW)'), { target: { value: '74000' } })

    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(placed).toHaveLength(0)
  })

  it('스트림이 지연이고 폴링 값도 없으면 현재가 즉시 주문을 잠그고 지연 칩을 보임', () => {
    act(() => useStreamStore.getState().setConnection('closed'))
    renderModal()
    fireEvent.change(screen.getByLabelText('수량 (주)'), { target: { value: '1' } })

    expect(screen.getByRole('dialog', { name: '매수 주문' }).textContent).toContain('지연')
    expect(screen.getByRole('button', { name: '현재가 즉시 매수' })).toHaveProperty(
      'disabled',
      true,
    )
    expect(screen.getByRole('button', { name: '지정가 매수' })).toHaveProperty('disabled', false)
  })

  it('증권사가 호가 단위로 거부하면 제안 가격 칩을 보이고 누르면 가격에 들어감', async () => {
    reply = () =>
      status(422, {
        code: 'OrderRejectedException',
        message: '호가 단위 불일치',
        tickSize: '100',
        nearestPrices: ['74300', '74400'],
      })
    renderModal()
    fireEvent.change(screen.getByLabelText('지정가 (KRW)'), { target: { value: '74350' } })
    fireEvent.change(screen.getByLabelText('수량 (주)'), { target: { value: '1' } })
    fireEvent.click(screen.getByRole('button', { name: '지정가 매수' }))
    fireEvent.click(screen.getByRole('button', { name: '매수 주문' }))

    await screen.findByRole('alert')
    fireEvent.click(screen.getByRole('button', { name: '74,400' }))

    expect(screen.getByLabelText('지정가 (KRW)')).toHaveProperty('value', '74400')
  })
})
