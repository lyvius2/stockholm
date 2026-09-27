import { useState, type FormEvent } from 'react'
import { ApiError } from '@renderer/data/client/Client'
import type { setupApi } from '@renderer/data/api/setup'
import { Field } from '@renderer/shared/ui/Field'

interface WizardUnlockProps {
  readonly api: ReturnType<typeof setupApi>
  readonly adminDisplayName: string
  readonly onUnlocked: () => void
}

/** 앱을 껐다 켜서 마법사 세션이 없을 때 admin 비밀번호와 TOTP 로 다시 여는 카드. */
export function WizardUnlock({ api, adminDisplayName, onUnlocked }: WizardUnlockProps) {
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.reopenSession(password, code)
      setPassword('')
      setCode('')
      onUnlocked()
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 401
          ? '비밀번호 또는 코드가 맞지 않습니다. 인증 앱의 다음 코드를 기다려 보세요.'
          : '요청에 실패함',
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="step" onSubmit={submit}>
      <h2>마법사 계속</h2>
      <p className="lead">
        설정을 이어가려면 {adminDisplayName} 계정의 비밀번호와 인증 앱 코드를 다시 확인합니다.
      </p>
      <div className="form-narrow">
        <Field
          label="비밀번호"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <Field
          label="TOTP 6자리"
          inputMode="numeric"
          autoComplete="one-time-code"
          value={code}
          onChange={(e) => setCode(e.target.value)}
        />
        {error !== null && <p className="error">{error}</p>}
        <button
          type="submit"
          className="primary"
          disabled={busy || password.length === 0 || code.length !== 6}
        >
          계속
        </button>
      </div>
    </form>
  )
}
