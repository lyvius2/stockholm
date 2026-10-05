import { create } from 'zustand'
import type { ApiUser } from '@renderer/generated/api-user'

interface SessionState {
  readonly user: ApiUser | null
  signIn(user: ApiUser): void
  signOut(): void
}

/** 로그인한 사용자. 로그아웃하면 화면 상태를 먼저 비운 뒤 모달을 띄우기 위해 여기부터 비움. */
export const useSessionStore = create<SessionState>((set) => ({
  user: null,
  signIn: (user) => set({ user }),
  signOut: () => set({ user: null }),
}))
