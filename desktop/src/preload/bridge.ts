/** main · preload · renderer 가 함께 보는 브리지 타입. electron 을 import 하지 않음. */
export interface DaemonStatus {
  readonly reachable: boolean
  readonly baseUrl: string
}

export type ThemeName = 'light' | 'dark'

export type ApiMethod = 'GET' | 'POST' | 'PUT' | 'DELETE'

/** 렌더러가 보내는 요청. 토큰·세션은 main 이 붙이므로 여기에는 없음. */
export interface ApiRequest {
  readonly method: ApiMethod
  readonly path: string
  readonly body?: unknown
}

/** main 이 돌려주는 응답. 토큰 필드는 main 이 걷어 낸 뒤임. */
export interface ApiResponse {
  readonly status: number
  readonly body: unknown
}

/** 렌더러에 노출하는 전부. 비밀값·파일 시스템·프로세스 API 는 여기 두지 않음. */
export interface StockholmBridge {
  readonly daemon: { status(): Promise<DaemonStatus> }
  readonly theme: { current(): Promise<ThemeName> }
  readonly api: { request(request: ApiRequest): Promise<ApiResponse> }
  readonly session: { hasSession(): Promise<boolean>; clear(): Promise<void> }
  readonly app: { version(): Promise<string>; expandForMain(): Promise<void> }
}
