import type { ReactNode } from 'react'
import { useRef } from 'react'
import {
  DRAWER_SIDE,
  DEFAULT_DRAWER_WIDTH,
  useDrawersStore,
  type DrawerKind,
} from '@renderer/data/store/drawers'

interface DrawerFrameProps {
  readonly kind: DrawerKind
  readonly title: ReactNode
  readonly subtitle?: ReactNode
  readonly children: ReactNode
  readonly footer?: ReactNode
}

/**
 * 서랍 한 개의 틀: 제목 줄(✕) · 내용 · 바닥 줄 · 폭 조절 구분선.
 * 폭은 작업 영역의 %이고 구분선을 끌어 15~50% 로 바꾸며 더블클릭으로 기본값.
 * 네 영역을 덮지 않고 밀어내는 배치는 MainShell 의 작업 영역이 함.
 */
export function DrawerFrame({ kind, title, subtitle, children, footer }: DrawerFrameProps) {
  const width = useDrawersStore((s) => s.widths[kind])
  const closeDrawer = useDrawersStore((s) => s.closeDrawer)
  const setWidth = useDrawersStore((s) => s.setWidth)
  const resetWidth = useDrawersStore((s) => s.resetWidth)
  const frameRef = useRef<HTMLElement | null>(null)
  const side = DRAWER_SIDE[kind]

  function startResize(e: React.PointerEvent<HTMLDivElement>) {
    const workspace = frameRef.current?.parentElement
    if (workspace === null || workspace === undefined) return
    const total = workspace.getBoundingClientRect().width
    if (total === 0) return
    const startX = e.clientX
    const startWidth = width
    const handle = e.currentTarget
    handle.setPointerCapture(e.pointerId)
    const onMove = (move: PointerEvent) => {
      // 왼쪽 서랍은 오른쪽으로 끌수록 넓어지고, 오른쪽 서랍은 반대
      const delta = ((move.clientX - startX) / total) * 100
      setWidth(kind, side === 'left' ? startWidth + delta : startWidth - delta)
    }
    const onUp = () => {
      handle.removeEventListener('pointermove', onMove)
      handle.removeEventListener('pointerup', onUp)
    }
    handle.addEventListener('pointermove', onMove)
    handle.addEventListener('pointerup', onUp)
  }

  const handle = (
    <div
      className="drawer-splitter"
      role="separator"
      aria-orientation="vertical"
      aria-label={`${labelOf(kind)} 폭 조절`}
      aria-valuenow={width}
      aria-valuemin={15}
      aria-valuemax={50}
      title={`드래그로 폭 조절 · 더블클릭으로 ${DEFAULT_DRAWER_WIDTH[kind]}% 복원`}
      onPointerDown={startResize}
      onDoubleClick={() => resetWidth(kind)}
    />
  )
  return (
    <aside
      ref={frameRef}
      className={`drawer drawer-${side}`}
      data-kind={kind}
      aria-label={labelOf(kind)}
      style={{ flexBasis: `${width}%` }}
    >
      {side === 'right' && handle}
      <div className="drawer-body">
        <header className="drawer-head">
          <b>{title}</b>
          {subtitle !== undefined && <span className="small">{subtitle}</span>}
          <span className="spacer" />
          <button
            type="button"
            className="link"
            aria-label="닫기"
            onClick={() => closeDrawer(kind)}
          >
            ✕
          </button>
        </header>
        <div className="drawer-content">{children}</div>
        {footer !== undefined && <footer className="drawer-foot small">{footer}</footer>}
      </div>
      {side === 'left' && handle}
    </aside>
  )
}

export function labelOf(kind: DrawerKind): string {
  switch (kind) {
    case 'watchlist':
      return '관심종목'
    case 'community':
      return '커뮤니티'
    case 'stockInfo':
      return '종목 정보'
    case 'movers':
      return '실시간 급등락'
  }
}
