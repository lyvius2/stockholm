import type { StreamConnectionState, StreamSymbol, Unsubscribe } from '../../../preload/bridge'
import type { StreamServerMessage } from '@renderer/generated/stream-server-message'

export type { StreamConnectionState, StreamSymbol, Unsubscribe }

/**
 * 실시간 스트림의 로컬(IPC 경유)·원격(7단계) 모드를 가르는 한 겹.
 * 화면은 이 인터페이스와 store 만 봄.
 */
export interface MarketStream {
  subscribe(symbols: readonly StreamSymbol[]): Promise<void>
  close(): Promise<void>
  state(): Promise<StreamConnectionState>
  onMessages(listener: (messages: readonly StreamServerMessage[]) => void): Unsubscribe
  onState(listener: (state: StreamConnectionState) => void): Unsubscribe
}

/** Electron main 이 맺은 데몬 `/ws` 연결을 IPC 로 씀. 토큰은 main 에 있어 렌더러는 모름. */
export const localMarketStream: MarketStream = {
  subscribe: (symbols) => window.stockholm.stream.subscribe(symbols),
  close: () => window.stockholm.stream.close(),
  state: () => window.stockholm.stream.state(),
  onMessages: (listener) => window.stockholm.stream.onMessages(listener),
  onState: (listener) => window.stockholm.stream.onState(listener),
}
