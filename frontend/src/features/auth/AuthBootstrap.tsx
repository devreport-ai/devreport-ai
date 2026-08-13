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

  if (!checked) return null // 판정은 한 번의 왕복이라 짧다. 빈 화면이 깜빡임보다 낫다
  return children
}
