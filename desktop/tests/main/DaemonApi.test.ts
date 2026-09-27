// @vitest-environment node
import { mkdtemp, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { DaemonApi } from '../../src/main/api'

/** main 의 대리인이 토큰을 붙이고, 응답의 토큰은 걷어 낸 채 렌더러에 넘기는지. */
describe('DaemonApi', () => {
  it('로컬 토큰 헤더를 붙이고 로그인 응답의 token 은 보관한 뒤 빼서 돌려줌', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'stockholm-'))
    await writeFile(join(dir, 'local-token'), 'local-secret\n')
    const seen: Array<{ url: string; headers: Record<string, string> }> = []
    const fetchMock = vi.fn(async (url: string, init: RequestInit) => {
      seen.push({ url, headers: init.headers as Record<string, string> })
      const body = url.endsWith('/session/login')
        ? { token: 'session-secret', expiresAt: 'x', user: { displayName: '월터' } }
        : { ok: true }
      return new Response(JSON.stringify(body), { status: 200 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const api = new DaemonApi(dir, 'http://daemon')

    const login = await api.request({
      method: 'POST',
      path: '/session/login',
      body: { userId: 'u' },
    })
    expect(login.body).toEqual({ expiresAt: 'x', user: { displayName: '월터' } })
    expect(api.hasSession()).toBe(true)

    await api.request({ method: 'GET', path: '/me' })
    expect(seen[0]?.headers['X-Stockholm-Local-Token']).toBe('local-secret')
    expect(seen[0]?.headers['Authorization']).toBeUndefined()
    expect(seen[1]?.headers['Authorization']).toBe('Bearer session-secret')

    await api.request({ method: 'POST', path: '/session/logout', body: {} })
    expect(api.hasSession()).toBe(false)
    vi.unstubAllGlobals()
  })

  it('마법사 토큰으로 보낸 /setup 요청이 401 이면 만료된 토큰을 잊음', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'stockholm-'))
    await writeFile(join(dir, 'local-token'), 'local-secret\n')
    const fetchMock = vi.fn(async (url: string) => {
      if (url.endsWith('/setup/admin/totp'))
        return new Response(
          JSON.stringify({ state: { state: 'ADMIN_CREATED' }, wizardToken: 'w' }),
          {
            status: 200,
          },
        )
      return new Response(JSON.stringify({ code: 'x', message: 'expired' }), { status: 401 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const api = new DaemonApi(dir, 'http://daemon')

    await api.request({ method: 'POST', path: '/setup/admin/totp', body: { code: '1' } })
    expect(api.hasSession()).toBe(true)
    await api.request({ method: 'POST', path: '/setup/keys/DART', body: {} })
    expect(api.hasSession()).toBe(false)
    vi.unstubAllGlobals()
  })
})
