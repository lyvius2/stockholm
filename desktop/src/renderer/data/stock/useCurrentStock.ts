import { useEffect } from 'react'
import { localClient } from '../client/LocalClient'
import type { Client } from '../client/Client'
import { marketApi } from '../api/market'
import { portfolioApi } from '../api/portfolio'
import { useStockStore } from '../store/stock'
import { useStreamStore } from '../stream/store'

/** 종목 전환을 데몬에 알리는 디바운스. 설계상 2초. */
export const LAST_VIEWED_DEBOUNCE_MS = 2000

/**
 * 로그인 동안 현재 종목을 다룸.
 * 들어오면 시작 종목 규칙으로 첫 종목을 받고, 종목이 바뀌면 요약을 받아 상단 바에 보이고
 * 실시간 구독을 그 종목으로 바꾸며 2초 디바운스로 직전 종목을 저장함.
 * 로그아웃하면 비움.
 */
export function useCurrentStock(
  isActive: boolean,
  client: Client = localClient,
  debounceMs: number = LAST_VIEWED_DEBOUNCE_MS,
): void {
  const current = useStockStore((s) => s.current)

  useEffect(() => {
    if (!isActive) return undefined
    let isCancelled = false
    portfolioApi(client)
      .startStock()
      .then((start) => {
        if (!isCancelled) useStockStore.getState().start(start.symbol, start.reason)
      })
      .catch(() => {
        // 시작 종목을 못 받으면 사용자가 검색으로 고름. 네 영역은 빈 채로 둠
      })
    return () => {
      isCancelled = true
      useStockStore.getState().reset()
      useStreamStore.getState().setWatched([])
    }
  }, [isActive, client])

  useEffect(() => {
    if (!isActive || current === null) return undefined
    let isCancelled = false
    useStreamStore.getState().setWatched([current])
    marketApi(client)
      .findStock(current)
      .then((summary) => {
        if (!isCancelled) useStockStore.getState().setSummary(summary)
      })
      .catch(() => {
        // 마스터에 없는 종목이면 코드만 보임
      })
    const timer = setTimeout(() => {
      void portfolioApi(client)
        .recordLastViewed(current)
        .catch(() => undefined)
    }, debounceMs)
    return () => {
      isCancelled = true
      clearTimeout(timer)
    }
  }, [isActive, current, client, debounceMs])
}
