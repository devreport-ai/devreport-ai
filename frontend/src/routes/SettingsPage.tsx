import { useState } from 'react'
import { useNavigate } from 'react-router'
import { AppNav } from '../components/AppNav'
import { AppTopBar, AuthField, PageHeader, SurfaceCard } from '../components/ui'
import { useChangePassword } from '../features/auth/api'
import { passwordError } from '../features/auth/validation'
import { toDisplayMessage } from '../lib/api/errors'

export default function SettingsPage() {
  const navigate = useNavigate()
  const changePassword = useChangePassword()
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [submitted, setSubmitted] = useState(false)

  const errors = {
    current: passwordError(currentPassword),
    next:
      passwordError(newPassword) ??
      (newPassword !== '' && newPassword === currentPassword
        ? '현재 비밀번호와 다른 비밀번호를 입력해 주세요.'
        : null),
    confirmation:
      confirmation === ''
        ? '새 비밀번호를 한 번 더 입력해 주세요.'
        : confirmation !== newPassword
          ? '새 비밀번호가 일치하지 않습니다.'
          : null,
  }

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    setSubmitted(true)
    if (errors.current || errors.next || errors.confirmation) return

    changePassword.mutate(
      { currentPassword, newPassword },
      { onSuccess: () => void navigate('/login', { replace: true }) },
    )
  }

  return (
    <div className="app-page">
      <AppNav screen="계정 설정" />
      <AppTopBar current="계정 설정" />
      <main className="app-content">
        <PageHeader
          eyebrow="ACCOUNT"
          title="계정 설정"
          description="비밀번호를 변경하고 계정 보안을 관리합니다."
        />

        <SurfaceCard className="settings-card">
          <header className="detail-card__header">
            <div>
              <h2>비밀번호 변경</h2>
              <p className="settings-card__description">
                변경하면 다른 기기의 Refresh Token도 폐기되어 다시 로그인해야 합니다.
              </p>
            </div>
          </header>

          <form onSubmit={handleSubmit} className="settings-form" noValidate>
            <AuthField
              id="current-password"
              label="현재 비밀번호"
              type="password"
              value={currentPassword}
              onChange={setCurrentPassword}
              error={submitted ? errors.current : null}
            />
            <AuthField
              id="new-password"
              label="새 비밀번호"
              type="password"
              value={newPassword}
              onChange={setNewPassword}
              error={submitted ? errors.next : null}
            />
            <AuthField
              id="new-password-confirmation"
              label="새 비밀번호 확인"
              type="password"
              value={confirmation}
              onChange={setConfirmation}
              error={submitted ? errors.confirmation : null}
            />

            {changePassword.error && (
              <p role="alert" className="inline-alert">
                {toDisplayMessage(changePassword.error)}
              </p>
            )}

            <button type="submit" disabled={changePassword.isPending} className="primary-button">
              {changePassword.isPending ? '변경 중…' : '비밀번호 변경'}
            </button>
          </form>
        </SurfaceCard>
      </main>
    </div>
  )
}
