import type { StreamClientMessage } from '../renderer/generated/stream-client-message'
import type { StreamServerMessage } from '../renderer/generated/stream-server-message'

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

/** 화면이 보는 종목 하나. protocol 의 symbol 정의와 같음. */
export type StreamSymbol = StreamClientMessage['symbols'][number]

/** main 이 데몬 `/ws` 에 맺은 연결의 상태. 증권사 시세 연결 상태는 메시지(feedState)로 따로 옴. */
export type StreamConnectionState = 'closed' | 'connecting' | 'open'

export type Unsubscribe = () => void

/**
 * 로컬 실시간 스트림. 연결은 main 이 맺고 렌더러는 선언과 수신만 함.
 * subscribe 는 보는 종목 전체를 바꿔 끼우는 선언이며 연결이 없으면 열고, 끊긴 뒤 다시 붙으면 main 이 다시 선언함.
 */
export interface StreamBridge {
  subscribe(symbols: readonly StreamSymbol[]): Promise<void>
  close(): Promise<void>
  state(): Promise<StreamConnectionState>
  onMessages(listener: (messages: StreamServerMessage[]) => void): Unsubscribe
  onState(listener: (state: StreamConnectionState) => void): Unsubscribe
}

/** 렌더러에 노출하는 전부. 비밀값·파일 시스템·프로세스 API 는 여기 두지 않음. */
export interface StockholmBridge {
  readonly daemon: { status(): Promise<DaemonStatus> }
  readonly theme: { current(): Promise<ThemeName> }
  readonly api: { request(request: ApiRequest): Promise<ApiResponse> }
  readonly session: {
    hasSession(): Promise<boolean>
    clear(): Promise<void>
    /** main 이 세션을 끝냈을 때(로그아웃·만료·401). 화면은 사용자 상태를 비우고 로그인 모달로 감. */
    onEnded(listener: () => void): Unsubscribe
  }
  readonly stream: StreamBridge
  readonly app: { version(): Promise<string>; expandForMain(): Promise<void> }
}
