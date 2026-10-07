import type { ApiMethod } from '../../../preload/bridge'
import type { ApiError as ApiErrorBody } from '@renderer/generated/api-error'

/**
 * 데몬 API 오류.
 * 상태·도메인 예외 코드와 함께 주문 경로의 상세(위반 규칙·확인할 노트·호가 단위 제안)를 가짐.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details: ApiErrorBody = { code, message },
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/** 로컬(IPC 경유)·원격(7단계) 모드를 가르는 한 겹. 화면은 이 인터페이스만 봄. */
export interface Client {
  request<T>(method: ApiMethod, path: string, body?: unknown): Promise<T>
}
