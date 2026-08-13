/**
 * 새로고침 복원 (#61 완료 조건) — 앱을 그리기 전에 쿠키의 Refresh Token 으로
 * Access Token 을 한 번 받아본다. 있으면 로그인 유지, 없으면 로그인 화면으로.
 * 이 확인 없이 그리면 로그인돼 있던 사용자도 새로고침마다 로그인 화면이 스친다.
 */
import { useEffect, useState, type ReactNode } from 'react'
import { refreshSession } from '../../lib/auth/session'

export function AuthBootstrap({ children }: { children: ReactNode }) {
  const [checked, setChecked] = useState(false)

  useEffect(() => {
    let mounted = true
    void refreshSession().finally(() => {
      if (mounted) setChecked(true)
    })
    return () => {
      mounted = false
    }
  }, [])

  if (!checked) {
    // 재발급이 타임아웃(10초)까지 걸릴 수 있어 빈 화면 대신 상태를 알린다
    return (
      <main role="status" aria-live="polite" className="p-6 text-gray-500">
        로그인 상태를 확인하는 중…
      </main>
    )
  }
  return children
}
