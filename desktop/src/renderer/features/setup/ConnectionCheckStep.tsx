import { useState } from 'react'
import type { ApiSetupState } from '@renderer/generated/api-setup-state'
import type { setupApi } from '@renderer/data/api/setup'

interface ConnectionCheckStepProps {
  readonly api: ReturnType<typeof setupApi>
  readonly state: ApiSetupState
  readonly onDone: () => void
}

/** ④ 등록한 키의 상태 표. 필수 항목이 정상일 때 "Stockholm 시작". */
export function ConnectionCheckStep({ api, state, onDone }: ConnectionCheckStepProps) {
  const [busy, setBusy] = useState(false)
  const requiredFailed = state.credentials.some((c) => c.required && c.status !== 'VERIFIED')

  async function start() {
    setBusy(true)
    try {
      await api.complete()
      onDone()
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="step">
      <h2>연결 확인</h2>
      <table className="check-table">
        <thead>
          <tr>
            <th>항목</th>
            <th>상태</th>
            <th>확인 시각</th>
          </tr>
        </thead>
        <tbody>
          {state.credentials.map((c) => (
            <tr key={`${c.kind}-${c.scope}`} data-status={c.status}>
              <td>{c.kind}</td>
              <td>{c.status === 'VERIFIED' ? '정상' : (c.statusDetail ?? c.status)}</td>
              <td>{c.verifiedAt ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {state.tossDecision === 'LATER' && (
        <p className="warn">토스 키 없이 시작합니다(조회 제한 모드).</p>
      )}
      <button
        type="button"
        className="primary"
        disabled={busy || requiredFailed}
        onClick={() => void start()}
      >
        Stockholm 시작
      </button>
    </div>
  )
}
