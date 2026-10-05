import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen } from '@testing-library/react'
import type { StreamIndexEntry, StreamIndexTicker } from '@renderer/generated/stream-server-message'
import { useStreamStore } from '@renderer/data/stream/store'
import { IndexTicker, entryText } from '@renderer/features/ticker/IndexTicker'
import { installBridge, ok, status } from '../support/bridge'

function entry(
  code: StreamIndexEntry['code'],
  value: string,
  change: string | null,
  ratio: string | null,
  state: StreamIndexEntry['state'] = 'FRESH',
): StreamIndexEntry {
  return {
    code,
    name: code,
    state,
    quote:
      value === ''
        ? null
        : {
            code,
            name: code,
            value,
            change,
            changeRatio: ratio,
            asOf: '2026-10-06T00:00:00Z',
            isClosed: false,
            proxy: null,
            source: 'TOSS',
          },
  }
}

const krSet: StreamIndexTicker = {
  market: 'KR',
  state: 'OPEN',
  asOf: '2026-10-06T00:00:00Z',
  isDelayed: false,
  entries: [
    entry('KOSPI', '3412.85', '12.30', '0.0036'),
    entry('KOSDAQ', '850.10', '-4.25', '-0.0050'),
    entry('NIKKEI225', '', null, null, 'UNCONFIGURED'),
  ],
}

describe('IndexTicker', () => {
  beforeEach(() => useStreamStore.getState().reset())

  it('항목 형식은 `값 | ▲ 등락 (+%)` 이고 하락은 ▼ 와 −, 값이 없으면 —', () => {
    expect(entryText(krSet.entries[0] as StreamIndexEntry)).toBe('3,412.85 | ▲ 12.30 (+0.36%)')
    expect(entryText(krSet.entries[1] as StreamIndexEntry)).toBe('850.10 | ▼ 4.25 (−0.50%)')
    expect(entryText(krSet.entries[2] as StreamIndexEntry)).toBe('—')
  })

  it('첫 값은 REST 로 받고, 스트림이 오면 그 값으로 바뀌며 출처 미설정은 칩으로 보임', async () => {
    installBridge(({ path }) =>
      path === '/market/index-ticker'
        ? ok({ ...krSet, entries: [entry('KOSPI', '3400.00', '0.00', '0')] })
        : status(404),
    )
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <IndexTicker />
      </QueryClientProvider>,
    )
    expect(await screen.findByText('3,400.00 | — 0.00 (0.00%)')).toBeDefined()

    act(() =>
      useStreamStore.getState().applyMessages([{ type: 'indexTicker', indexTicker: krSet }]),
    )

    expect(screen.getByText('3,412.85 | ▲ 12.30 (+0.36%)')).toBeDefined()
    expect(screen.getByText('출처 미설정')).toBeDefined()
    expect(screen.getByText('NIKKEI')).toBeDefined()
  })
})
