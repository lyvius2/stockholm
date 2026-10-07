import { act, render, screen, waitFor } from '@testing-library/react'
import { localClient } from '@renderer/data/client/LocalClient'
import { usePollingFallback } from '@renderer/features/price/usePollingFallback'
import { installBridge, ok, status } from '../support/bridge'

const samsung = { market: 'KR', code: '005930' } as const
const INTERVALS = { quoteMs: 30, orderBookMs: 45 }

function Host({ isActive }: { readonly isActive: boolean }) {
  const polled = usePollingFallback(samsung, isActive, localClient, INTERVALS)
  return (
    <div>
      <span data-testid="quote">{polled.quote?.last.amount ?? '-'}</span>
      <span data-testid="book">{polled.orderBook?.asks.length ?? '-'}</span>
    </div>
  )
}

describe('usePollingFallback', () => {
  it('지연 동안 현재가·호가를 간격대로 폴링하고, 지연이 풀리면 멈추고 값을 비움', async () => {
    let quoteCalls = 0
    let bookCalls = 0
    installBridge(({ path }) => {
      if (path.startsWith('/market/quote')) {
        quoteCalls += 1
        return ok({
          symbol: samsung,
          last: { amount: String(74000 + quoteCalls), currency: 'KRW' },
          asOf: '2026-10-06T01:00:00Z',
        })
      }
      if (path.startsWith('/market/order-book')) {
        bookCalls += 1
        return ok({
          symbol: samsung,
          asks: [{ price: { amount: '74100', currency: 'KRW' }, quantity: '1' }],
          bids: [],
          asOf: '2026-10-06T01:00:00Z',
        })
      }
      return status(404)
    })
    const view = render(<Host isActive />)

    await waitFor(() => expect(screen.getByTestId('quote').textContent).toBe('74001'))
    await waitFor(() => expect(screen.getByTestId('book').textContent).toBe('1'))
    await waitFor(() => expect(quoteCalls).toBeGreaterThanOrEqual(3))
    expect(bookCalls).toBeLessThan(quoteCalls)

    view.rerender(<Host isActive={false} />)
    const callsAtStop = quoteCalls
    expect(screen.getByTestId('quote').textContent).toBe('-')
    await new Promise((resolve) => setTimeout(resolve, INTERVALS.orderBookMs * 2))
    expect(quoteCalls).toBe(callsAtStop)
  })
  it('느린 요청은 중복하지 않고, 중지 뒤 늦은 응답도 버림', async () => {
    const replies: Array<(reply: ReturnType<typeof ok>) => void> = []
    installBridge(() => new Promise((resolve) => replies.push(resolve)))
    const view = render(<Host isActive />)
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 100))
    })
    expect(replies).toHaveLength(2)

    view.rerender(<Host isActive={false} />)
    await act(async () => {
      replies.forEach((resolve) =>
        resolve(
          ok({
            symbol: samsung,
            last: { amount: '70000', currency: 'KRW' },
            asks: [],
            bids: [],
            asOf: '2026-10-06T01:00:00Z',
          }),
        ),
      )
    })
    expect(screen.getByTestId('quote').textContent).toBe('-')
    expect(screen.getByTestId('book').textContent).toBe('-')
  })

  it('같은 종목의 폴링을 재개하면 이전 폴링 값을 새 응답 전까지 숨김', async () => {
    let isWaiting = false
    installBridge(({ path }) => {
      if (isWaiting) return new Promise(() => undefined)
      if (path.startsWith('/market/quote'))
        return ok({
          symbol: samsung,
          last: { amount: '70000', currency: 'KRW' },
          asOf: '2026-10-06T01:00:00Z',
        })
      return ok({ symbol: samsung, asks: [], bids: [], asOf: '2026-10-06T01:00:00Z' })
    })
    const view = render(<Host isActive />)
    await waitFor(() => expect(screen.getByTestId('quote').textContent).toBe('70000'))
    view.rerender(<Host isActive={false} />)
    isWaiting = true
    view.rerender(<Host isActive />)
    expect(screen.getByTestId('quote').textContent).toBe('-')
    expect(screen.getByTestId('book').textContent).toBe('-')
  })
})
