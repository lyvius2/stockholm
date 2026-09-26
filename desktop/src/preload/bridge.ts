/** main · preload · renderer 가 함께 보는 브리지 타입. electron 을 import 하지 않음. */
export interface DaemonStatus {
  readonly reachable: boolean
  readonly baseUrl: string
}

export type ThemeName = 'light' | 'dark'

/** 렌더러에 노출하는 전부. 비밀값·파일 시스템·프로세스 API 는 여기 두지 않음. */
export interface StockholmBridge {
  readonly daemon: { status(): Promise<DaemonStatus> }
  readonly theme: { current(): Promise<ThemeName> }
}
