/** 회원가입 화면. 가입 성공 시 자동 로그인 후 첫 화면으로 간다. */
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { useSignup } from '../features/auth/api'
import { emailError, nameError, passwordError } from '../features/auth/validation'
import { toDisplayMessage } from '../lib/api/errors'
import { AuthField } from './LoginPage'

export function SignupPage() {
  const navigate = useNavigate()
  const signup = useSignup()

  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [submitted, setSubmitted] = useState(false)

  const errors = {
    name: nameError(name),
    email: emailError(email),
    password: passwordError(password),
  }

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    setSubmitted(true)
    if (errors.name || errors.email || errors.password) return

    signup.mutate(
      { name: name.trim(), email: email.trim(), password },
      { onSuccess: () => void navigate('/', { replace: true }) },
    )
  }

  return (
    <main className="mx-auto max-w-sm space-y-6 p-6">
      <h1 className="text-2xl font-bold">회원가입</h1>

      <form onSubmit={handleSubmit} noValidate className="space-y-4">
        <AuthField
          id="name"
          label="이름"
          type="text"
          value={name}
          onChange={setName}
          error={submitted ? errors.name : null}
        />
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
          disabled={signup.isPending}
          className="w-full rounded bg-gray-900 px-4 py-2 text-white disabled:opacity-40"
        >
          {signup.isPending ? '가입 중…' : '가입하기'}
        </button>

        {signup.error && (
          <p role="alert" className="text-sm text-red-600">
            {toDisplayMessage(signup.error)}
          </p>
        )}
      </form>

      <p className="text-sm text-gray-600">
        이미 계정이 있나요?{' '}
        <Link to="/login" className="underline">
          로그인
        </Link>
      </p>
    </main>
  )
}
