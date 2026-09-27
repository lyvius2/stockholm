import { useState } from 'react'
import type { ApiCredentialCheck } from '@renderer/generated/api-credential-check'
import type { ApiCredentialKind } from '@renderer/generated/api-credential-kind'
import type { ApiSetupState } from '@renderer/generated/api-setup-state'
import { ApiError } from '@renderer/data/client/Client'

const KIND_LABELS: Record<string, string> = {
  OPENAI: 'OpenAI 키',
  ANTHROPIC: 'Claude 키',
  DEEPSEEK: 'DeepSeek 키',
  OLLAMA: 'Ollama 주소',
  DART: 'DART 인증키',
  KRX: 'KRX Open API 인증키',
  MASSIVE: 'Massive 키',
  NAVER: '네이버 검색',
  ODCLOUD: '공공데이터포털 서비스 키',
  SEC_CONTACT_EMAIL: 'SEC EDGAR 연락처 이메일',
  FRED: 'FRED API 키',
  SLACK: 'Slack 봇 토큰',
  KFTC_APP: '금융결제원 앱 자격',
  CACHE_SERVER: 'Valkey/Redis 주소',
  TOSS: '토스증권 Open API',
}

const FIELD_LABELS: Record<string, string> = {
  VALUE: '값',
  CLIENT_ID: 'client id',
  CLIENT_SECRET: 'client secret',
}

type Credential = ApiSetupState['credentials'][number]

interface CredentialRowProps {
  readonly kind: ApiCredentialKind
  readonly meta: Credential | undefined
  readonly verify: (fields: Record<string, string>) => Promise<ApiCredentialCheck>
  readonly onVerified: () => void
}

/** 키 한 줄: 이름 · 필수 칩 · 입력(마스킹) · 검증 · 상태 칩. 성공하면 입력 칸을 비움. */
export function CredentialRow({ kind, meta, verify, onVerified }: CredentialRowProps) {
  const [fields, setFields] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const filled = kind.fields.every((field) => (fields[field] ?? '').trim().length > 0)

  async function run() {
    setBusy(true)
    setFailure(null)
    try {
      const check = await verify(fields)
      if (check.result === 'OK') {
        setFields({})
        onVerified()
      } else {
        setFailure(check.reason ?? '검증 실패')
      }
    } catch (e) {
      setFailure(e instanceof ApiError ? e.message : '요청에 실패함')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="key-row" data-status={meta?.status ?? 'UNREGISTERED'}>
      <div className="key-name">
        {KIND_LABELS[kind.kind] ?? kind.kind}
        <span className={kind.required ? 'chip required' : 'chip'}>
          {kind.required ? '필수' : '선택'}
        </span>
      </div>
      <div className="key-inputs">
        {kind.fields.map((field) => (
          <input
            key={field}
            type={kind.isSecret ? 'password' : 'text'}
            placeholder={FIELD_LABELS[field] ?? field}
            autoComplete="off"
            value={fields[field] ?? ''}
            onChange={(e) => setFields((prev) => ({ ...prev, [field]: e.target.value }))}
          />
        ))}
      </div>
      <button type="button" disabled={busy || !filled} onClick={() => void run()}>
        검증
      </button>
      <span className="status-chip">{statusLabel(meta, failure)}</span>
    </div>
  )
}

function statusLabel(meta: Credential | undefined, failure: string | null): string {
  if (failure !== null) return `실패: ${failure}`
  if (meta === undefined) return '미등록'
  switch (meta.status) {
    case 'VERIFIED':
      return meta.last4 !== null && meta.last4 !== undefined ? `검증됨 ···${meta.last4}` : '검증됨'
    case 'REJECTED':
      return `실패: ${meta.statusDetail ?? '거부됨'}`
    case 'UNREACHABLE':
      return `연결 안 됨: ${meta.statusDetail ?? ''}`
    case 'UNREGISTERED':
      return '미등록'
  }
}
