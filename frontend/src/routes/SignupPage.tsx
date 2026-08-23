/** 회원가입 화면. 가입 성공 시 자동 로그인 후 첫 화면으로 간다. */
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { useSignup } from '../features/auth/api'
import { POLICY_VERSIONS } from '../features/auth/policyVersions'
import { emailError, nameError, passwordError } from '../features/auth/validation'
import { toDisplayMessage } from '../lib/api/errors'
import { AuthField, AuthLayout } from '../components/ui'

export function SignupPage() {
  const navigate = useNavigate()
  const signup = useSignup()

  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [policyAgreed, setPolicyAgreed] = useState(false)
  const [submitted, setSubmitted] = useState(false)

  const errors = {
    name: nameError(name),
    email: emailError(email),
    password: passwordError(password),
  }

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    setSubmitted(true)
    if (errors.name || errors.email || errors.password || !policyAgreed) return

    signup.mutate(
      {
        name: name.trim(),
        email: email.trim(),
        password,
        privacyPolicyVersion: POLICY_VERSIONS.privacyPolicy,
        termsOfServiceVersion: POLICY_VERSIONS.termsOfService,
      },
      { onSuccess: () => void navigate('/', { replace: true }) },
    )
  }

  return (
    <AuthLayout
      title="회원가입"
      subtitle="새로운 DevReport AI 작업 공간을 시작하세요."
      footer={
        <>
          <p>
            이미 계정이 있나요? <Link to="/login">로그인</Link>
          </p>
          <p className="policy-links">
            가입하면 <Link to="/policies#privacy">개인정보처리방침</Link> 및{' '}
            <Link to="/policies#terms">이용약관</Link>을 확인한 것으로 안내됩니다.
          </p>
        </>
      }
    >
      <form onSubmit={handleSubmit} noValidate>
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

        <label className="policy-agreement">
          <input
            type="checkbox"
            required
            checked={policyAgreed}
            onChange={(event) => setPolicyAgreed(event.target.checked)}
            aria-invalid={submitted && !policyAgreed}
            aria-describedby={submitted && !policyAgreed ? 'policy-agreement-error' : undefined}
            className=""
          />
          <span>
            <Link to="/policies#privacy" className="underline">
              개인정보처리방침
            </Link>
            과{' '}
            <Link to="/policies#terms" className="underline">
              이용약관
            </Link>
            을 확인하고 동의합니다.
          </span>
        </label>
        {submitted && !policyAgreed && (
          <p id="policy-agreement-error" role="alert" className="field-error">
            개인정보처리방침과 이용약관에 동의해야 합니다.
          </p>
        )}

        <button type="submit" disabled={signup.isPending} className="primary-button">
          {signup.isPending ? '가입 중…' : '가입하기'}
        </button>

        {signup.error && (
          <p role="alert" className="field-error">
            {toDisplayMessage(signup.error)}
          </p>
        )}
      </form>
    </AuthLayout>
  )
}
