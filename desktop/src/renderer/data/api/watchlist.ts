import type { ApiWatchlist } from '@renderer/generated/api-watchlist'
import type { ApiWatchlistGroup } from '@renderer/generated/api-watchlist-group'
import type { ApiWatchlistItem } from '@renderer/generated/api-watchlist-item'
import type { Client } from '../client/Client'
import type { StreamSymbol } from '../stream/MarketStream'

/** 관심종목(F4). 세션 사용자의 것만 오가며 변경은 디바이스 간 동기화됨. */
export function watchlistApi(client: Client) {
  const groupPath = (groupId: string) => `/watchlist/groups/${encodeURIComponent(groupId)}`
  return {
    watchlist: () => client.request<ApiWatchlist>('GET', '/watchlist'),
    createGroup: (name: string) =>
      client.request<ApiWatchlistGroup>('POST', '/watchlist/groups', { name }),
    renameGroup: (groupId: string, name: string) =>
      client.request<null>('PUT', groupPath(groupId), { name }),
    removeGroup: (groupId: string) => client.request<null>('DELETE', groupPath(groupId)),
    /** groupId 가 없으면 첫 그룹(없으면 기본 그룹이 생김). */
    addItem: (symbol: StreamSymbol, groupId?: string) =>
      client.request<ApiWatchlistItem>('POST', '/watchlist/items', {
        groupId: groupId ?? null,
        symbol,
      }),
    removeItem: (groupId: string, symbol: StreamSymbol) =>
      client.request<null>(
        'DELETE',
        `${groupPath(groupId)}/items/${encodeURIComponent(symbol.market)}/${encodeURIComponent(symbol.code)}`,
      ),
    reorder: (groupId: string, symbols: readonly StreamSymbol[]) =>
      client.request<null>('PUT', `${groupPath(groupId)}/order`, { symbols }),
  }
}
