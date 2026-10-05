import type { ApiLogin } from '@renderer/generated/api-login'
import type { ApiUser } from '@renderer/generated/api-user'
import type { Client } from '../client/Client'

export interface LoginInput {
  readonly userId: string
  readonly password: string
  readonly totpCode?: string
  readonly recoveryCode?: string
}

/** 세션 API. 토큰은 main 이 걷어 내므로 응답에는 사용자 요약만 남음. */
export function sessionApi(client: Client) {
  return {
    users: () => client.request<ApiUser[]>('GET', '/session/users'),
    login: (input: LoginInput) =>
      client.request<Omit<ApiLogin, 'token'>>('POST', '/session/login', input),
    logout: () => client.request<null>('POST', '/session/logout', {}),
    me: () => client.request<ApiUser>('GET', '/me'),
  }
}
