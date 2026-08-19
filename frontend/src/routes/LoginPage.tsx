/** 로그인 화면. 성공 시 RequireAuth 가 state 로 넘긴 원래 목적지로 돌아간다. */
import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { useLogin } from '../features/auth/api'
import { emailError, passwordError } from '../features/auth/validation'
import { toDisplayMessage } from '../lib/api/errors'
import { AuthLayout } from '../components/ui'

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const login = useLogin()

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  // 검증 메시지는 제출 시도 후부터 보여준다
  const [submitted, setSubmitted] = useState(false)

  const errors = {
    email: emailError(email),
    password: passwordError(password),
  }

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    setSubmitted(true)
    if (errors.email || errors.password) return

    login.mutate(
      { email: email.trim(), password },
      {
        onSuccess: () => {
          const from = (location.state as { from?: string } | null)?.from ?? '/'
          void navigate(from, { replace: true })
        },
      },
    )
  }

  return (
    <AuthLayout
      title="로그인"
      subtitle="DevReport AI 작업 공간에 로그인하세요."
      footer={
        <>
          <p>
            계정이 없나요? <Link to="/signup">회원가입</Link>
          </p>
          <p className="policy-links">
            <Link to="/policies#privacy">개인정보처리방침</Link> ·{' '}
            <Link to="/policies#terms">이용약관</Link>
          </p>
        </>
      }
    >
      <form onSubmit={handleSubmit} noValidate>
        <AuthField
          id="email"
          label="이메일"
          type="email"
          value={email}
          onChange={setEmail}
          error={submitted ? errors.email : null}
        />
        <AuthField
          id="password"
          label="비밀번호"
          type="password"
          value={password}
          onChange={setPassword}
          error={submitted ? errors.password : null}
        />

        <button type="submit" disabled={login.isPending} className="primary-button">
          {login.isPending ? '로그인 중…' : '로그인'}
        </button>

        {login.error && (
          <p role="alert" className="field-error">
            {toDisplayMessage(login.error)}
          </p>
        )}
      </form>
    </AuthLayout>
  )
}

/** 로그인·회원가입 공용 입력칸. */
export function AuthField({
  id,
  label,
  type,
  value,
  onChange,
  error,
}: {
  id: string
  label: string
  type: 'email' | 'password' | 'text'
  value: string
  onChange: (value: string) => void
  error: string | null
}) {
  return (
    <div className="field-group">
      <label htmlFor={id} className="field-label">
        {label}
      </label>
      <input
        id={id}
        type={type}
        placeholder={
          type === 'email'
            ? 'name@example.com'
            : type === 'text'
              ? '이름을 입력해 주세요'
              : '비밀번호를 입력해 주세요'
        }
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-invalid={error !== null}
        className="field-control"
      />
      {error && (
        <p role="alert" className="field-error">
          {error}
        </p>
      )}
    </div>
  )
}
