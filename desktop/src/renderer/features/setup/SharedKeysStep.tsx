import { useState } from 'react'
import type { ApiCredentialKind } from '@renderer/generated/api-credential-kind'
import type { ApiSetupState } from '@renderer/generated/api-setup-state'
import type { LlmPreset, setupApi } from '@renderer/data/api/setup'
import { CredentialRow } from './CredentialRow'

const GROUP_LABELS: Record<string, string> = {
  LLM: 'LLM (1개 이상 필수)',
  DISCLOSURE: '공시',
  MARKET_DATA: '시장 데이터',
  NEWS: '뉴스·커뮤니티',
  PUBLIC_DATA: '공공',
  NOTIFICATION: '알림',
  KFTC: '금융결제원',
  CACHE: '캐시 서버',
}

const PRESETS: ReadonlyArray<{ readonly value: LlmPreset; readonly label: string }> = [
  { value: 'BALANCED', label: '균형' },
  { value: 'QUALITY', label: '품질 우선' },
  { value: 'COST', label: '비용 최소' },
  { value: 'LOCAL', label: '로컬 전용' },
]

interface SharedKeysStepProps {
  readonly api: ReturnType<typeof setupApi>
  readonly state: ApiSetupState
  readonly catalog: readonly ApiCredentialKind[]
  readonly catalogError: string | null
  readonly onDone: () => void
}

/** ② 그룹별 키 목록. "다음"은 LLM 1개 이상 + DART 가 검증됐을 때만 활성. */
export function SharedKeysStep({ api, state, catalog, catalogError, onDone }: SharedKeysStepProps) {
  const [preset, setPreset] = useState<LlmPreset>('BALANCED')
  const [busy, setBusy] = useState(false)
  const shared = catalog.filter((kind) => kind.scope === 'SHARED')
  const groups = [...new Set(shared.map((kind) => kind.group))]

  async function finish() {
    setBusy(true)
    try {
      await api.finishSharedKeys(preset)
      onDone()
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="step">
      <h2>공유 API 키</h2>
      <p className="lead">
        각 키는 "검증"을 눌러야 데몬이 확인하고, 성공한 값만 저장됩니다. 저장된 값은 누구도 다시 볼
        수 없습니다.
      </p>
      {catalogError !== null && (
        <p className="error">키 종류 목록을 받지 못했습니다: {catalogError}</p>
      )}
      {groups.map((group) => (
        <section key={group} className="key-group">
          <h3>{GROUP_LABELS[group] ?? group}</h3>
          {shared
            .filter((kind) => kind.group === group)
            .map((kind) => (
              <CredentialRow
                key={kind.kind}
                kind={kind}
                meta={state.credentials.find((c) => c.kind === kind.kind)}
                verify={(fields) => api.registerSharedKey(kind.kind, fields)}
                onVerified={onDone}
              />
            ))}
        </section>
      ))}
      {state.credentials.some((c) => c.status === 'VERIFIED' && LLM_KINDS.has(c.kind)) && (
        <label className="field">
          <span className="field-label">LLM 프리셋</span>
          <select value={preset} onChange={(e) => setPreset(e.target.value as LlmPreset)}>
            {PRESETS.map((p) => (
              <option key={p.value} value={p.value}>
                {p.label}
              </option>
            ))}
          </select>
        </label>
      )}
      <button
        type="button"
        className="primary"
        disabled={busy || !state.canFinishSharedKeys}
        onClick={() => void finish()}
      >
        다음
      </button>
    </div>
  )
}

const LLM_KINDS: ReadonlySet<string> = new Set(['OPENAI', 'ANTHROPIC', 'DEEPSEEK', 'OLLAMA'])
