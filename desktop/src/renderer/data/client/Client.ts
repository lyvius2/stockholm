import type { ApiMethod } from '../../../preload/bridge'

/** 데몬 API 오류. 상태와 도메인 예외 코드를 가짐. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/** 로컬(IPC 경유)·원격(7단계) 모드를 가르는 한 겹. 화면은 이 인터페이스만 봄. */
export interface Client {
  request<T>(method: ApiMethod, path: string, body?: unknown): Promise<T>
}
