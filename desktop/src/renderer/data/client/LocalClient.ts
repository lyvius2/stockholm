import type { ApiMethod } from '../../../preload/bridge'
import type { ApiError as ApiErrorBody } from '@renderer/generated/api-error'
import { ApiError, type Client } from './Client'

interface ErrorBody {
  code?: unknown
  message?: unknown
  violations?: unknown
  notes?: unknown
  tickSize?: unknown
  nearestPrices?: unknown
}

const stringList = (value: unknown): string[] | undefined =>
  Array.isArray(value) && value.every((v) => typeof v === 'string') ? value : undefined

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
    throw errorOf(response.status, (response.body ?? {}) as ErrorBody)
  }
}

// 주문 경로의 상세(위반 규칙·확인 노트·호가 단위 제안)는 있을 때만 실음
function errorOf(status: number, error: ErrorBody): ApiError {
  const code = typeof error.code === 'string' ? error.code : `HTTP_${status}`
  const message = typeof error.message === 'string' ? error.message : `요청 실패 (${status})`
  const details: ApiErrorBody = { code, message }
  const violations = stringList(error.violations)
  if (violations !== undefined) details.violations = violations
  const notes = stringList(error.notes)
  if (notes !== undefined) details.notes = notes
  if (typeof error.tickSize === 'string') details.tickSize = error.tickSize
  const nearestPrices = stringList(error.nearestPrices)
  if (nearestPrices !== undefined) details.nearestPrices = nearestPrices
  return new ApiError(status, code, message, details)
}

export const localClient: Client = new LocalClient()
