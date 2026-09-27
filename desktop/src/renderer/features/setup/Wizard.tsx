import { useEffect } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { ApiSetupState } from '@renderer/generated/api-setup-state'
import { UNAUTHORIZED_EVENT, localClient } from '@renderer/data/client/LocalClient'
import { setupApi } from '@renderer/data/api/setup'
import { AdminStep } from './AdminStep'
import { ConnectionCheckStep } from './ConnectionCheckStep'
import { SharedKeysStep } from './SharedKeysStep'
import { TossStep } from './TossStep'
import { WizardUnlock } from './WizardUnlock'

const STEPS = ['admin 비밀번호', '공유 API 키', '토스증권 키', '연결 확인'] as const

function stepIndex(state: ApiSetupState['state']): number {
  switch (state) {
    case 'NOT_STARTED':
      return 0
    case 'ADMIN_CREATED':
      return 1
    case 'SHARED_KEYS_DONE':
      return 2
    case 'TOSS_DECIDED':
    case 'COMPLETE':
      return 3
  }
}

/** 최초 구동 마법사. 어느 단계를 띄울지는 데몬의 SetupState 만 보고 정함. */
export function Wizard({ state }: { readonly state: ApiSetupState }) {
  const api = setupApi(localClient)
  const queryClient = useQueryClient()
  const catalog = useQuery({ queryKey: ['setup', 'catalog'], queryFn: api.catalog })
  // ① 뒤 단계는 마법사 세션이 있어야 함. 앱을 껐다 켜면 main 의 토큰이 사라지므로 다시 연다
  const session = useQuery({
    queryKey: ['session', 'has'],
    queryFn: () => window.stockholm.session.hasSession(),
  })
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['setup', 'state'] })
    void queryClient.invalidateQueries({ queryKey: ['session', 'has'] })
  }
  // 마법사 세션(1시간)이 만료되면 main 이 토큰을 잊고 데몬은 401 을 냄 → 세션 유무를 다시 물어 잠금 카드로 돌아감
  useEffect(() => {
    const onUnauthorized = () =>
      void queryClient.invalidateQueries({ queryKey: ['session', 'has'] })
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
  }, [queryClient])
  const current = stepIndex(state.state)
  const needsUnlock = current >= 1 && session.data === false

  return (
    <div className="wizard" data-step={current}>
      <aside className="wizard-steps">
        <h1 className="brand">Stockholm</h1>
        <ol>
          {STEPS.map((label, index) => (
            <li
              key={label}
              data-state={index < current ? 'done' : index === current ? 'current' : 'todo'}
            >
              <span className="step-no">{index + 1}</span>
              {label}
            </li>
          ))}
        </ol>
      </aside>
      <section className="wizard-card">
        {needsUnlock && (
          <WizardUnlock
            api={api}
            adminDisplayName={state.adminDisplayName ?? 'admin'}
            onUnlocked={refresh}
          />
        )}
        {current === 0 && <AdminStep api={api} onDone={refresh} />}
        {!needsUnlock && current === 1 && (
          <SharedKeysStep
            api={api}
            state={state}
            catalog={catalog.data ?? []}
            catalogError={catalog.error instanceof Error ? catalog.error.message : null}
            onDone={refresh}
          />
        )}
        {!needsUnlock && current === 2 && <TossStep api={api} state={state} onDone={refresh} />}
        {!needsUnlock && current === 3 && (
          <ConnectionCheckStep api={api} state={state} onDone={refresh} />
        )}
      </section>
    </div>
  )
}
