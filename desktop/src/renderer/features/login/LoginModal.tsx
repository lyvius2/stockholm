import { useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import type { ApiUser } from '@renderer/generated/api-user'
import { ApiError } from '@renderer/data/client/Client'
import { localClient } from '@renderer/data/client/LocalClient'
import { sessionApi } from '@renderer/data/api/session'
import { useSessionStore } from '@renderer/data/store/session'
import { Field } from '@renderer/shared/ui/Field'

/**
 * 로그인 모달. 모든 모달 중 유일하게 바깥을 흐림(연출이지 보안 수단이 아님).
 * 사용자 선택 → 비밀번호 → TOTP 6자리(또는 복구 코드). Esc 로 닫히지 않음.
 */
export function LoginModal() {
  const api = sessionApi(localClient)
  const signIn = useSessionStore((s) => s.signIn)
  const users = useQuery({ queryKey: ['session', 'users'], queryFn: api.users })
  const [userId, setUserId] = useState<string | null>(null)
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [useRecovery, setUseRecovery] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const selected: ApiUser | undefined =
    users.data?.find((u) => u.userId === userId) ?? users.data?.[0]
  const canSubmit =
    selected !== undefined &&
    password.length > 0 &&
    (useRecovery ? code.length > 0 : code.length === 6)

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (selected === undefined) return
    setBusy(true)
    setError(null)
    try {
      const result = await api.login({
        userId: selected.userId,
        password,
        ...(useRecovery ? { recoveryCode: code } : { totpCode: code }),
      })
      setPassword('')
      setCode('')
      signIn(result.user)
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 401
          ? '로그인할 수 없습니다. 비밀번호와 코드를 확인하세요. 마법사 직후라면 앱의 다음 코드를 기다리세요.'
          : '데몬에 연결할 수 없습니다.',
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-backdrop" role="presentation">
      <form
        className="modal login-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="login-title"
        onSubmit={submit}
      >
        <h2 id="login-title">로그인</h2>
        <div className="user-chips" role="radiogroup" aria-label="사용자">
          {(users.data ?? []).map((u) => (
            <button
              type="button"
              key={u.userId}
              role="radio"
              aria-checked={selected?.userId === u.userId}
              className={selected?.userId === u.userId ? 'chip selected' : 'chip'}
              onClick={() => setUserId(u.userId)}
            >
              {u.displayName}
            </button>
          ))}
        </div>
        <Field
          label="비밀번호"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <Field
          label={useRecovery ? '복구 코드' : 'TOTP 6자리'}
          inputMode={useRecovery ? 'text' : 'numeric'}
          autoComplete="one-time-code"
          value={code}
          onChange={(e) => setCode(e.target.value)}
        />
        {error !== null && <p className="error">{error}</p>}
        <button type="submit" className="primary" disabled={busy || !canSubmit}>
          로그인
        </button>
        <button type="button" className="link" onClick={() => setUseRecovery((v) => !v)}>
          {useRecovery ? 'TOTP로 로그인' : '복구 코드로 로그인'}
        </button>
      </form>
    </div>
  )
}
