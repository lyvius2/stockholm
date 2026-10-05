import { useQuery } from '@tanstack/react-query'
import { useDaemonStatus } from '@renderer/data/daemon/useDaemonStatus'
import { useStreamStore } from '@renderer/data/stream/store'

const STREAM_LABEL = {
  open: '실시간',
  connecting: '실시간 연결 중',
  closed: '실시간 끊김',
} as const

/** 하단 상태줄: 데몬 · 실시간 스트림 · 버전. API·LLM 예산·Ollama 는 뒤 단계에서 채움. */
export function StatusBar() {
  const daemon = useDaemonStatus()
  const stream = useStreamStore((s) => s.connection)
  const isFeedLive = useStreamStore((s) => s.feed?.isLive ?? true)
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
      <span className="stream-state" data-state={stream} data-feed-live={isFeedLive}>
        {stream === 'open' && !isFeedLive ? '시세 지연' : STREAM_LABEL[stream]}
      </span>
      <span className="spacer" />
      <span>v{version.data ?? '—'}</span>
    </footer>
  )
}
