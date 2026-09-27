import { useState } from 'react'
import type { ApiUser } from '@renderer/generated/api-user'

interface TopBarProps {
  readonly user: ApiUser | null
  readonly onLogout: () => void
}

/** 두 줄 상단 바 골격. 1행: 브랜드 · 지수 티커 · 서랍 버튼 / 2행: 종목 · 패널 버튼 · 장 상태 · 사용자. */
export function TopBar({ user, onLogout }: TopBarProps) {
  const [menuOpen, setMenuOpen] = useState(false)
  return (
    <header className="topbar">
      <div className="topbar-row">
        <span className="brand">Stockholm</span>
        <span className="ticker" aria-label="지수 티커">
          지수 —
        </span>
        <span className="spacer" />
        <button type="button">☰ 관심종목</button>
        <button type="button">🏛️ 연기금종목</button>
        <button type="button">⚡ 실시간 급등락</button>
        <button type="button" className="danger">
          전체 정지
        </button>
      </div>
      <div className="topbar-row">
        <button type="button" className="symbol">
          종목 선택
        </button>
        <button type="button">💬 커뮤니티</button>
        <button type="button">💰 보유주식 평가금액</button>
        <button type="button">📒 거래내역</button>
        <span className="spacer" />
        <span className="chip">KR 장 —</span>
        <span className="chip">US 장 —</span>
        <span className="chip">자동화 꺼짐</span>
        {user !== null && (
          <div className="user-menu">
            <button
              type="button"
              aria-haspopup="menu"
              aria-expanded={menuOpen}
              onClick={() => setMenuOpen((v) => !v)}
            >
              👤 {user.displayName}
            </button>
            {menuOpen && (
              <ul role="menu">
                <li role="menuitem">자산 조회</li>
                <li role="menuitem">회원정보 변경</li>
                <li role="menuitem">알림 설정</li>
                {user.role === 'ADMIN' && <li role="menuitem">회원 관리 · 공유 키</li>}
                <li role="menuitem">
                  <button type="button" className="link" onClick={onLogout}>
                    로그아웃
                  </button>
                </li>
              </ul>
            )}
          </div>
        )}
      </div>
    </header>
  )
}
