import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useState } from 'react'
import type { ApiUser } from '@renderer/generated/api-user'
import { localClient } from '@renderer/data/client/LocalClient'
import { sessionApi } from '@renderer/data/api/session'
import { useSessionStore } from '@renderer/data/store/session'
import { useMarketStream } from '@renderer/data/stream/useMarketStream'
import { useCurrentStock } from '@renderer/data/stock/useCurrentStock'
import { useStockStore, type StartReason } from '@renderer/data/store/stock'
import { PriceArea } from '@renderer/features/price/PriceArea'
import { PollingFallbackAgent } from '@renderer/features/price/PollingFallbackAgent'
import { OrderArea } from '@renderer/features/order/OrderArea'
import { SearchPopover } from '@renderer/features/search/SearchPopover'
import { Toast } from '@renderer/shared/ui/Toast'
import { LoginModal } from '@renderer/features/login/LoginModal'
import { StatusBar } from './StatusBar'
import { TopBar } from './TopBar'

// 기본 종목으로 시작할 때는 토스트 없음(설계)
const START_TOASTS: Partial<Record<StartReason, string>> = {
  LAST_VIEWED: '직전에 보던 종목입니다',
  LARGEST_POSITION: '보유 중 평가금액이 가장 큰 종목을 열었습니다',
}

/** 메인 화면 골격: 상단 바 두 줄 + 네 영역(좌 6 : 우 4, 좌 7:3, 우 4:6) + 상태줄. 로그인 전에는 빈 채로 흐려짐. */
export function MainShell({ user }: { readonly user: ApiUser | null }) {
  const queryClient = useQueryClient()
  const signOut = useSessionStore((s) => s.signOut)
  // 로그인해 있는 동안 데몬 실시간 스트림을 열어 둠(지수 티커는 종목 없이도 옴)
  useMarketStream(user !== null)
  // 시작 종목 → 현재 종목 → 실시간 구독·직전 종목 저장
  useCurrentStock(user !== null)
  const startReason = useStockStore((s) => s.startReason)
  const consumeStartReason = useStockStore((s) => s.consumeStartReason)
  const startToast = useCallback(() => consumeStartReason(), [consumeStartReason])
  const [isSearchOpen, setSearchOpen] = useState(false)
  const closeSearch = useCallback(() => setSearchOpen(false), [])

  // ⌘K(맥)·Ctrl+K 로 종목 검색
  useEffect(() => {
    if (user === null) return undefined
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setSearchOpen(true)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [user])

  // 세션이 만료되거나 401 로 끝나면 main 이 알림. 로그아웃과 같이 화면 상태를 비워 로그인 모달로 감
  useEffect(
    () =>
      window.stockholm.session.onEnded(() => {
        signOut()
        queryClient.clear()
      }),
    [signOut, queryClient],
  )

  // 마법사 폭(1100)에서 메인으로 넘어오면 창을 넓힘
  useEffect(() => {
    void window.stockholm.app.expandForMain()
  }, [])

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
        <TopBar user={user} onLogout={() => void logout()} onSearch={() => setSearchOpen(true)} />
        <main className="workspace four-areas">
          <section className="area chart" aria-label="차트">
            {user !== null && <p className="placeholder">차트</p>}
          </section>
          <section className="area price" aria-label="가격">
            {user !== null && <PriceArea />}
          </section>
          <section className="area order" aria-label="매수·매도">
            {user !== null && <OrderArea />}
          </section>
          <section className="area debate" aria-label="토론">
            {user !== null && <p className="placeholder">토론</p>}
          </section>
        </main>
        <StatusBar />
      </div>
      {user !== null && <PollingFallbackAgent />}
      {user === null && <LoginModal />}
      {user !== null && isSearchOpen && <SearchPopover onClose={closeSearch} />}
      {user !== null && startReason !== null && START_TOASTS[startReason] !== undefined && (
        <Toast message={START_TOASTS[startReason] ?? ''} onDone={startToast} />
      )}
    </>
  )
}
