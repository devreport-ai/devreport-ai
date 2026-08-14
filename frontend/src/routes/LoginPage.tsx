/** 로그인 화면. 성공 시 RequireAuth 가 state 로 넘긴 원래 목적지로 돌아간다. */
import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { useLogin } from '../features/auth/api'
import { emailError, passwordError } from '../features/auth/validation'
import { toDisplayMessage } from '../lib/api/errors'

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
    <main className="flex min-h-screen items-center justify-center bg-gray-50 p-6">
      <div className="w-full max-w-sm space-y-6 rounded-lg border border-gray-200 bg-white p-8">
        <div>
          <p className="text-xs font-bold tracking-widest text-[#ea002c]">DEVREPORT AI</p>
          <h1 className="mt-1 text-2xl font-bold">로그인</h1>
        </div>

        <form onSubmit={handleSubmit} noValidate className="space-y-4">
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

          <button
            type="submit"
            disabled={login.isPending}
            className="w-full rounded bg-gray-900 px-4 py-2 text-white disabled:opacity-40"
          >
            {login.isPending ? '로그인 중…' : '로그인'}
          </button>

          {login.error && (
            <p role="alert" className="text-sm text-red-600">
              {toDisplayMessage(login.error)}
            </p>
          )}
        </form>

        <p className="text-sm text-gray-600">
          계정이 없나요?{' '}
          <Link to="/signup" className="underline">
            회원가입
          </Link>
        </p>
        <p className="text-xs text-gray-500">
          <Link to="/policies#privacy" className="underline">
            개인정보처리방침
          </Link>{' '}
          ·{' '}
          <Link to="/policies#terms" className="underline">
            이용약관
          </Link>
        </p>
      </div>
    </main>
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
    <div>
      <label htmlFor={id} className="block text-sm font-medium">
        {label}
      </label>
      <input
        id={id}
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-invalid={error !== null}
        className="mt-1 w-full rounded border border-gray-300 px-3 py-2"
      />
      {error && (
        <p role="alert" className="mt-1 text-sm text-red-600">
          {error}
        </p>
      )}
    </div>
  )
}
