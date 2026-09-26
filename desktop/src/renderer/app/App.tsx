import { useDaemonStatus } from '@renderer/data/daemon/useDaemonStatus'

/** 1단계 골격: 상단 바와 데몬 연결 상태만 있음. 마법사·로그인·네 영역은 뒤이어 채움. */
export function App() {
  const daemon = useDaemonStatus()
  return (
    <div className="shell">
      <header className="topbar">
        <span className="brand">Stockholm</span>
      </header>
      <main className="workspace">
        <p className="daemon-state" data-state={daemon.state}>
          {daemonLabel(daemon.state)}
        </p>
      </main>
    </div>
  )
}

function daemonLabel(state: ReturnType<typeof useDaemonStatus>['state']): string {
  switch (state) {
    case 'connected':
      return '데몬 연결됨'
    case 'unreachable':
      return '데몬에 연결할 수 없음 (127.0.0.1:2609)'
    case 'checking':
      return '데몬 연결 확인 중…'
  }
}
