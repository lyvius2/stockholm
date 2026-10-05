import type { ApiMethod } from '../../../preload/bridge'
import { ApiError, type Client } from './Client'

interface ErrorBody {
  code?: unknown
  message?: unknown
}

/** 데몬이 401 을 돌려줬을 때 창에 알리는 이벤트 이름. 세션 유무를 다시 물어야 하는 화면이 듣는다. */
export const UNAUTHORIZED_EVENT = 'stockholm:unauthorized'

/** Electron main 의 대리인을 IPC 로 부름. 토큰은 main 에 있어 렌더러는 모름. */
export class LocalClient implements Client {
  async request<T>(method: ApiMethod, path: string, body?: unknown): Promise<T> {
    const response = await window.stockholm.api.request(
      body === undefined ? { method, path } : { method, path, body },
    )
    if (response.status >= 200 && response.status < 300) return response.body as T
    if (response.status === 401) window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
    const error = (response.body ?? {}) as ErrorBody
    throw new ApiError(
      response.status,
      typeof error.code === 'string' ? error.code : `HTTP_${response.status}`,
      typeof error.message === 'string' ? error.message : `요청 실패 (${response.status})`,
    )
  }
}

export const localClient: Client = new LocalClient()
