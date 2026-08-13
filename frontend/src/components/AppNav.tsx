/** 상단 네비게이션 — 서비스명 | 화면명, 우측에 사용자·로그아웃. */
import { Link } from 'react-router'
import { useLogout, useMe } from '../features/auth/api'

export function AppNav({ screen }: { screen?: string }) {
  const me = useMe()
  const logout = useLogout()

  return (
    <nav className="border-b border-gray-200 bg-white">
      <div className="mx-auto flex max-w-3xl items-center gap-3 px-6 py-3">
        <Link to="/" className="text-lg font-extrabold tracking-tight">
          DevReport <span className="text-[#ea002c]">AI</span>
        </Link>
        {screen && (
          <>
            <span aria-hidden className="h-4 w-px bg-gray-300" />
            <span className="text-sm text-gray-600">{screen}</span>
          </>
        )}
        <div className="ml-auto flex items-center gap-3 text-sm text-gray-600">
          {me.data && <span>{me.data.name}</span>}
          <button
            type="button"
            onClick={() => logout.mutate()}
            disabled={logout.isPending}
            className="rounded border border-gray-300 px-3 py-1 hover:border-gray-900 disabled:opacity-40"
          >
            로그아웃
          </button>
        </div>
      </div>
    </nav>
  )
}
