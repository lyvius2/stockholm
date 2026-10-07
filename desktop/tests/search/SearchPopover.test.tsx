import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useStockStore } from '@renderer/data/store/stock'
import { SearchPopover } from '@renderer/features/search/SearchPopover'
import { installBridge, ok, status } from '../support/bridge'

const samsung = {
  symbol: { market: 'KR', code: '005930' },
  name: '삼성전자',
  englishName: 'Samsung Electronics',
  board: 'KOSPI',
  securityType: 'STOCK',
  isPreferred: false,
}
const samsungPreferred = {
  ...samsung,
  symbol: { market: 'KR', code: '005935' },
  name: '삼성전자우',
}

describe('SearchPopover', () => {
  beforeEach(() => useStockStore.getState().reset())

  it('검색어로 데몬에 묻고, 화살표·Enter 로 고르면 현재 종목이 바뀌고 닫힘', async () => {
    const queries: string[] = []
    installBridge(({ path }) => {
      if (path.startsWith('/stocks?')) {
        queries.push(decodeURIComponent(path))
        return ok([samsung, samsungPreferred])
      }
      return status(404)
    })
    const onClose = vi.fn()
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <SearchPopover onClose={onClose} />
      </QueryClientProvider>,
    )
    const input = screen.getByLabelText('종목 검색어')
    expect(document.activeElement).toBe(input)

    fireEvent.change(input, { target: { value: 'ㅅㅅㅈㅈ' } })
    await screen.findByText('삼성전자우')
    expect(queries).toEqual(['/stocks?query=ㅅㅅㅈㅈ&limit=12'])

    fireEvent.keyDown(screen.getByRole('dialog', { name: '종목 검색' }), { key: 'ArrowDown' })
    fireEvent.keyDown(screen.getByRole('dialog', { name: '종목 검색' }), { key: 'Enter' })

    await waitFor(() =>
      expect(useStockStore.getState().current).toEqual({ market: 'KR', code: '005935' }),
    )
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('Esc 와 바깥 클릭으로 닫히고, 결과가 없으면 안내를 보임', async () => {
    installBridge(({ path }) => (path.startsWith('/stocks?') ? ok([]) : status(404)))
    const onClose = vi.fn()
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <SearchPopover onClose={onClose} />
      </QueryClientProvider>,
    )
    fireEvent.change(screen.getByLabelText('종목 검색어'), { target: { value: '없는종목' } })
    expect(await screen.findByText('찾는 종목이 없습니다')).toBeDefined()

    fireEvent.keyDown(screen.getByRole('dialog', { name: '종목 검색' }), { key: 'Escape' })
    fireEvent.mouseDown(screen.getByRole('presentation'))

    expect(onClose).toHaveBeenCalledTimes(2)
  })
})
