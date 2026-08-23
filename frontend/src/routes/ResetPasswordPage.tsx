import { useState } from 'react'
import { Link } from 'react-router'
import { AuthField, AuthLayout } from '../components/ui'
import { useResetPassword } from '../features/auth/api'
import { passwordError } from '../features/auth/validation'
import { toDisplayMessage } from '../lib/api/errors'

function readToken(): string {
  const token = new URLSearchParams(window.location.hash.slice(1)).get('token') ?? ''
  if (token)
    window.history.replaceState(
      window.history.state,
      '',
      window.location.pathname + window.location.search,
    )
  return token
}

export function ResetPasswordPage() {
  const resetPassword = useResetPassword()
  const [token] = useState(readToken)
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [submitted, setSubmitted] = useState(false)
  const errors = {
    password: passwordError(password),
    confirmation:
      confirmation === ''
        ? '새 비밀번호를 한 번 더 입력해 주세요.'
        : confirmation !== password
          ? '새 비밀번호가 일치하지 않습니다.'
          : null,
  }

  if (!token) {
    return (
      <AuthLayout
        title="유효하지 않은 링크입니다"
        subtitle="재설정 링크가 없거나 올바르지 않습니다."
        footer={
          <p>
            <Link to="/login">로그인으로 돌아가기</Link>
          </p>
        }
      >
        <Link to="/forgot-password" className="primary-button">
          새 링크 요청하기
        </Link>
      </AuthLayout>
    )
  }

  if (resetPassword.isSuccess) {
    return (
      <AuthLayout
        title="비밀번호가 변경되었습니다"
        subtitle="새 비밀번호로 다시 로그인해 주세요."
        footer={<p>기존 로그인 세션은 모두 종료되었습니다.</p>}
      >
        <Link to="/login" className="primary-button">
          로그인하기
        </Link>
      </AuthLayout>
    )
  }

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    setSubmitted(true)
    if (errors.password || errors.confirmation) return
    resetPassword.mutate({ token, newPassword: password })
  }

  return (
    <AuthLayout
      title="새 비밀번호 설정"
      subtitle="8자 이상, UTF-8 기준 72바이트 이하로 입력해 주세요."
      footer={
        <p>
          <Link to="/forgot-password">새 링크 요청하기</Link>
        </p>
      }
    >
      <form onSubmit={handleSubmit} noValidate>
        <AuthField
          id="new-password"
          label="새 비밀번호"
          type="password"
          value={password}
          onChange={setPassword}
          error={submitted ? errors.password : null}
        />
        <AuthField
          id="new-password-confirmation"
          label="새 비밀번호 확인"
          type="password"
          value={confirmation}
          onChange={setConfirmation}
          error={submitted ? errors.confirmation : null}
        />
        <button type="submit" disabled={resetPassword.isPending} className="primary-button">
          {resetPassword.isPending ? '변경 중…' : '비밀번호 재설정'}
        </button>
        {resetPassword.error && (
          <p role="alert" className="field-error">
            {toDisplayMessage(resetPassword.error)}
          </p>
        )}
      </form>
    </AuthLayout>
  )
}
