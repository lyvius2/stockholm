import type { StreamConnectionState, StreamSymbol } from '../preload/bridge'
import type { StreamClientMessage } from '../renderer/generated/stream-client-message'
import type { StreamServerMessage } from '../renderer/generated/stream-server-message'
import { DAEMON_BASE_URL } from './daemon'
import { timerScheduler, type Scheduler } from './timers'

/** 데몬 로컬 WebSocket 주소. REST 와 같은 포트의 `/ws` 하나. */
export const DAEMON_STREAM_URL = `${DAEMON_BASE_URL.replace(/^http/, 'ws')}/ws`

// 끊기면 1초부터 두 배씩 늘려 10초까지 기다린 뒤 다시 붙음
const FIRST_RETRY_MS = 1_000
const MAX_RETRY_MS = 10_000

/** 소켓 중 쓰는 부분만. 실제는 Node 의 WebSocket, 테스트는 가짜. */
export interface StreamSocket {
  send(text: string): void
  close(): void
  onOpen(listener: () => void): void
  onMessage(listener: (text: string) => void): void
  onClose(listener: () => void): void
}

export type SocketFactory = (url: string, headers: Record<string, string>) => StreamSocket

/** 받은 묶음과 연결 상태가 가는 곳(렌더러 창들). */
export interface StreamSink {
  messages(messages: StreamServerMessage[]): void
  state(state: StreamConnectionState): void
}

/**
 * 데몬 `/ws` 의 유일한 클라이언트.
 * 로컬 토큰과 세션 토큰은 main 에만 있으므로 연결도 main 이 맺고, 렌더러는 선언과 수신만 IPC 로 함.
 * 끊기면 세션이 있는 동안 물러나기(backoff)로 다시 붙고, 붙을 때마다 마지막 선언을 다시 보냄.
 */
export class DaemonStream {
  private socket: StreamSocket | null = null
  // 헤더를 읽는 동안 또 선언이 와도 연결은 하나만 열도록 진행 중인 시도를 붙들어 둠
  private connecting: Promise<void> | null = null
  private declared: StreamSymbol[] = []
  private isWanted = false
  private failures = 0
  private cancelRetry: (() => void) | null = null
  private current: StreamConnectionState = 'closed'

  constructor(
    private readonly headers: () => Promise<Record<string, string> | null>,
    private readonly sink: StreamSink,
    private readonly openSocket: SocketFactory = connectNodeSocket,
    private readonly schedule: Scheduler = timerScheduler,
    private readonly url: string = DAEMON_STREAM_URL,
  ) {}

  state(): StreamConnectionState {
    return this.current
  }

  /** 보는 종목 전체를 선언함. 연결이 없으면 열고, 여는 중이면 열린 뒤 마지막 선언만 보냄. */
  async subscribe(symbols: readonly StreamSymbol[]): Promise<void> {
    this.declared = [...symbols]
    this.isWanted = true
    if (this.current === 'open') this.sendDeclaration()
    else if (this.socket === null && this.cancelRetry === null) await this.connectOnce()
  }

  /** 연결을 닫고 다시 붙지 않음(로그아웃·종료). */
  close(): void {
    this.isWanted = false
    this.cancelRetry?.()
    this.cancelRetry = null
    const socket = this.socket
    this.socket = null
    socket?.close()
    this.failures = 0
    this.setState('closed')
  }

  private connectOnce(): Promise<void> {
    if (this.connecting === null) {
      this.connecting = this.connect().finally(() => {
        this.connecting = null
      })
    }
    return this.connecting
  }

  private async connect(): Promise<void> {
    // 헤더(토큰 파일)를 읽는 동안도 연결 중으로 보여 화면이 바로 알게 함
    this.setState('connecting')
    const headers = await this.headers()
    // 세션이 없으면 데몬이 401 로 거부하므로 시도하지 않음. 로그인하면 화면이 다시 선언함
    if (headers === null || !this.isWanted) {
      this.setState('closed')
      return
    }
    const socket = this.openSocket(this.url, headers)
    this.socket = socket
    socket.onOpen(() => {
      if (this.socket !== socket) return
      this.failures = 0
      this.setState('open')
      this.sendDeclaration()
    })
    socket.onMessage((text) => {
      if (this.socket !== socket) return
      const batch = parseBatch(text)
      if (batch.length > 0) this.sink.messages(batch)
    })
    socket.onClose(() => {
      if (this.socket !== socket) return
      this.socket = null
      this.setState('closed')
      this.scheduleRetry()
    })
  }

  private scheduleRetry(): void {
    if (!this.isWanted) return
    const delay = Math.min(FIRST_RETRY_MS * 2 ** this.failures, MAX_RETRY_MS)
    this.failures += 1
    this.cancelRetry = this.schedule(() => {
      this.cancelRetry = null
      void this.connectOnce()
    }, delay)
  }

  private sendDeclaration(): void {
    const message: StreamClientMessage = { type: 'subscribe', symbols: this.declared }
    this.socket?.send(JSON.stringify(message))
  }

  private setState(state: StreamConnectionState): void {
    if (this.current === state) return
    this.current = state
    this.sink.state(state)
  }
}

// 데몬은 묶음을 배열로 보냄. 배열이 아니거나 JSON 이 아니면 버림
function parseBatch(text: string): StreamServerMessage[] {
  try {
    const parsed: unknown = JSON.parse(text)
    return Array.isArray(parsed) ? (parsed as StreamServerMessage[]) : []
  } catch {
    return []
  }
}

// Node 의 WebSocket 은 WHATWG 모양이지만 두 번째 인자로 헤더를 받음(undici 확장)
function connectNodeSocket(url: string, headers: Record<string, string>): StreamSocket {
  const socket = new WebSocket(url, { headers })
  return {
    send: (text) => socket.send(text),
    close: () => socket.close(),
    onOpen: (listener) => socket.addEventListener('open', () => listener()),
    onMessage: (listener) =>
      socket.addEventListener('message', (event) => listener(String(event.data))),
    onClose: (listener) => socket.addEventListener('close', () => listener()),
  }
}
