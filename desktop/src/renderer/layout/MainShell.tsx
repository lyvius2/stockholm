import { useQueryClient } from '@tanstack/react-query'
import type { ApiUser } from '@renderer/generated/api-user'
import { localClient } from '@renderer/data/client/LocalClient'
import { sessionApi } from '@renderer/data/api/session'
import { useSessionStore } from '@renderer/data/store/session'
import { LoginModal } from '@renderer/features/login/LoginModal'
import { StatusBar } from './StatusBar'
import { TopBar } from './TopBar'

/** 메인 화면 골격: 상단 바 두 줄 + 네 영역(좌 6 : 우 4, 좌 7:3, 우 4:6) + 상태줄. 로그인 전에는 빈 채로 흐려짐. */
export function MainShell({ user }: { readonly user: ApiUser | null }) {
  const queryClient = useQueryClient()
  const signOut = useSessionStore((s) => s.signOut)

  async function logout() {
    // 화면 상태를 먼저 비우고 나서 모달을 띄움. 흐려진 배경에 직전 사용자의 숫자가 남지 않게
    signOut()
    queryClient.clear()
    await sessionApi(localClient)
      .logout()
      .catch(() => undefined)
    await window.stockholm.session.clear()
  }

  // 로그인 모달은 aria-hidden 인 셸 밖에 두어 보조 기술과 테스트가 찾을 수 있게 함
  return (
    <>
      <div className="shell" aria-hidden={user === null}>
        <TopBar user={user} onLogout={() => void logout()} />
        <main className="workspace four-areas">
          <section className="area chart" aria-label="차트">
            {user !== null && <p className="placeholder">차트</p>}
          </section>
          <section className="area price" aria-label="가격">
            {user !== null && <p className="placeholder">가격</p>}
          </section>
          <section className="area order" aria-label="매수·매도">
            {user !== null && <p className="placeholder">매수 · 매도</p>}
          </section>
          <section className="area debate" aria-label="토론">
            {user !== null && <p className="placeholder">토론</p>}
          </section>
        </main>
        <StatusBar />
      </div>
      {user === null && <LoginModal />}
    </>
  )
}
