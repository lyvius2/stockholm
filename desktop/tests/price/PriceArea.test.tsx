import { act, render, screen } from '@testing-library/react'
import { useStockStore } from '@renderer/data/store/stock'
import { useStreamStore } from '@renderer/data/stream/store'
import { PriceArea } from '@renderer/features/price/PriceArea'

const samsung = { market: 'KR', code: '005930' } as const

describe('PriceArea', () => {
  beforeEach(() => {
    useStockStore.getState().reset()
    useStreamStore.getState().reset()
  })

  it('종목이 없으면 안내만, 종목이 있으면 스트림의 현재가와 호가 3단을 보이고 끊기면 지연 칩', () => {
    render(<PriceArea />)
    expect(screen.getByText('종목을 고르면 현재가와 호가가 보입니다')).toBeDefined()

    act(() => {
      useStockStore.getState().select(samsung)
      useStreamStore.getState().setConnection('open')
      useStreamStore.getState().applyMessages([
        { type: 'feedState', feedState: { isLive: true, unavailableSymbols: [] } },
        {
          type: 'quote',
          symbol: samsung,
          quote: { last: { amount: '74300', currency: 'KRW' }, asOf: '2026-10-06T01:00:00Z' },
        },
        {
          type: 'orderBook',
          symbol: samsung,
          orderBook: {
            asOf: '2026-10-06T01:00:00Z',
            asks: [1, 2, 3, 4].map((i) => ({
              price: { amount: String(74300 + i * 100), currency: 'KRW' },
              quantity: String(i * 10),
            })),
            bids: [1, 2, 3, 4].map((i) => ({
              price: { amount: String(74300 - i * 100), currency: 'KRW' },
              quantity: String(i * 5),
            })),
          },
        },
      ])
    })

    expect(screen.getByLabelText('현재가').textContent).toBe('74,300원')
    const rows = screen.getAllByRole('row')
    expect(rows).toHaveLength(6)
    expect(rows[0]?.textContent).toBe('74,60030')
    expect(rows[2]?.textContent).toBe('74,40010')
    expect(rows[3]?.textContent).toBe('74,2005')
    expect(screen.queryByText('지연')).toBeNull()

    act(() =>
      useStreamStore
        .getState()
        .applyMessages([
          { type: 'feedState', feedState: { isLive: false, unavailableSymbols: [] } },
        ]),
    )
    expect(screen.getByText('지연')).toBeDefined()
  })
})
