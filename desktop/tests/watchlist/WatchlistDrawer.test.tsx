import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ApiRequest } from '../../src/preload/bridge'
import { useDrawersStore } from '@renderer/data/store/drawers'
import { useStockStore } from '@renderer/data/store/stock'
import { useStreamStore } from '@renderer/data/stream/store'
import { WatchlistDrawer } from '@renderer/features/watchlist/WatchlistDrawer'
import { installBridge, ok, status } from '../support/bridge'

const samsung = { market: 'KR', code: '005930' } as const
const nvidia = { market: 'US', code: 'NVDA' } as const
const hynix = { market: 'KR', code: '000660' } as const

describe('WatchlistDrawer', () => {
  const requests: ApiRequest[] = []
  let items: Array<{
    groupId: string
    symbol: typeof samsung | typeof nvidia | typeof hynix
    name: string | null
  }>

  beforeEach(() => {
    requests.length = 0
    useDrawersStore.getState().reset()
    useDrawersStore.getState().openDrawer('watchlist')
    useStockStore.getState().reset()
    useStreamStore.getState().reset()
    items = [
      { groupId: 'g1', symbol: samsung, name: '삼성전자' },
      { groupId: 'g1', symbol: nvidia, name: 'NVIDIA' },
      { groupId: 'g2', symbol: hynix, name: null },
    ]
    installBridge((request) => {
      requests.push(request)
      if (request.method === 'GET' && request.path === '/watchlist')
        return ok({
          groups: [
            { groupId: 'g1', name: '반도체', sortOrder: 0, updatedAt: '2026-10-13T00:00:00Z' },
            { groupId: 'g2', name: '배당', sortOrder: 1, updatedAt: '2026-10-13T00:00:00Z' },
          ],
          items: items.map((item, index) => ({
            ...item,
            sortOrder: index,
            addedAt: '2026-10-13T00:00:00Z',
          })),
        })
      if (request.method === 'POST' && request.path === '/watchlist/items') {
        const body = request.body as { groupId: string | null; symbol: typeof hynix }
        items.push({ groupId: body.groupId ?? 'g1', symbol: body.symbol, name: null })
        return ok({
          ...body,
          groupId: body.groupId ?? 'g1',
          name: null,
          sortOrder: 9,
          addedAt: '2026-10-13T00:00:00Z',
        })
      }
      if (request.method === 'POST' && request.path === '/watchlist/groups')
        return ok({
          groupId: 'g3',
          name: (request.body as { name: string }).name,
          sortOrder: 2,
          updatedAt: '2026-10-13T00:00:00Z',
        })
      if (request.method === 'DELETE' && request.path.startsWith('/watchlist/groups/g1/items/')) {
        items = items.filter((i) => !request.path.endsWith(`${i.symbol.market}/${i.symbol.code}`))
        return ok(null)
      }
      if (request.method === 'PUT' && request.path.endsWith('/order')) return ok(null)
      if (request.method === 'PUT' && request.path.startsWith('/watchlist/groups/')) return ok(null)
      if (request.path === '/portfolio/valuation')
        return ok({
          byMarket: [
            {
              market: 'KR',
              marketValue: { amount: '1', currency: 'KRW' },
              purchaseAmount: { amount: '1', currency: 'KRW' },
              profitLoss: { amount: '0', currency: 'KRW' },
              returnRate: '0',
              todayChange: null,
              risers: 0,
              fallers: 0,
              holdings: [
                {
                  symbol: samsung,
                  quantity: '1',
                  lastPrice: { amount: '1', currency: 'KRW' },
                  purchaseAmount: { amount: '1', currency: 'KRW' },
                  marketValue: { amount: '1', currency: 'KRW' },
                  profitLoss: { amount: '0', currency: 'KRW' },
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
          asOf: '2026-10-13T00:00:00Z',
        })
      return status(404)
    })
  })

  function renderDrawer() {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    return render(
      <QueryClientProvider client={client}>
        <WatchlistDrawer />
      </QueryClientProvider>,
    )
  }

  it('그룹 탭과 종목 행(이름·보유·US 칩·현재가)을 보이고 보이는 종목을 스트림에 더 선언함', async () => {
    act(() => {
      useStreamStore.getState().setWatched([hynix])
      useStreamStore.getState().setConnection('open')
    })
    renderDrawer()
    expect(await screen.findByRole('tab', { name: '반도체' })).toBeDefined()
    const rows = screen.getAllByRole('listitem')
    expect(rows).toHaveLength(3)
    expect(rows[0]?.textContent).toContain('삼성전자')
    expect(rows[0]?.textContent).toContain('보유')
    expect(rows[1]?.textContent).toContain('US')
    expect(rows[2]?.textContent).toContain('000660')
    expect(useStreamStore.getState().watched).toEqual([hynix, samsung, nvidia])

    act(() =>
      useStreamStore.getState().applyMessages([
        { type: 'feedState', feedState: { isLive: true, unavailableSymbols: [] } },
        {
          type: 'quote',
          symbol: samsung,
          quote: { last: { amount: '74300', currency: 'KRW' }, asOf: '2026-10-13T00:00:00Z' },
        },
      ]),
    )
    expect(screen.getAllByRole('listitem')[0]?.textContent).toContain('74,300원')

    fireEvent.click(screen.getByRole('tab', { name: '배당' }))
    expect(screen.getAllByRole('listitem')).toHaveLength(1)
  })

  it('목록과 무관한 다시 그리기(이름 변경 실패)에도 구독을 거두지 않아 시세가 남고, 스트림이 끊기면 지연 칩', async () => {
    installBridge((request) => {
      requests.push(request)
      if (request.method === 'PUT' && request.path === '/watchlist/groups/g1')
        return status(400, { code: 'InvalidValueException', message: '이름이 김' })
      if (request.method === 'GET' && request.path === '/watchlist')
        return ok({
          groups: [
            { groupId: 'g1', name: '반도체', sortOrder: 0, updatedAt: '2026-10-13T00:00:00Z' },
          ],
          items: [
            {
              groupId: 'g1',
              symbol: samsung,
              name: '삼성전자',
              sortOrder: 0,
              addedAt: '2026-10-13T00:00:00Z',
            },
          ],
        })
      return status(404)
    })
    act(() => useStreamStore.getState().setConnection('open'))
    renderDrawer()
    fireEvent.click(await screen.findByRole('tab', { name: '반도체' }))
    act(() =>
      useStreamStore.getState().applyMessages([
        { type: 'feedState', feedState: { isLive: true, unavailableSymbols: [] } },
        {
          type: 'quote',
          symbol: samsung,
          quote: { last: { amount: '74300', currency: 'KRW' }, asOf: '2026-10-13T00:00:00Z' },
        },
      ]),
    )
    expect(screen.getByRole('listitem').textContent).toContain('74,300원')

    fireEvent.click(screen.getByRole('button', { name: '이름 바꾸기' }))
    fireEvent.change(screen.getByLabelText('그룹 이름'), { target: { value: '너무 긴 이름' } })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    expect((await screen.findByRole('alert')).textContent).toContain('이름이 김')
    expect(useStreamStore.getState().quotes['KR:005930']?.last.amount).toBe('74300')
    expect(screen.getByRole('listitem').textContent).toContain('74,300원')
    expect(screen.queryByText('지연')).toBeNull()

    act(() => useStreamStore.getState().setConnection('closed'))
    expect(screen.getByRole('listitem').textContent).toContain('지연')
  })

  it('이름 편집 중 탭을 바꾸면 편집이 접히고, 저장은 편집을 시작한 그룹으로만 감', async () => {
    renderDrawer()
    fireEvent.click(await screen.findByRole('tab', { name: '반도체' }))
    fireEvent.click(screen.getByRole('button', { name: '이름 바꾸기' }))
    fireEvent.change(screen.getByLabelText('그룹 이름'), { target: { value: '반도체2' } })
    fireEvent.click(screen.getByRole('tab', { name: '배당' }))
    expect(screen.queryByLabelText('그룹 이름')).toBeNull()
    expect(requests.some((r) => r.method === 'PUT')).toBe(false)

    fireEvent.click(screen.getByRole('button', { name: '이름 바꾸기' }))
    fireEvent.change(screen.getByLabelText('그룹 이름'), { target: { value: '배당주' } })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    await waitFor(() => expect(requests.some((r) => r.method === 'PUT')).toBe(true))
    const put = requests.find((r) => r.method === 'PUT')
    expect(put?.path).toBe('/watchlist/groups/g2')
    expect(put?.body).toEqual({ name: '배당주' })
  })

  it('행을 누르면 네 영역이 그 종목으로 바뀌고 서랍이 닫힘', async () => {
    renderDrawer()
    fireEvent.click(await screen.findByRole('button', { name: 'NVIDIA 보기' }))
    expect(useStockStore.getState().current).toEqual(nvidia)
    expect(useDrawersStore.getState().open).toEqual([])
  })

  it('현재 종목 담기는 보고 있는 그룹에 넣고, 이미 담겼으면 잠기며, 빼기는 그룹에서 지움', async () => {
    act(() => useStockStore.getState().select(hynix))
    renderDrawer()
    fireEvent.click(await screen.findByRole('tab', { name: '반도체' }))
    const add = screen.getByRole('button', { name: /현재 종목 담기/ })
    expect(add).toHaveProperty('disabled', false)
    fireEvent.click(add)
    await waitFor(() =>
      expect(
        requests.filter((r) => r.method === 'POST' && r.path === '/watchlist/items'),
      ).toHaveLength(1),
    )
    expect(requests.find((r) => r.path === '/watchlist/items')?.body).toEqual({
      groupId: 'g1',
      symbol: hynix,
    })
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(3))
    expect(screen.getByRole('button', { name: /현재 종목 담기/ })).toHaveProperty('disabled', true)

    fireEvent.click(screen.getAllByRole('button', { name: '빼기' })[0] as HTMLElement)
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(2))
    expect(requests.some((r) => r.path === '/watchlist/groups/g1/items/KR/005930')).toBe(true)
  })

  it('+ 로 그룹을 만들면 그 그룹으로 넘어가고, 드래그로 순서를 바꾸면 새 순서를 보냄', async () => {
    renderDrawer()
    fireEvent.click(await screen.findByRole('button', { name: '그룹 추가' }))
    fireEvent.change(screen.getByLabelText('새 그룹 이름'), { target: { value: '성장주' } })
    fireEvent.click(screen.getByRole('button', { name: '만들기' }))
    await waitFor(() => expect(requests.some((r) => r.path === '/watchlist/groups')).toBe(true))

    fireEvent.click(screen.getByRole('tab', { name: '반도체' }))
    const rows = screen.getAllByRole('listitem')
    fireEvent.dragStart(rows[1] as HTMLElement)
    fireEvent.drop(rows[0] as HTMLElement)
    await waitFor(() =>
      expect(requests.some((r) => r.path === '/watchlist/groups/g1/order')).toBe(true),
    )
    expect(requests.find((r) => r.path.endsWith('/order'))?.body).toEqual({
      symbols: [nvidia, samsung],
    })
  })
})
