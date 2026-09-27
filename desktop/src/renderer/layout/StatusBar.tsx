import { useQuery } from '@tanstack/react-query'
import { useDaemonStatus } from '@renderer/data/daemon/useDaemonStatus'

/** 하단 상태줄: 데몬 · 버전. API·LLM 예산·Ollama 는 뒤 단계에서 채움. */
export function StatusBar() {
  const daemon = useDaemonStatus()
  const version = useQuery({
    queryKey: ['app', 'version'],
    queryFn: () => window.stockholm.app.version(),
  })
  return (
    <footer className="statusbar">
      <span className="daemon-state" data-state={daemon.state}>
        데몬{' '}
        {daemon.state === 'connected'
          ? '연결됨'
          : daemon.state === 'unreachable'
            ? '연결 안 됨'
            : '확인 중'}
      </span>
      <span className="spacer" />
      <span>v{version.data ?? '—'}</span>
    </footer>
  )
}
