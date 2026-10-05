import { useQuery } from '@tanstack/react-query'

export type DaemonState = 'checking' | 'connected' | 'unreachable'

const REFRESH_INTERVAL_MS = 3000

/** preload 브리지를 통해 데몬 헬스를 주기적으로 확인함. 렌더러는 데몬에 직접 닿지 않음. */
export function useDaemonStatus(): { readonly state: DaemonState } {
  const query = useQuery({
    queryKey: ['daemon', 'status'],
    queryFn: () => window.stockholm.daemon.status(),
    refetchInterval: REFRESH_INTERVAL_MS,
  })
  if (query.data === undefined) return { state: 'checking' }
  return { state: query.data.reachable ? 'connected' : 'unreachable' }
}
