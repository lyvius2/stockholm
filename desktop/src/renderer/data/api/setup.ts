import type { ApiCredentialCheck } from '@renderer/generated/api-credential-check'
import type { ApiCredentialKind } from '@renderer/generated/api-credential-kind'
import type { ApiSetupState } from '@renderer/generated/api-setup-state'
import type { ApiTotpEnrollment } from '@renderer/generated/api-totp-enrollment'
import type { Client } from '../client/Client'

export type LlmPreset = 'BALANCED' | 'QUALITY' | 'COST' | 'LOCAL'
export type TossDecision = 'REGISTERED' | 'LATER'

/** 최초 구동 마법사 API. 값(키·비밀번호)은 요청에만 있고 응답에는 없음. */
export function setupApi(client: Client) {
  return {
    state: () => client.request<ApiSetupState>('GET', '/setup/state'),
    catalog: () => client.request<ApiCredentialKind[]>('GET', '/setup/catalog'),
    createAdmin: (displayName: string, password: string, passwordConfirmation: string) =>
      client.request<ApiTotpEnrollment>('POST', '/setup/admin', {
        displayName,
        password,
        passwordConfirmation,
      }),
    reopenSession: (password: string, totpCode: string) =>
      client.request<{ progress: ApiSetupState }>('POST', '/setup/session', { password, totpCode }),
    confirmAdminTotp: (code: string) =>
      client.request<{ progress: ApiSetupState }>('POST', '/setup/admin/totp', { code }),
    registerSharedKey: (kind: string, fields: Record<string, string>) =>
      client.request<ApiCredentialCheck>('POST', `/setup/keys/${kind}`, { fields }),
    finishSharedKeys: (llmPreset: LlmPreset) =>
      client.request<ApiSetupState>('POST', '/setup/keys/done', { llmPreset }),
    registerToss: (fields: Record<string, string>) =>
      client.request<ApiCredentialCheck>('POST', '/setup/toss/keys', { fields }),
    decideToss: (decision: TossDecision) =>
      client.request<ApiSetupState>('POST', '/setup/toss', { decision }),
    complete: () => client.request<ApiSetupState>('POST', '/setup/complete', {}),
  }
}
