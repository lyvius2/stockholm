import { useQueries, useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import type { ApiMover, ApiMoverBoard, ApiStockFlags } from '@renderer/generated/api-mover-board'
import { marketApi } from '@renderer/data/api/market'
import { portfolioApi } from '@renderer/data/api/portfolio'
import { localClient } from '@renderer/data/client/LocalClient'
import { isSameSymbol, useStockStore } from '@renderer/data/store/stock'
import type { StreamSymbol } from '@renderer/data/stream/MarketStream'
import { symbolKey } from '@renderer/data/stream/store'
import { DrawerFrame } from '@renderer/layout/DrawerFrame'
import { formatMoney, formatPercentFromRatio, signOf } from '@renderer/shared/format/decimal'

/** 설계상 10초 갱신. 데몬도 10초 안의 재요청에는 같은 판을 돌려줌. */
export const MOVERS_REFRESH_MS = 10_000

const MARKET_TITLE: Record<StreamSymbol['market'], string> = { KR: '🇰🇷 국내', US: '🇺🇸 미국' }
const SESSION_LABEL: Record<ApiMoverBoard['session'], string> = {
  DAY_MARKET: '데이마켓',
  PRE: '프리마켓',
  REGULAR: '정규장',
  AFTER: '애프터마켓',
  CLOSED: '전일 기준',
}

/**
 * 실시간 급등락 서랍(F11): 국내·미국 × 급등 5·급락 5.
 * 순위 옆에 직전 판 대비 변동(↑n·↓n·NEW), 행에 종목 경고·보유 칩.
 * 행을 누르면 네 영역이 바뀌고 서랍은 열린 채 남음. 정보 표시일 뿐 자동 주문의 입력이 아님.
 */
export function MoversDrawer() {
  const boards = useQueries({
    queries: (['KR', 'US'] as const).map((market) => ({
      queryKey: ['market', 'movers', market] as const,
      queryFn: () => marketApi(localClient).movers(market),
      refetchInterval: MOVERS_REFRESH_MS,
      retry: false,
    })),
  })
  const holdings = useQuery({
    queryKey: ['portfolio', 'valuation'] as const,
    queryFn: () => portfolioApi(localClient).valuation(),
    retry: false,
    staleTime: 30_000,
  })
  const heldKeys = useMemo(
    () =>
      new Set(
        (holdings.data?.byMarket ?? []).flatMap((m) => m.holdings.map((h) => symbolKey(h.symbol))),
      ),
    [holdings.data],
  )
  const latest = boards
    .map((b) => b.data?.asOf)
    .filter((t): t is string => t !== undefined)
    .sort()
    .at(-1)
  const isDelayed = boards.some((b) => b.data?.isDelayed === true)

  return (
    <DrawerFrame
      kind="movers"
      title="⚡ 실시간 급등락"
      subtitle={
        <>
          {isDelayed ? <span className="chip warn-chip">지연</span> : '10초 갱신'}
          {latest !== undefined && ` · ${timeLabel(latest)}`}
        </>
      }
      footer={
        <>
          <span>순위 옆 화살표 = 직전 갱신 대비 순위 변동</span>
          <span>·</span>
          <span>클릭 → 그 종목으로 이동(서랍 유지)</span>
        </>
      }
    >
      <div className="chips" role="tablist" aria-label="급등락 기준">
        <button type="button" role="tab" className="chip" aria-selected>
          등락률
        </button>
        <button
          type="button"
          role="tab"
          className="chip"
          aria-selected={false}
          disabled
          title="준비 중"
        >
          거래량 급증
        </button>
        <button
          type="button"
          role="tab"
          className="chip"
          aria-selected={false}
          disabled
          title="준비 중"
        >
          체결 강도
        </button>
      </div>
      {boards.map((board, index) => {
        const market = index === 0 ? 'KR' : 'US'
        if (board.isError)
          return (
            <p key={market} className="warn small">
              {MARKET_TITLE[market]} 순위를 받지 못했습니다
            </p>
          )
        if (board.data === undefined) return null
        return <BoardSection key={market} board={board.data} heldKeys={heldKeys} />
      })}
    </DrawerFrame>
  )
}

function BoardSection({
  board,
  heldKeys,
}: {
  readonly board: ApiMoverBoard
  readonly heldKeys: ReadonlySet<string>
}) {
  const title = MARKET_TITLE[board.market]
  const sessionChip = (
    <span className={`chip${board.session === 'REGULAR' ? ' live-chip' : ''}`}>
      {SESSION_LABEL[board.session]}
    </span>
  )
  return (
    <section className="mover-section" aria-label={`${title} 급등락`}>
      <h4>
        {title} · 급등 {sessionChip}
      </h4>
      <MoverList movers={board.gainers} heldKeys={heldKeys} label={`${title} 급등`} />
      <h4>{title} · 급락</h4>
      <MoverList movers={board.losers} heldKeys={heldKeys} label={`${title} 급락`} />
    </section>
  )
}

function MoverList({
  movers,
  heldKeys,
  label,
}: {
  readonly movers: readonly ApiMover[]
  readonly heldKeys: ReadonlySet<string>
  readonly label: string
}) {
  if (movers.length === 0) return <p className="placeholder small">순위 없음</p>
  return (
    <ol className="mover-rows" aria-label={label}>
      {movers.map((mover) => (
        <MoverRow
          key={symbolKey(mover.symbol)}
          mover={mover}
          isHeld={heldKeys.has(symbolKey(mover.symbol))}
        />
      ))}
    </ol>
  )
}

function MoverRow({ mover, isHeld }: { readonly mover: ApiMover; readonly isHeld: boolean }) {
  const current = useStockStore((s) => s.current)
  const select = useStockStore((s) => s.select)
  const name = useQuery({
    queryKey: ['stocks', 'summary', mover.symbol.market, mover.symbol.code] as const,
    queryFn: () => marketApi(localClient).findStock(mover.symbol),
    staleTime: Infinity,
    retry: false,
  })
  const sign = mover.changeRate === null ? 'flat' : signOf(mover.changeRate)
  return (
    <li
      className={`mover-row${isSameSymbol(current, mover.symbol) ? ' current' : ''}`}
      data-symbol={symbolKey(mover.symbol)}
    >
      <span className="mover-rank num">{mover.rank}</span>
      <button
        type="button"
        className="watch-pick"
        aria-label={`${name.data?.name ?? mover.symbol.code} 보기`}
        onClick={() => select(mover.symbol)}
      >
        <span className="watch-name">{name.data?.name ?? mover.symbol.code}</span>
        <span className="watch-code small">{mover.symbol.code}</span>
        <RankChange change={mover.rankChange} />
        {isHeld && <span className="chip">보유</span>}
        <FlagChips flags={mover.flags} />
      </button>
      <span className={`num mover-price ${sign}`}>
        <span>{formatMoney(mover.last.amount, mover.last.currency)}</span>
        <span className="small">
          {mover.changeRate === null
            ? '—'
            : `${sign === 'up' ? '+' : ''}${formatPercentFromRatio(mover.changeRate)}`}
        </span>
      </span>
    </li>
  )
}

function RankChange({ change }: { readonly change: ApiMover['rankChange'] }) {
  if (change.kind === 'SAME') return null
  if (change.kind === 'NEW') return <span className="rank-change new">NEW</span>
  const arrow = change.kind === 'UP' ? '↑' : '↓'
  return (
    <span className={`rank-change ${change.kind === 'UP' ? 'up' : 'down'}`}>
      {arrow}
      {change.places ?? ''}
    </span>
  )
}

/**
 * 종목 경고 칩.
 * 값을 모르는 항목(null)과 경고 조회 자체가 실패한 경우(flags null)는 "확인 필요" 로 보여
 * F7 제외 필터의 "모르면 제외" 가 드러나게 함.
 */
function FlagChips({ flags }: { readonly flags: ApiStockFlags | null }) {
  if (flags === null) return <span className="chip warn-chip">확인 필요</span>
  const chips: string[] = []
  if (flags.isInvestmentRisk) chips.push('투자위험')
  else if (flags.isInvestmentWarning) chips.push('투자경고')
  if (flags.isOverheated) chips.push('단기과열')
  if (flags.isLiquidationTrading) chips.push('정리매매')
  if (flags.isViStatic || flags.isViDynamic) chips.push('VI')
  if (flags.isTradingHalted === true) chips.push('거래정지')
  if (flags.isAdministrative === true) chips.push('관리')
  if (flags.hasUnknownWarning || flags.isTradingHalted === null || flags.isAdministrative === null)
    chips.push('확인 필요')
  return (
    <>
      {chips.map((chip) => (
        <span key={chip} className="chip warn-chip">
          {chip}
        </span>
      ))}
    </>
  )
}

function timeLabel(iso: string): string {
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).format(new Date(iso))
}
