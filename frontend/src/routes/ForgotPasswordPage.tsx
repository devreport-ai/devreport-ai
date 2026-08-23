import { useState } from 'react'
import { Link } from 'react-router'
import { AuthField, AuthLayout } from '../components/ui'
import { useRequestPasswordReset } from '../features/auth/api'
import { emailError } from '../features/auth/validation'
import { toDisplayMessage } from '../lib/api/errors'

export function ForgotPasswordPage() {
  const requestReset = useRequestPasswordReset()
  const [email, setEmail] = useState('')
  const [submitted, setSubmitted] = useState(false)
  const error = emailError(email)

  if (requestReset.isSuccess) {
    return (
      <AuthLayout
        title="이메일을 확인해 주세요"
        subtitle={requestReset.data.message}
        footer={
          <p>
            <Link to="/login">로그인으로 돌아가기</Link>
          </p>
        }
      >
        <p className="auth-notice" role="status">
          메일이 보이지 않으면 스팸함을 확인하거나 잠시 후 다시 요청해 주세요.
        </p>
      </AuthLayout>
    )
  }

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    setSubmitted(true)
    if (error) return
    requestReset.mutate({ email: email.trim() })
  }

  return (
    <AuthLayout
      title="비밀번호 찾기"
      subtitle="가입한 이메일로 일회용 재설정 링크를 보내드립니다."
      footer={
        <p>
          <Link to="/login">로그인으로 돌아가기</Link>
        </p>
      }
    >
      <form onSubmit={handleSubmit} noValidate>
        <AuthField
          id="reset-email"
          label="이메일"
          type="email"
          value={email}
          onChange={setEmail}
          error={submitted ? error : null}
        />
        <button type="submit" disabled={requestReset.isPending} className="primary-button">
          {requestReset.isPending ? '전송 중…' : '재설정 링크 받기'}
        </button>
        {requestReset.error && (
          <p role="alert" className="field-error">
            {toDisplayMessage(requestReset.error)}
          </p>
        )}
      </form>
    </AuthLayout>
  )
}
