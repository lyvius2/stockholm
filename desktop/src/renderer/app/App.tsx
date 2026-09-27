import { useQuery } from '@tanstack/react-query'
import { ApiError } from '@renderer/data/client/Client'
import { localClient } from '@renderer/data/client/LocalClient'
import { setupApi } from '@renderer/data/api/setup'
import { useDaemonStatus } from '@renderer/data/daemon/useDaemonStatus'
import { useSessionStore } from '@renderer/data/store/session'
import { Wizard } from '@renderer/features/setup/Wizard'
import { MainShell } from '@renderer/layout/MainShell'

const SETUP_COMPLETE = 'COMPLETE'

/**
 * 진입 분기: 데몬 대기 → 마법사(SetupState 가 COMPLETE 아님) → 메인 + 로그인 모달.
 * COMPLETE 뒤에는 /setup/state 가 404 라 그것을 완료로 봄.
 */
export function App() {
  const daemon = useDaemonStatus()
  const user = useSessionStore((s) => s.user)
  const setup = useQuery({
    queryKey: ['setup', 'state'],
    enabled: daemon.state === 'connected',
    retry: false,
    queryFn: async () => {
      try {
        return await setupApi(localClient).state()
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null
        throw e
      }
    },
  })

  if (daemon.state !== 'connected' || setup.isPending) {
    return <Splash message={daemon.state === 'unreachable' ? '데몬 기동 중…' : '준비 중…'} />
  }
  if (setup.isError) return <Splash message="데몬 응답을 읽을 수 없습니다. 앱을 다시 시작하세요." />
  if (setup.data !== null && setup.data.state !== SETUP_COMPLETE)
    return <Wizard state={setup.data} />
  return <MainShell user={user} />
}

function Splash({ message }: { readonly message: string }) {
  return (
    <div className="splash">
      <span className="brand">Stockholm</span>
      <p className="daemon-state">{message}</p>
    </div>
  )
}
