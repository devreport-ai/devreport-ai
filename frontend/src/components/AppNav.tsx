/** 로그인 이후 공통 앱 Shell — 데스크톱 Sidebar, 작은 화면 상단 바. */
import { Link, NavLink } from 'react-router'
import { useLogout, useMe } from '../features/auth/api'
import { BrandMark, Icon } from './ui'

export function AppNav({ screen }: { screen?: string }) {
  const me = useMe()
  const logout = useLogout()

  return (
    <>
      <aside className="app-sidebar">
        <Link to="/" className="app-sidebar__brand" aria-label="DevReport AI 사이드바">
          <BrandMark compact />
        </Link>

        <nav aria-label="주요 메뉴" className="app-sidebar__menu">
          <NavLink to="/" end className="app-nav-link">
            <Icon name="folder" />
            프로젝트
          </NavLink>
          <span className="app-nav-link app-nav-link--muted" aria-disabled="true">
            <Icon name="file-text" />
            보고서
          </span>
          <NavLink to="/api-keys" className="app-nav-link">
            <Icon name="key" />
            API 키 추가
          </NavLink>
        </nav>

        <div className="app-sidebar__bottom">
          <div className="user-chip">
            <span className="user-chip__avatar" aria-hidden>
              {me.data?.name?.slice(0, 1) ?? 'U'}
            </span>
            <span className="user-chip__name">{me.data?.name ?? '사용자'}</span>
          </div>
          <button
            type="button"
            onClick={() => logout.mutate()}
            disabled={logout.isPending}
            className="app-logout"
            aria-label="로그아웃 사이드바"
          >
            로그아웃
          </button>
        </div>
      </aside>

      <header className="app-mobile-header">
        <Link to="/" aria-label="DevReport AI">
          <BrandMark compact />
        </Link>
        <div className="app-mobile-header__meta">
          {screen && <span>{screen}</span>}
          <button
            type="button"
            onClick={() => logout.mutate()}
            disabled={logout.isPending}
            className="app-logout"
          >
            로그아웃
          </button>
        </div>
      </header>
    </>
  )
}
