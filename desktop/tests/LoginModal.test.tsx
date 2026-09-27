import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { LoginModal } from '@renderer/features/login/LoginModal'
import { useSessionStore } from '@renderer/data/store/session'
import { installBridge, ok, status } from './support/bridge'

const walter = {
  userId: 'u_1',
  displayName: '월터',
  role: 'ADMIN',
  status: 'ACTIVE',
  isTotpEnrolled: true,
  tossKeyDecision: 'LATER',
  lockedUntil: null,
}

describe('LoginModal', () => {
  beforeEach(() => useSessionStore.getState().signOut())

  it('비밀번호와 6자리 코드가 있어야 로그인 버튼이 켜지고, 성공하면 사용자가 세션에 들어감', async () => {
    const bridge = installBridge(({ path, body }) => {
      if (path === '/session/users') return ok([walter])
      if (path === '/session/login') return ok({ expiresAt: '2026-09-27T21:00:00Z', user: walter })
      return status(404, { body })
    })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <LoginModal />
      </QueryClientProvider>,
    )
    await screen.findByRole('radio', { name: '월터' })
    const submit = screen.getByRole('button', { name: '로그인' })
    expect(submit).toHaveProperty('disabled', true)
    fireEvent.change(screen.getByLabelText('비밀번호'), {
      target: { value: 'correct-horse-battery' },
    })
    fireEvent.change(screen.getByLabelText('TOTP 6자리'), { target: { value: '123456' } })
    expect(submit).toHaveProperty('disabled', false)
    fireEvent.click(submit)
    await waitFor(() => expect(useSessionStore.getState().user?.displayName).toBe('월터'))
    const loginCall = (bridge.api.request as ReturnType<typeof vi.fn>).mock.calls.find(
      ([r]) => (r as { path: string }).path === '/session/login',
    )?.[0] as { body: Record<string, string> }
    expect(loginCall.body).toEqual({
      userId: 'u_1',
      password: 'correct-horse-battery',
      totpCode: '123456',
    })
  })

  it('401 이면 안내 문구를 보이고 세션은 비어 있음', async () => {
    installBridge(({ path }) =>
      path === '/session/users'
        ? ok([walter])
        : status(401, { code: 'AuthenticationFailedException', message: '로그인할 수 없음' }),
    )
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <LoginModal />
      </QueryClientProvider>,
    )
    await screen.findByRole('radio', { name: '월터' })
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'x' } })
    fireEvent.change(screen.getByLabelText('TOTP 6자리'), { target: { value: '000000' } })
    fireEvent.click(screen.getByRole('button', { name: '로그인' }))
    expect(await screen.findByText(/로그인할 수 없습니다/)).toBeDefined()
    expect(useSessionStore.getState().user).toBeNull()
  })
})
