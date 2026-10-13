import { create } from 'zustand'

/** 서랍 네 개. 왼쪽 둘(커뮤니티·관심종목)과 오른쪽 둘(종목 정보·급등락). */
export type DrawerKind = 'community' | 'watchlist' | 'stockInfo' | 'movers'
export type DrawerSide = 'left' | 'right'

export const DRAWER_SIDE: Record<DrawerKind, DrawerSide> = {
  community: 'left',
  watchlist: 'left',
  stockInfo: 'right',
  movers: 'right',
}

/** 기본 폭(작업 영역의 %). 구분선으로 15~50% 사이에서 조절함. */
export const DEFAULT_DRAWER_WIDTH: Record<DrawerKind, number> = {
  community: 28,
  watchlist: 30,
  stockInfo: 32,
  movers: 25,
}
export const MIN_DRAWER_WIDTH = 15
export const MAX_DRAWER_WIDTH = 50

interface DrawersState {
  /** 열린 서랍. 나중에 연 것이 뒤에 옴(Esc 는 뒤에서부터 닫음). */
  readonly open: readonly DrawerKind[]
  readonly widths: Readonly<Record<DrawerKind, number>>
  /** 한쪽을 열면 반대쪽은 모두 닫힘(좌우 배타). 같은 쪽은 겹쳐 열림. */
  openDrawer(kind: DrawerKind): void
  closeDrawer(kind: DrawerKind): void
  toggle(kind: DrawerKind): void
  /** 가장 나중에 연 서랍 하나를 닫음. 열린 것이 없으면 false. */
  closeTopmost(): boolean
  closeAll(): void
  setWidth(kind: DrawerKind, percent: number): void
  resetWidth(kind: DrawerKind): void
  reset(): void
}

/**
 * 서랍 상태(11.3 서랍 규칙).
 * 폭은 지금은 세션 안에서만 기억함(사용자 설정 저장은 레이아웃 비율과 함께 다음 조각).
 */
export const useDrawersStore = create<DrawersState>((set, get) => ({
  open: [],
  widths: { ...DEFAULT_DRAWER_WIDTH },
  openDrawer: (kind) =>
    set((state) => {
      if (state.open.includes(kind)) return state
      const sameSide = state.open.filter((k) => DRAWER_SIDE[k] === DRAWER_SIDE[kind])
      return { open: [...sameSide, kind] }
    }),
  closeDrawer: (kind) => set((state) => ({ open: state.open.filter((k) => k !== kind) })),
  toggle: (kind) => {
    if (get().open.includes(kind)) get().closeDrawer(kind)
    else get().openDrawer(kind)
  },
  closeTopmost: () => {
    const { open } = get()
    if (open.length === 0) return false
    set({ open: open.slice(0, -1) })
    return true
  },
  closeAll: () => set({ open: [] }),
  setWidth: (kind, percent) =>
    set((state) => ({
      widths: {
        ...state.widths,
        [kind]: Math.min(MAX_DRAWER_WIDTH, Math.max(MIN_DRAWER_WIDTH, Math.round(percent))),
      },
    })),
  resetWidth: (kind) =>
    set((state) => ({ widths: { ...state.widths, [kind]: DEFAULT_DRAWER_WIDTH[kind] } })),
  reset: () => set({ open: [], widths: { ...DEFAULT_DRAWER_WIDTH } }),
}))

export function useIsDrawerOpen(kind: DrawerKind): boolean {
  return useDrawersStore((s) => s.open.includes(kind))
}

/** 그 쪽에 열린 서랍들의 폭 합(%). 네 영역을 얼마나 밀어낼지 정함. */
export function useOpenWidth(side: DrawerSide): number {
  return useDrawersStore((s) =>
    s.open.filter((k) => DRAWER_SIDE[k] === side).reduce((sum, k) => sum + s.widths[k], 0),
  )
}
