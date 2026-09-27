import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import type { ReactElement } from 'react'
import { App } from '@renderer/app/App'
import { localClient } from '@renderer/data/client/LocalClient'
import { useSessionStore } from '@renderer/data/store/session'
import { vi } from 'vitest'
import { installBridge, ok, status } from './support/bridge'

function renderApp(ui: ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>)
}

const notStarted = {
  state: 'NOT_STARTED',
  adminDisplayName: null,
  tossDecision: 'NONE',
  llmPreset: null,
  canFinishSharedKeys: false,
  credentials: [],
}

describe('App 진입 분기', () => {
  beforeEach(() => useSessionStore.getState().signOut())

  it('데몬에 닿지 못하면 기동 중 안내를 보임', async () => {
    installBridge(() => status(500), false)
    renderApp(<App />)
    expect(await screen.findByText('데몬 기동 중…')).toBeDefined()
  })

  it('마법사가 끝나지 않았으면 마법사 ① 을 보임', async () => {
    installBridge(({ path }) =>
      path === '/setup/state' ? ok(notStarted) : path === '/setup/catalog' ? ok([]) : status(404),
    )
    renderApp(<App />)
    expect(await screen.findByRole('heading', { name: 'admin 계정' })).toBeDefined()
    expect(screen.getByRole('button', { name: '다음' })).toHaveProperty('disabled', true)
  })

  it('①이 끝난 뒤 앱을 다시 켜 세션이 없으면 비밀번호·TOTP 확인 카드를 먼저 보임', async () => {
    installBridge(({ path }) =>
      path === '/setup/state'
        ? ok({ ...notStarted, state: 'ADMIN_CREATED', adminDisplayName: '월터' })
        : path === '/setup/catalog'
          ? ok([])
          : status(404),
    )
    renderApp(<App />)
    expect(await screen.findByRole('heading', { name: '마법사 계속' })).toBeDefined()
    expect(screen.queryByRole('heading', { name: '공유 API 키' })).toBeNull()
  })

  it('마법사 세션이 만료돼 401 을 받으면 잠금 카드로 돌아감', async () => {
    const bridge = installBridge(({ path }) =>
      path === '/setup/state'
        ? ok({ ...notStarted, state: 'ADMIN_CREATED', adminDisplayName: '월터' })
        : path === '/setup/catalog'
          ? ok([])
          : status(401, { code: 'HTTP_401', message: '마법사 세션이 필요함' }),
    )
    let hasSession = true
    bridge.session.hasSession = vi.fn(async () => hasSession)
    renderApp(<App />)
    expect(await screen.findByRole('heading', { name: '공유 API 키' })).toBeDefined()

    hasSession = false
    await localClient.request('POST', '/setup/keys/DART', {}).catch(() => undefined)

    expect(await screen.findByRole('heading', { name: '마법사 계속' })).toBeDefined()
  })

  it('마법사가 끝났고 세션이 없으면 흐린 배경 위에 로그인 모달을 보임', async () => {
    installBridge(({ path }) =>
      path === '/setup/state'
        ? status(404, { code: 'HTTP_404', message: 'not found' })
        : path === '/session/users'
          ? ok([
              {
                userId: 'u_1',
                displayName: '월터',
                role: 'ADMIN',
                status: 'ACTIVE',
                isTotpEnrolled: true,
                tossKeyDecision: 'LATER',
                lockedUntil: null,
              },
            ])
          : status(404),
    )
    renderApp(<App />)
    const dialog = await screen.findByRole('dialog', { name: '로그인' })
    expect(dialog).toBeDefined()
    expect(await screen.findByRole('radio', { name: '월터' })).toBeDefined()
    expect(document.querySelector('.login-backdrop')).not.toBeNull()
    expect(screen.queryByText('차트')).toBeNull()
  })

  it('로그인한 사용자가 있으면 네 영역과 상단 바를 보이고 모달은 없음', async () => {
    installBridge(({ path }) => (path === '/setup/state' ? status(404) : status(404)))
    useSessionStore.getState().signIn({
      userId: 'u_1',
      displayName: '월터',
      role: 'ADMIN',
      status: 'ACTIVE',
      isTotpEnrolled: true,
      tossKeyDecision: 'LATER',
      lockedUntil: null,
    })
    renderApp(<App />)
    expect(await screen.findByText('차트')).toBeDefined()
    expect(screen.getByText('토론')).toBeDefined()
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.getByRole('button', { name: '👤 월터' })).toBeDefined()
  })
})
