import { useState } from 'react'
import type { ApiCredentialKind } from '@renderer/generated/api-credential-kind'
import type { ApiSetupState } from '@renderer/generated/api-setup-state'
import type { setupApi } from '@renderer/data/api/setup'
import { CredentialRow } from './CredentialRow'

interface TossStepProps {
  readonly api: ReturnType<typeof setupApi>
  readonly state: ApiSetupState
  readonly onDone: () => void
}

const TOSS_KIND: ApiCredentialKind = {
  kind: 'TOSS',
  group: 'BROKER',
  scope: 'USER',
  required: true,
  fields: ['CLIENT_ID', 'CLIENT_SECRET'],
  isSecret: true,
  isLlm: false,
}

/** ③ 토스증권 키 안내. 지금 등록(검증 뒤 "다음") 또는 나중에(조회 제한 모드). */
export function TossStep({ api, state, onDone }: TossStepProps) {
  const [registering, setRegistering] = useState(false)
  const [busy, setBusy] = useState(false)
  const verified = state.credentials.some((c) => c.kind === 'TOSS' && c.status === 'VERIFIED')

  async function decide(decision: 'REGISTERED' | 'LATER') {
    setBusy(true)
    try {
      await api.decideToss(decision)
      onDone()
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="step">
      <h2>토스증권 Open API 키</h2>
      <p className="lead">
        거래를 시작하려면 토스증권 Open API 인증키가 필요합니다. 시세·계좌·주문은 모두 이 키로 하며,
        키는 본인 계좌에만 쓰이고 admin을 포함해 누구도 값을 다시 볼 수 없습니다. admin의 키는 공용
        시세 수집에도 쓰이므로 건너뛰면 구성원에게도 시세가 없습니다.
      </p>
      {registering || verified ? (
        <>
          <CredentialRow
            kind={TOSS_KIND}
            meta={state.credentials.find((c) => c.kind === 'TOSS')}
            verify={(fields) => api.registerToss(fields)}
            onVerified={onDone}
          />
          <button
            type="button"
            className="primary"
            disabled={busy || !verified}
            onClick={() => void decide('REGISTERED')}
          >
            다음
          </button>
        </>
      ) : (
        <div className="actions">
          <button type="button" className="primary" onClick={() => setRegistering(true)}>
            지금 등록
          </button>
          <button type="button" disabled={busy} onClick={() => void decide('LATER')}>
            나중에
          </button>
          <p className="warn">토스 키가 없으면 시세·차트·계좌·주문이 모두 꺼진 채로 열립니다.</p>
        </div>
      )}
    </div>
  )
}
