import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen } from '@testing-library/react'
import type { ApiUser } from '@renderer/generated/api-user'
import { useSessionStore } from '@renderer/data/store/session'
import { useStreamStore } from '@renderer/data/stream/store'
import { MainShell } from '@renderer/layout/MainShell'
import { installBridge, ok, status } from './support/bridge'

const walter: ApiUser = {
  userId: 'u_1',
  displayName: '월터',
  role: 'ADMIN',
  status: 'ACTIVE',
  isTotpEnrolled: true,
  tossKeyDecision: 'LATER',
}

/** App 처럼 세션 store 의 사용자를 MainShell 에 넘김. */
function Host() {
  const user = useSessionStore((s) => s.user)
  return <MainShell user={user} />
}

describe('MainShell 세션 수명주기', () => {
  beforeEach(() => {
    useSessionStore.getState().signOut()
    useStreamStore.getState().reset()
  })

  it('로그인 동안 스트림을 열고, main 이 세션 종료를 알리면 비우고 로그인 모달로 감', async () => {
    const bridge = installBridge(({ path }) =>
      path === '/session/users' ? ok([walter]) : status(404),
    )
    let sessionEnded: (() => void) | null = null
    vi.mocked(bridge.session.onEnded).mockImplementation((listener) => {
      sessionEnded = listener
      return () => undefined
    })
    useSessionStore.getState().signIn(walter)
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <Host />
      </QueryClientProvider>,
    )
    expect(screen.queryByRole('dialog', { name: '로그인' })).toBeNull()
    expect(bridge.stream.subscribe).toHaveBeenCalledWith([])

    act(() => useStreamStore.getState().setConnection('open'))
    act(() => (sessionEnded as (() => void) | null)?.())

    expect(useSessionStore.getState().user).toBeNull()
    expect(await screen.findByRole('dialog', { name: '로그인' })).toBeDefined()
    expect(bridge.stream.close).toHaveBeenCalledTimes(1)
    expect(useStreamStore.getState().connection).toBe('closed')
  })
})
