// @vitest-environment node
import { mkdtemp, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { DaemonApi } from '../../src/main/api'
import { ManualScheduler } from './support/ManualScheduler'

interface Seen {
  readonly url: string
  readonly headers: Record<string, string>
}

type Reply = (url: string, headers: Record<string, string>) => Response

const json = (status: number, body: unknown): Response =>
  new Response(JSON.stringify(body), { status })

/** 로컬 토큰 파일이 있는 데이터 폴더와 응답을 정해 둔 fetch 위의 대리인. */
async function harness(reply: Reply, now: () => number = Date.now) {
  const dir = await mkdtemp(join(tmpdir(), 'stockholm-'))
  await writeFile(join(dir, 'local-token'), 'local-secret\n')
  const seen: Seen[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit) => {
      const headers = init.headers as Record<string, string>
      seen.push({ url, headers })
      return reply(url, headers)
    }),
  )
  const scheduler = new ManualScheduler()
  const api = new DaemonApi(dir, 'http://daemon', scheduler.schedule, now)
  let endedCount = 0
  api.onSessionEnded(() => {
    endedCount += 1
  })
  return { dir, api, seen, scheduler, ended: () => endedCount }
}

const loginReply =
  (body: unknown = { token: 'session-secret', expiresAt: 'x', user: {} }): Reply =>
  (url) =>
    url.endsWith('/session/login') ? json(200, body) : json(200, { ok: true })

const login = (api: DaemonApi) =>
  api.request({ method: 'POST', path: '/session/login', body: { userId: 'u' } })

/** main 의 대리인이 토큰을 붙이고, 응답의 토큰은 걷어 내고, 세션의 끝을 알리는지. */
describe('DaemonApi', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('로컬 토큰 헤더를 붙이고 로그인 응답의 token 은 보관한 뒤 빼서 돌려줌', async () => {
    const { api, seen, ended } = await harness(
      loginReply({ token: 'session-secret', expiresAt: 'x', user: { displayName: '월터' } }),
    )

    const response = await login(api)
    expect(response.body).toEqual({ expiresAt: 'x', user: { displayName: '월터' } })
    expect(api.hasSession()).toBe(true)

    await api.request({ method: 'GET', path: '/me' })
    expect(seen[0]?.headers['X-Stockholm-Local-Token']).toBe('local-secret')
    expect(seen[0]?.headers['Authorization']).toBeUndefined()
    expect(seen[1]?.headers['Authorization']).toBe('Bearer session-secret')

    await api.request({ method: 'POST', path: '/session/logout', body: {} })
    expect(api.hasSession()).toBe(false)
    expect(ended()).toBe(1)
  })

  it('마법사 토큰으로 보낸 /setup 요청이 401 이면 만료된 토큰을 잊음', async () => {
    const { api } = await harness((url) =>
      url.endsWith('/setup/admin/totp')
        ? json(200, { state: { state: 'ADMIN_CREATED' }, wizardToken: 'w' })
        : json(401, { code: 'x', message: 'expired' }),
    )

    await api.request({ method: 'POST', path: '/setup/admin/totp', body: { code: '1' } })
    expect(api.hasSession()).toBe(true)
    await api.request({ method: 'POST', path: '/setup/keys/DART', body: {} })
    expect(api.hasSession()).toBe(false)
  })

  it('세션이 있을 때만 스트림 헤더를 주고, 비우면 한 번만 알림', async () => {
    const { api, ended } = await harness(loginReply())

    expect(await api.authHeaders()).toBeNull()
    await login(api)
    expect(await api.authHeaders()).toEqual({
      'X-Stockholm-Local-Token': 'local-secret',
      Authorization: 'Bearer session-secret',
    })
    api.clearSession()
    api.clearSession()

    expect(ended()).toBe(1)
    expect(await api.authHeaders()).toBeNull()
  })

  it('데몬이 다시 떠 로컬 토큰이 바뀌면 파일을 다시 읽어 한 번 더 보내고 세션은 지킴', async () => {
    const { dir, api, seen, ended } = await harness((url, headers) => {
      if (url.endsWith('/session/login')) return json(200, { token: 's', expiresAt: 'x' })
      return headers['X-Stockholm-Local-Token'] === 'local-rotated'
        ? json(200, { ok: true })
        : json(401, { status: 401, error: 'Unauthorized' })
    })
    await login(api)

    await writeFile(join(dir, 'local-token'), 'local-rotated\n')
    const response = await api.request({ method: 'GET', path: '/me' })

    expect(response.status).toBe(200)
    expect(seen.map((s) => s.headers['X-Stockholm-Local-Token'])).toEqual([
      'local-secret',
      'local-secret',
      'local-rotated',
    ])
    expect(api.hasSession()).toBe(true)
    expect(ended()).toBe(0)
    expect((await api.authHeaders())?.['X-Stockholm-Local-Token']).toBe('local-rotated')
  })

  it('세션 만료 401 은 세션을 끝내지만 step-up 의 TOTP 거부 401 은 세션을 지킴', async () => {
    const { api, ended } = await harness((url) => {
      if (url.endsWith('/session/login')) return json(200, { token: 's', expiresAt: 'x' })
      if (url.endsWith('/session/step-up'))
        return json(401, { code: 'TotpRejectedException', message: 'TOTP 코드가 맞지 않음' })
      return json(401, { status: 401, error: 'Unauthorized', path: '/me' })
    })
    await login(api)

    const stepUp = await api.request({ method: 'POST', path: '/session/step-up', body: {} })
    expect(stepUp.status).toBe(401)
    expect(api.hasSession()).toBe(true)
    expect(ended()).toBe(0)

    const me = await api.request({ method: 'GET', path: '/me' })
    expect(me.status).toBe(401)
    expect(api.hasSession()).toBe(false)
    expect(ended()).toBe(1)
  })

  it('로그인 실패 401 은 세션이 없던 상태라 알리지 않음', async () => {
    const { api, ended } = await harness(() =>
      json(401, { code: 'AuthenticationFailedException', message: '…' }),
    )

    const response = await login(api)

    expect(response.status).toBe(401)
    expect(api.hasSession()).toBe(false)
    expect(ended()).toBe(0)
  })

  it('로그인 응답의 expiresAt 에 맞춰 세션을 스스로 끝냄', async () => {
    const loggedInAt = Date.parse('2026-10-05T09:00:00Z')
    const { api, scheduler, ended } = await harness(
      loginReply({ token: 's', expiresAt: '2026-10-05T21:00:00Z', user: {} }),
      () => loggedInAt,
    )

    await login(api)
    expect(scheduler.pendingDelays()).toEqual([12 * 60 * 60 * 1000])

    scheduler.fireNext()

    expect(api.hasSession()).toBe(false)
    expect(ended()).toBe(1)
    expect(await api.authHeaders()).toBeNull()
  })

  it('로그아웃하면 만료 예약을 취소함', async () => {
    const { api, scheduler } = await harness(
      loginReply({ token: 's', expiresAt: '2026-10-05T21:00:00Z', user: {} }),
      () => Date.parse('2026-10-05T09:00:00Z'),
    )
    await login(api)

    await api.request({ method: 'POST', path: '/session/logout', body: {} })

    expect(scheduler.pendingDelays()).toEqual([])
  })
})
