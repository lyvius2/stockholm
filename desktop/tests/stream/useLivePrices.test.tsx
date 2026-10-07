import { act, render, screen } from '@testing-library/react'
import { usePollStore } from '@renderer/data/stream/pollStore'
import { useStreamStore } from '@renderer/data/stream/store'
import { useIsFeedDelayed } from '@renderer/data/stream/useMarketStream'
import { useLiveQuote } from '@renderer/data/stream/useLivePrices'

const samsung = { market: 'KR', code: '005930' } as const

function Host() {
  const live = useLiveQuote(samsung)
  const isDelayed = useIsFeedDelayed(samsung)
  return (
    <div>
      <span data-testid="price">{live.quote?.last.amount ?? '-'}</span>
      <span data-testid="flags">{`${isDelayed}/${live.isFresh}`}</span>
    </div>
  )
}

describe('useLiveQuote · useIsFeedDelayed', () => {
  beforeEach(() => {
    useStreamStore.getState().reset()
    usePollStore.getState().clear()
  })

  it('구독이 거절된 종목은 연결이 살아 있어도 지연이고, 폴링 값이 있으면 그 값이 신선한 값으로 보임', () => {
    render(<Host />)
    act(() => {
      useStreamStore.getState().setConnection('open')
      useStreamStore.getState().setWatched([samsung])
      useStreamStore.getState().applyMessages([
        { type: 'feedState', feedState: { isLive: true, unavailableSymbols: [] } },
        {
          type: 'quote',
          symbol: samsung,
          quote: { last: { amount: '74300', currency: 'KRW' }, asOf: '2026-10-07T00:00:00Z' },
        },
      ])
    })
    expect(screen.getByTestId('price').textContent).toBe('74300')
    expect(screen.getByTestId('flags').textContent).toBe('false/true')

    act(() =>
      useStreamStore
        .getState()
        .applyMessages([
          { type: 'feedState', feedState: { isLive: true, unavailableSymbols: [samsung] } },
        ]),
    )
    expect(screen.getByTestId('flags').textContent).toBe('true/false')

    act(() =>
      usePollStore.getState().setPolled('KR:005930', {
        quote: {
          symbol: samsung,
          last: { amount: '74500', currency: 'KRW' },
          asOf: '2026-10-07T00:00:05Z',
        },
        orderBook: null,
      }),
    )
    expect(screen.getByTestId('price').textContent).toBe('74500')
    expect(screen.getByTestId('flags').textContent).toBe('true/true')
  })
})
