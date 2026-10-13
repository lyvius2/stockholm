import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import type { ApiWatchlist, ApiWatchlistItem } from '@renderer/generated/api-watchlist'
import { portfolioApi } from '@renderer/data/api/portfolio'
import { watchlistApi } from '@renderer/data/api/watchlist'
import { ApiError } from '@renderer/data/client/Client'
import { localClient } from '@renderer/data/client/LocalClient'
import { useDrawersStore } from '@renderer/data/store/drawers'
import { isSameSymbol, useStockStore } from '@renderer/data/store/stock'
import type { StreamSymbol } from '@renderer/data/stream/MarketStream'
import { symbolKey, useStreamStore } from '@renderer/data/stream/store'
import { useLiveQuote } from '@renderer/data/stream/useLivePrices'
import { DrawerFrame } from '@renderer/layout/DrawerFrame'
import { formatMoney } from '@renderer/shared/format/decimal'

export const WATCHLIST_QUERY_KEY = ['watchlist'] as const
const HOLDINGS_QUERY_KEY = ['portfolio', 'valuation'] as const

/** 실시간 구독은 화면당 20종목이고 네 영역의 종목이 하나를 쓰므로 서랍은 나머지만 선언함. */
export const LIVE_ROWS = 19

/** "전체" 탭은 그룹이 아니라 모든 그룹의 종목을 한 번에 보는 보기. */
const ALL = 'ALL'

// 응답 전의 빈 목록은 고정 배열로 두어 useMemo 의존성이 렌더마다 바뀌지 않게 함
const NO_GROUPS: ApiWatchlist['groups'] = []
const NO_ITEMS: ApiWatchlistItem[] = []

/**
 * 관심종목 서랍(F4): 그룹 탭 · 종목 행(현재가·보유 칩·💬) · 드래그 정렬.
 * 행을 누르면 네 영역이 그 종목으로 바뀌고 서랍은 닫힘(급등락과 다른 규칙).
 */
