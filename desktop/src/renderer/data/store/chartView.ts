import { create } from 'zustand'
import type { ApiChartView } from '@renderer/generated/api-chart-view'
import { settingsApi } from '../api/settings'
import type { Client } from '../client/Client'
import { localClient } from '../client/LocalClient'

export type { ApiChartView as ChartView }

/** 데몬 기본값과 같음(간단 차트 · 장중 · 일봉). */
export const DEFAULT_CHART_VIEW: ApiChartView = {
  isDetailed: false,
  simpleRange: 'INTRADAY',
  detailedResolution: 'DAY',
}

interface ChartViewState {
  readonly view: ApiChartView
  /** 데몬에서 한 번 읽었는지. 읽기 전에는 기본값으로 그리되 저장은 미룸. */
  readonly isLoaded: boolean
  /** 로그인 한 번이 한 세대. reset 마다 올라가 이전 사용자의 늦은 응답·저장을 버림. */
  readonly generation: number
  load(client?: Client): Promise<void>
  /** 화면에 먼저 반영하고 데몬에 저장함. 저장은 한 번에 하나만 보내고, 보내는 동안의 변경은 마지막 값 하나로 뒤따라 보냄. */
  change(partial: Partial<ApiChartView>, client?: Client): Promise<void>
  reset(): void
}

/**
 * 차트 보기 설정(F3): 자세한 차트 토글과 모드별 마지막 기간 탭.
 * 사용자 설정이라 종목을 바꿔도 유지되고, 로그아웃하면 비워 다음 사용자가 제 설정을 읽게 함.
 */
export const useChartViewStore = create<ChartViewState>((set, get) => {
  let isSaving = false
  let hasPendingSave = false

  // 저장을 직렬화함. 전체 설정을 보내므로 병렬로 나가면 먼저 보낸 것이 나중에 처리돼 마지막 선택과 어긋날 수 있음
  async function drainSaves(client: Client, generation: number) {
    if (isSaving) {
      hasPendingSave = true
      return
    }
    isSaving = true
    try {
      do {
        hasPendingSave = false
        if (get().generation !== generation) return
        await settingsApi(client)
          .saveChartView(get().view)
          .catch(() => undefined)
      } while (hasPendingSave)
    } finally {
      isSaving = false
    }
  }

  return {
    view: DEFAULT_CHART_VIEW,
    isLoaded: false,
    generation: 0,
    load: async (client = localClient) => {
      const generation = get().generation
      try {
        const view = await settingsApi(client).chartView()
        // 읽는 동안 로그아웃·재로그인했으면 이전 사용자의 설정이라 버림
        if (get().generation !== generation) return
        set({ view, isLoaded: true })
      } catch {
        // 못 읽으면 기본값으로 그림. 저장은 isLoaded 가 false 라 하지 않음(옛 설정을 기본값으로 덮지 않게)
      }
    },
    change: async (partial, client = localClient) => {
      set((state) => ({ view: { ...state.view, ...partial } }))
      if (!get().isLoaded) return
      await drainSaves(client, get().generation)
    },
    reset: () =>
      set((state) => ({
        view: DEFAULT_CHART_VIEW,
        isLoaded: false,
        generation: state.generation + 1,
      })),
  }
})
