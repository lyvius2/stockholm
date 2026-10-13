import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { useDrawersStore } from '@renderer/data/store/drawers'
import { useStockStore } from '@renderer/data/store/stock'
import { MoversDrawer } from '@renderer/features/ranking/MoversDrawer'
import { installBridge, ok, status } from '../support/bridge'

const flags = {
  isInvestmentWarning: true,
  isInvestmentRisk: false,
  isOverheated: false,
  isLiquidationTrading: false,
  isViStatic: false,
  isViDynamic: false,
  hasUnknownWarning: false,
  isTradingHalted: null,
  isAdministrative: false,
  asOf: '2026-10-13T01:00:00Z',
}

function mover(
  rank: number,
  code: string,
  changeRate: string,
  rankChange: { kind: string; places: number | null },
) {
  const market = /^\d+$/.test(code) ? 'KR' : 'US'
  return {
    rank,
    symbol: { market, code },
    last: {
      amount: market === 'KR' ? '118400' : '16.22',
      currency: market === 'KR' ? 'KRW' : 'USD',
    },
    changeRate,
    tradingVolume: '1000',
    rankChange,
    flags: rank === 1 ? flags : null,
  }
}

describe('MoversDrawer', () => {
  beforeEach(() => {
    useDrawersStore.getState().reset()
    useDrawersStore.getState().openDrawer('movers')
    useStockStore.getState().reset()
    installBridge(({ path }) => {
      if (path.startsWith('/market/movers')) {
        const market = new URL(`http://x${path}`).searchParams.get('market')
        return ok(
          market === 'KR'
            ? {
                market: 'KR',
                session: 'REGULAR',
                asOf: '2026-10-13T01:02:41Z',
                isDelayed: false,
                gainers: [
                  mover(1, '042700', '0.0421', { kind: 'NEW', places: null }),
                  mover(2, '007660', '0.0397', { kind: 'UP', places: 3 }),
                ],
                losers: [mover(1, '036570', '-0.0583', { kind: 'DOWN', places: 1 })],
              }
            : {
                market: 'US',
                session: 'CLOSED',
                asOf: '2026-10-13T01:02:41Z',
                isDelayed: true,
                gainers: [mover(1, 'GRML', '0.146', { kind: 'SAME', places: null })],
                losers: [],
              },
        )
      }
      if (path === '/stocks/KR/042700')
        return ok({
          symbol: { market: 'KR', code: '042700' },
          name: '한미반도체',
          englishName: 'Hanmi',
          board: 'KOSPI',
          securityType: 'STOCK',
          isPreferred: false,
        })
      return status(404)
    })
  })

  function renderDrawer() {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    return render(
      <QueryClientProvider client={client}>
        <MoversDrawer />
      </QueryClientProvider>,
    )
  }

  it('국내·미국 급등·급락을 순위·변동·경고 칩·등락률과 함께 보이고, 미국 장 밖은 전일 기준 칩', async () => {
    renderDrawer()
    const gainers = await screen.findByRole('list', { name: '🇰🇷 국내 급등' })
    const rows = Array.from(gainers.querySelectorAll('li'))
    expect(rows).toHaveLength(2)
    expect(await screen.findByText('한미반도체')).toBeDefined()
    expect(rows[0]?.textContent).toContain('NEW')
    expect(rows[0]?.textContent).toContain('투자경고')
    expect(rows[0]?.textContent).toContain('확인 필요')
    expect(rows[0]?.textContent).toContain('+4.21%')
    expect(rows[1]?.textContent).toContain('↑3')
    // 경고 조회 자체가 실패한 행(flags null)도 모름을 드러냄
    expect(rows[1]?.textContent).toContain('확인 필요')

    const losers = screen.getByRole('list', { name: '🇰🇷 국내 급락' })
    expect(losers.textContent).toContain('↓1')
    expect(losers.textContent).toContain('-5.83%')
    expect(screen.getByText('전일 기준')).toBeDefined()
    expect(screen.getByText('지연')).toBeDefined()
    expect(screen.getByRole('tab', { name: '거래량 급증' })).toHaveProperty('disabled', true)
  })

  it('행을 누르면 네 영역이 바뀌고 서랍은 열린 채 남음', async () => {
    renderDrawer()
    fireEvent.click(await screen.findByRole('button', { name: '한미반도체 보기' }))
    expect(useStockStore.getState().current).toEqual({ market: 'KR', code: '042700' })
    expect(useDrawersStore.getState().open).toEqual(['movers'])
  })
})
