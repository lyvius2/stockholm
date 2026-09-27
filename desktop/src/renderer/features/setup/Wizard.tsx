import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { ApiSetupState } from '@renderer/generated/api-setup-state'
import { localClient } from '@renderer/data/client/LocalClient'
import { setupApi } from '@renderer/data/api/setup'
import { AdminStep } from './AdminStep'
import { ConnectionCheckStep } from './ConnectionCheckStep'
import { SharedKeysStep } from './SharedKeysStep'
import { TossStep } from './TossStep'

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
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['setup', 'state'] })
  const current = stepIndex(state.state)

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
        {current === 0 && <AdminStep api={api} onDone={refresh} />}
        {current === 1 && (
          <SharedKeysStep api={api} state={state} catalog={catalog.data ?? []} onDone={refresh} />
        )}
        {current === 2 && <TossStep api={api} state={state} onDone={refresh} />}
        {current === 3 && <ConnectionCheckStep api={api} state={state} onDone={refresh} />}
      </section>
    </div>
  )
}