export function WatchlistDrawer() {
  const queryClient = useQueryClient()
  const [groupId, setGroupId] = useState<string>(ALL)
  const [groupError, setGroupError] = useState<string | null>(null)
  const watchlist = useQuery({
    queryKey: WATCHLIST_QUERY_KEY,
    queryFn: () => watchlistApi(localClient).watchlist(),
    retry: false,
  })
  const holdings = useQuery({
    queryKey: HOLDINGS_QUERY_KEY,
    queryFn: () => portfolioApi(localClient).valuation(),
    retry: false,
    staleTime: 30_000,
  })
  const refresh = () => queryClient.invalidateQueries({ queryKey: WATCHLIST_QUERY_KEY })
  const groups = watchlist.data?.groups ?? NO_GROUPS
  const items = watchlist.data?.items ?? NO_ITEMS
  const current = useStockStore((s) => s.current)
  // 지운 그룹을 보고 있었으면 전체로
  const activeGroup = groups.find((g) => g.groupId === groupId)
  const activeGroupId = activeGroup?.groupId
  // 목록은 응답·그룹이 바뀔 때만 새로 만듦. 렌더마다 새 배열이면 구독 effect 가 매번 돌아 시세를 잃음
  const visible = useMemo(
    () => (activeGroupId === undefined ? items : items.filter((i) => i.groupId === activeGroupId)),
    [items, activeGroupId],
  )
  const heldKeys = useMemo(
    () =>
      new Set(
        (holdings.data?.byMarket ?? []).flatMap((m) => m.holdings.map((h) => symbolKey(h.symbol))),
      ),
    [holdings.data],
  )

  // 보이는 행의 현재가를 받으려고 스트림에 더 선언함. 종목 목록이 실제로 바뀔 때만 다시 선언하고, 거두는 것은 닫힐 때만
  const liveSymbols = useMemo(() => visible.slice(0, LIVE_ROWS).map((i) => i.symbol), [visible])
  const liveKeys = liveSymbols.map(symbolKey).join(',')
  useEffect(() => {
    useStreamStore.getState().setExtraWatched('watchlist', liveSymbols)
    // 목록 내용이 같으면 다시 선언하지 않음(liveKeys 가 같음)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [liveKeys])
  useEffect(() => () => useStreamStore.getState().setExtraWatched('watchlist', []), [])

  const createGroup = useMutation({
    mutationFn: (name: string) => watchlistApi(localClient).createGroup(name),
    onSuccess: (group) => {
      setGroupId(group.groupId)
      setGroupError(null)
      void refresh()
    },
    onError: (e) => setGroupError(errorText(e)),
  })
  const renameGroup = useMutation({
    mutationFn: ({ id, name }: { id: string; name: string }) =>
      watchlistApi(localClient).renameGroup(id, name),
    onSuccess: () => {
      setGroupError(null)
      void refresh()
    },
    onError: (e) => setGroupError(errorText(e)),
  })
  const removeGroup = useMutation({
    mutationFn: (id: string) => watchlistApi(localClient).removeGroup(id),
    onSuccess: () => {
      setGroupId(ALL)
      void refresh()
    },
    onError: (e) => setGroupError(errorText(e)),
  })
  const addItem = useMutation({
    mutationFn: (symbol: StreamSymbol) =>
      watchlistApi(localClient).addItem(symbol, activeGroup?.groupId),
    onSuccess: () => {
      setGroupError(null)
      void refresh()
    },
    onError: (e) => setGroupError(errorText(e)),
  })
  const removeItem = useMutation({
    mutationFn: (item: ApiWatchlistItem) =>
      watchlistApi(localClient).removeItem(item.groupId, item.symbol),
    onSuccess: () => void refresh(),
    onError: (e) => setGroupError(errorText(e)),
  })
  const reorder = useMutation({
    mutationFn: ({ id, symbols }: { id: string; symbols: StreamSymbol[] }) =>
      watchlistApi(localClient).reorder(id, symbols),
    onSuccess: () => void refresh(),
    onError: (e) => setGroupError(errorText(e)),
  })

  const isCurrentListed =
    current !== null &&
    visible.some((i) => isSameSymbol(i.symbol, current)) &&
    (activeGroup !== undefined || items.some((i) => isSameSymbol(i.symbol, current)))

  return (
    <DrawerFrame
      kind="watchlist"
      title="관심종목"
      subtitle={`${groups.length}그룹 · ${items.length}`}
      footer={
        <>
          <span>드래그로 순서 변경</span>
          <span>·</span>
          <span>행을 누르면 네 영역이 바뀌고 서랍은 닫힘</span>
        </>
      }
    >
      <GroupTabs
        groups={groups}
        activeId={activeGroup?.groupId ?? ALL}
        onSelect={setGroupId}
        onCreate={(name) => createGroup.mutate(name)}
        onRename={(id, name) => renameGroup.mutate({ id, name })}
        onRemove={(id) => removeGroup.mutate(id)}
      />
      {groupError !== null && (
        <p className="warn small" role="alert">
          {groupError}
        </p>
      )}
      {watchlist.isError ? (
        <p className="warn small">관심종목을 받지 못했습니다</p>
      ) : (
        <WatchlistRows
          items={visible}
          heldKeys={heldKeys}
          liveCount={LIVE_ROWS}
          canReorder={activeGroup !== undefined}
          onRemove={(item) => removeItem.mutate(item)}
          onReorder={(symbols) => {
            if (activeGroup !== undefined) reorder.mutate({ id: activeGroup.groupId, symbols })
          }}
        />
      )}
      <div className="drawer-actions">
        <button
          type="button"
          disabled={current === null || isCurrentListed || addItem.isPending}
          onClick={() => {
            if (current !== null) addItem.mutate(current)
          }}
        >
          + 현재 종목 담기{activeGroup !== undefined ? ` (${activeGroup.name})` : ''}
        </button>
      </div>
    </DrawerFrame>
  )
}

function errorText(e: unknown): string {
  return e instanceof ApiError ? e.message : '데몬에 연결할 수 없습니다'
}

function GroupTabs({
  groups,
  activeId,
  onSelect,
  onCreate,
  onRename,
  onRemove,
}: {
  readonly groups: readonly { groupId: string; name: string }[]
  readonly activeId: string
  readonly onSelect: (id: string) => void
  readonly onCreate: (name: string) => void
  readonly onRename: (id: string, name: string) => void
  readonly onRemove: (id: string) => void
}) {
  // 이름 편집은 시작할 때의 그룹 id 를 붙들어 탭을 바꿔도 다른 그룹을 고치지 않음
  const [draft, setDraft] = useState<
    { mode: 'create'; text: string } | { mode: 'rename'; text: string; groupId: string } | null
  >(null)
  const [isConfirmingRemove, setConfirmingRemove] = useState(false)
  const active = groups.find((g) => g.groupId === activeId)

  // 탭을 바꾸면 편집·삭제 확인을 접음
  function selectTab(id: string) {
    setDraft(null)
    setConfirmingRemove(false)
    onSelect(id)
  }

  function submitDraft() {
    if (draft === null) return
    const name = draft.text.trim()
    if (name === '') {
      setDraft(null)
      return
    }
    if (draft.mode === 'create') onCreate(name)
    else onRename(draft.groupId, name)
    setDraft(null)
  }

  return (
    <div className="group-tabs">
      <div className="chips" role="tablist" aria-label="관심종목 그룹">
        <button
          type="button"
          role="tab"
          className="chip"
          aria-selected={active === undefined}
          onClick={() => selectTab(ALL)}
        >
          전체
        </button>
        {groups.map((group) => (
          <button
            key={group.groupId}
            type="button"
            role="tab"
            className="chip"
            aria-selected={group.groupId === activeId}
            onClick={() => selectTab(group.groupId)}
          >
            {group.name}
          </button>
        ))}
        <button
          type="button"
          className="chip"
          aria-label="그룹 추가"
          onClick={() => setDraft({ mode: 'create', text: '' })}
        >
          +
        </button>
      </div>
      {draft !== null && (
        <form
          className="group-draft"
          onSubmit={(e) => {
            e.preventDefault()
            submitDraft()
          }}
        >
          <input
            aria-label={draft.mode === 'create' ? '새 그룹 이름' : '그룹 이름'}
            maxLength={20}
            value={draft.text}
            onChange={(e) => setDraft({ ...draft, text: e.target.value })}
            onKeyDown={(e) => {
              if (e.key === 'Escape') setDraft(null)
            }}
          />
          <button type="submit">{draft.mode === 'create' ? '만들기' : '저장'}</button>
          <button type="button" onClick={() => setDraft(null)}>
            취소
          </button>
        </form>
      )}
      {active !== undefined && draft === null && !isConfirmingRemove && (
        <div className="group-actions small">
          <button
            type="button"
            className="link"
            onClick={() => setDraft({ mode: 'rename', text: active.name, groupId: active.groupId })}
          >
            이름 바꾸기
          </button>
          <button type="button" className="link" onClick={() => setConfirmingRemove(true)}>
            그룹 삭제
          </button>
        </div>
      )}
      {active !== undefined && isConfirmingRemove && (
        <div className="confirm-box small" role="alertdialog" aria-label="그룹 삭제 확인">
          <span>{active.name} 그룹과 담긴 종목을 지웁니다.</span>
          <div className="actions">
            <button
              type="button"
              onClick={() => {
                setConfirmingRemove(false)
                onRemove(active.groupId)
              }}
            >
              삭제
            </button>
            <button type="button" onClick={() => setConfirmingRemove(false)}>
              돌아가기
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

function WatchlistRows({
  items,
  heldKeys,
  liveCount,
  canReorder,
  onRemove,
  onReorder,
}: {
  readonly items: readonly ApiWatchlistItem[]
  readonly heldKeys: ReadonlySet<string>
  readonly liveCount: number
  readonly canReorder: boolean
  readonly onRemove: (item: ApiWatchlistItem) => void
  readonly onReorder: (symbols: StreamSymbol[]) => void
}) {
  const [dragIndex, setDragIndex] = useState<number | null>(null)
  if (items.length === 0) return <p className="placeholder small">담긴 종목이 없습니다</p>

  function dropOn(index: number) {
    if (dragIndex === null || dragIndex === index) return
    const next = [...items]
    const [moved] = next.splice(dragIndex, 1)
    if (moved === undefined) return
    next.splice(index, 0, moved)
    setDragIndex(null)
    onReorder(next.map((i) => i.symbol))
  }

  return (
    <ul className="watch-rows" aria-label="관심종목 목록">
      {items.map((item, index) => (
        <WatchlistRow
          key={`${item.groupId}:${symbolKey(item.symbol)}`}
          item={item}
          isHeld={heldKeys.has(symbolKey(item.symbol))}
          isLive={index < liveCount}
          isDraggable={canReorder}
          onDragStart={() => setDragIndex(index)}
          onDrop={() => dropOn(index)}
          onRemove={() => onRemove(item)}
        />
      ))}
    </ul>
  )
}

function WatchlistRow({
  item,
  isHeld,
  isLive,
  isDraggable,
  onDragStart,
  onDrop,
  onRemove,
}: {
  readonly item: ApiWatchlistItem
  readonly isHeld: boolean
  readonly isLive: boolean
  readonly isDraggable: boolean
  readonly onDragStart: () => void
  readonly onDrop: () => void
  readonly onRemove: () => void
}) {
  const current = useStockStore((s) => s.current)
  const select = useStockStore((s) => s.select)
  const closeDrawer = useDrawersStore((s) => s.closeDrawer)
  const { quote, isDelayed, isFresh } = useLiveQuote(item.symbol)
  const isCurrent = isSameSymbol(current, item.symbol)
  return (
    <li
      className={`watch-row${isCurrent ? ' current' : ''}`}
      data-symbol={symbolKey(item.symbol)}
      draggable={isDraggable}
      onDragStart={onDragStart}
      onDragOver={(e) => {
        if (isDraggable) e.preventDefault()
      }}
      onDrop={(e) => {
        e.preventDefault()
        onDrop()
      }}
    >
      <button
        type="button"
        className="watch-pick"
        aria-label={`${item.name ?? item.symbol.code} 보기`}
        onClick={() => {
          select(item.symbol)
          closeDrawer('watchlist')
        }}
      >
        <span className="watch-name">{item.name ?? item.symbol.code}</span>
        <span className="watch-code small">{item.symbol.code}</span>
        {item.symbol.market === 'US' && <span className="chip">US</span>}
        {isHeld && <span className="chip">보유</span>}
      </button>
      <span className={`num watch-price${isFresh ? '' : ' stale'}`}>
        {quote === undefined || !isLive ? '—' : formatMoney(quote.last.amount, quote.last.currency)}
        {isLive && quote !== undefined && isDelayed && (
          <span className="chip warn-chip" title="스트림이 끊겨 마지막 값임">
            지연
          </span>
        )}
      </span>
      <button type="button" className="link" disabled title="커뮤니티 서랍은 다음 조각에서">
        💬
      </button>
      <button type="button" className="link" aria-label="빼기" onClick={onRemove}>
        ✕
      </button>
    </li>
  )
}
