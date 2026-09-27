import { useState, type FormEvent } from 'react'
import type { ApiTotpEnrollment } from '@renderer/generated/api-totp-enrollment'
import { ApiError } from '@renderer/data/client/Client'
import type { setupApi } from '@renderer/data/api/setup'
import { Field } from '@renderer/shared/ui/Field'

const MIN_PASSWORD = 12

interface AdminStepProps {
  readonly api: ReturnType<typeof setupApi>
  readonly onDone: () => void
}

/** ① 표시 이름·비밀번호 → TOTP 등록(QR + 6자리 확인). 비밀번호는 데몬에만 보내고 화면 상태에 남기지 않음. */
export function AdminStep({ api, onDone }: AdminStepProps) {
  const [displayName, setDisplayName] = useState('admin')
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [enrollment, setEnrollment] = useState<ApiTotpEnrollment | null>(null)
  const [showManualKey, setShowManualKey] = useState(false)
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const canSubmit =
    displayName.trim().length > 0 && password.length >= MIN_PASSWORD && password === confirmation

  async function createAdmin(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      setEnrollment(await api.createAdmin(displayName.trim(), password, confirmation))
      setPassword('')
      setConfirmation('')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '요청에 실패함')
    } finally {
      setBusy(false)
    }
  }

  async function confirmTotp(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.confirmAdminTotp(code)
      onDone()
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 401
          ? '코드가 맞지 않습니다. 앱의 다음 코드를 입력하세요.'
          : '요청에 실패함',
      )
    } finally {
      setBusy(false)
    }
  }

  if (enrollment !== null) {
    return (
      <form className="step" onSubmit={confirmTotp}>
        <h2>인증 앱 등록</h2>
        <p className="lead">
          Google Authenticator·1Password 등 인증 앱으로 QR을 찍고, 앱이 보여 주는 6자리를
          입력하세요.
        </p>
        <img
          className="qr"
          alt="TOTP QR"
          src={`data:image/png;base64,${enrollment.totpQrPngBase64}`}
        />
        <button type="button" className="link" onClick={() => setShowManualKey((v) => !v)}>
          {showManualKey ? '수동 키 숨기기' : '수동 키 보기'}
        </button>
        {showManualKey && <code className="manual-key">{enrollment.totpManualKey}</code>}
        <Field
          label="6자리 코드"
          inputMode="numeric"
          pattern="[0-9]{6}"
          autoComplete="one-time-code"
          value={code}
          onChange={(e) => setCode(e.target.value)}
        />
        {error !== null && <p className="error">{error}</p>}
        <button type="submit" className="primary" disabled={busy || code.length !== 6}>
          확인
        </button>
      </form>
    )
  }

  return (
    <form className="step" onSubmit={createAdmin}>
      <h2>admin 계정</h2>
      <p className="lead">
        이 계정이 이 설치의 admin이 됩니다. 구성원 초대·공유 키 관리는 admin만 할 수 있습니다.
      </p>
      <Field
        label="표시 이름"
        value={displayName}
        maxLength={20}
        onChange={(e) => setDisplayName(e.target.value)}
      />
      <Field
        label="비밀번호"
        type="password"
        autoComplete="new-password"
        value={password}
        onChange={(e) => setPassword(e.target.value)}
        hint={`${MIN_PASSWORD}자 이상`}
      />
      <Field
        label="비밀번호 확인"
        type="password"
        autoComplete="new-password"
        value={confirmation}
        onChange={(e) => setConfirmation(e.target.value)}
      />
      {error !== null && <p className="error">{error}</p>}
      <button type="submit" className="primary" disabled={busy || !canSubmit}>
        다음
      </button>
    </form>
  )
}
