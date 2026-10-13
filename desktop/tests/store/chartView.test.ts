import type { ApiRequest } from '../../src/preload/bridge'
import { useChartViewStore } from '@renderer/data/store/chartView'
import { installBridge, ok, status } from '../support/bridge'

type Deferred = { resolve: () => void }

describe('useChartViewStore', () => {
  const requests: ApiRequest[] = []

  beforeEach(() => {
    requests.length = 0
    useChartViewStore.getState().reset()
  })

  it('읽는 동안 로그아웃·재로그인하면 이전 사용자의 늦은 응답을 버림', async () => {
    let releaseFirst: () => void = () => undefined
    const first = new Promise<void>((resolve) => {
      releaseFirst = resolve
    })
    installBridge(async (request) => {
      requests.push(request)
      if (request.path !== '/session/chart-view') return status(404)
      if (requests.length === 1) {
        await first
        return ok({ isDetailed: true, simpleRange: 'ALL', detailedResolution: 'YEAR' })
      }
      return ok({ isDetailed: false, simpleRange: 'WEEK_1', detailedResolution: 'DAY' })
    })
    const store = useChartViewStore.getState()
    const loadA = store.load()
    store.reset()
    await store.load()
    expect(useChartViewStore.getState().view.simpleRange).toBe('WEEK_1')

    releaseFirst()
    await loadA
    expect(useChartViewStore.getState().view.simpleRange).toBe('WEEK_1')
    expect(useChartViewStore.getState().isLoaded).toBe(true)
  })

  it('빠른 연속 변경은 저장을 하나씩 보내고 마지막 값 하나로 따라잡음', async () => {
    const pendingSaves: Deferred[] = []
    installBridge((request) => {
      requests.push(request)
      if (request.path !== '/session/chart-view') return status(404)
      if (request.method === 'GET')
        return ok({ isDetailed: false, simpleRange: 'INTRADAY', detailedResolution: 'DAY' })
      return new Promise((resolve) => {
        pendingSaves.push({ resolve: () => resolve(ok(request.body)) })
      })
    })
    const store = useChartViewStore.getState()
    await store.load()

    const changes = [
      store.change({ isDetailed: true }),
      store.change({ simpleRange: 'YEAR_1' }),
      store.change({ detailedResolution: 'MINUTE_5' }),
    ]
    const puts = () => requests.filter((r) => r.method === 'PUT')
    expect(puts()).toHaveLength(1)
    expect(useChartViewStore.getState().view).toEqual({
      isDetailed: true,
      simpleRange: 'YEAR_1',
      detailedResolution: 'MINUTE_5',
    })

    pendingSaves[0]?.resolve()
    await vi.waitFor(() => expect(puts()).toHaveLength(2))
    expect(puts()[1]?.body).toEqual({
      isDetailed: true,
      simpleRange: 'YEAR_1',
      detailedResolution: 'MINUTE_5',
    })
    pendingSaves[1]?.resolve()
    await Promise.all(changes)
    expect(puts()).toHaveLength(2)
  })

  it('저장이 밀려 있는 동안 로그아웃하면 다음 사용자에게 저장하지 않음', async () => {
    const pendingSaves: Deferred[] = []
    installBridge((request) => {
      requests.push(request)
      if (request.path !== '/session/chart-view') return status(404)
      if (request.method === 'GET')
        return ok({ isDetailed: false, simpleRange: 'INTRADAY', detailedResolution: 'DAY' })
      return new Promise((resolve) => {
        pendingSaves.push({ resolve: () => resolve(ok(request.body)) })
      })
    })
    const store = useChartViewStore.getState()
    await store.load()
    const first = store.change({ isDetailed: true })
    const second = store.change({ simpleRange: 'ALL' })
    store.reset()
    pendingSaves[0]?.resolve()
    await Promise.all([first, second])

    expect(requests.filter((r) => r.method === 'PUT')).toHaveLength(1)
  })
})
