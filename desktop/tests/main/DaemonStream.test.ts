// @vitest-environment node
import type { StreamConnectionState } from '../../src/preload/bridge'
import type { StreamServerMessage } from '../../src/renderer/generated/stream-server-message'
import { DaemonStream, type StreamSocket } from '../../src/main/stream'
import { ManualScheduler } from './support/ManualScheduler'

/** 가짜 소켓. 테스트가 열고 닫고 메시지를 넣음. */
class FakeSocket implements StreamSocket {
  readonly sent: string[] = []
  isClosed = false
  private opened: (() => void) | null = null
  private received: ((text: string) => void) | null = null
  private closed: (() => void) | null = null

  constructor(
    readonly url: string,
    readonly headers: Record<string, string>,
  ) {}

  send(text: string): void {
    this.sent.push(text)
  }

  close(): void {
    this.isClosed = true
  }

  onOpen(listener: () => void): void {
    this.opened = listener
  }

  onMessage(listener: (text: string) => void): void {
    this.received = listener
  }

  onClose(listener: () => void): void {
    this.closed = listener
  }

  open(): void {
    this.opened?.()
  }

  receive(text: string): void {
    this.received?.(text)
  }

  drop(): void {
    this.closed?.()
  }
}

const samsung = { market: 'KR', code: '005930' } as const
const nvidia = { market: 'US', code: 'NVDA' } as const
const headers = { 'X-Stockholm-Local-Token': 'local-secret', Authorization: 'Bearer s' }

function harness(session: () => Promise<Record<string, string> | null> = async () => headers) {
  const sockets: FakeSocket[] = []
  const scheduler = new ManualScheduler()
  const batches: StreamServerMessage[][] = []
  const states: StreamConnectionState[] = []
  const stream = new DaemonStream(
    session,
    { messages: (batch) => batches.push(batch), state: (state) => states.push(state) },
    (url, requestHeaders) => {
      const socket = new FakeSocket(url, requestHeaders)
      sockets.push(socket)
      return socket
    },
    scheduler.schedule,
    'ws://daemon/ws',
  )
  return { stream, sockets, scheduler, batches, states }
}

// 헤더 Promise 가 풀리고 소켓이 열릴 때까지 마이크로태스크를 모두 비움
const settle = (): Promise<void> => new Promise((resolve) => setImmediate(resolve))

function lastSocket(sockets: FakeSocket[]): FakeSocket {
  const socket = sockets[sockets.length - 1]
  if (socket === undefined) throw new Error('소켓이 열리지 않음')
  return socket
}

/** main 의 스트림 클라이언트가 토큰을 붙여 붙고, 끊기면 물러나며 다시 붙어 마지막 선언을 되풀이하는지. */
describe('DaemonStream', () => {
  it('세션이 없으면 연결하지 않고 닫힘 상태로 둠', async () => {
    const { stream, sockets, states } = harness(async () => null)

    await stream.subscribe([samsung])

    expect(sockets).toHaveLength(0)
    expect(stream.state()).toBe('closed')
    expect(states).toEqual(['connecting', 'closed'])
  })

  it('로컬 토큰과 세션 헤더로 붙고, 열리면 선언을 보내고, 받은 묶음을 창에 넘김', async () => {
    const { stream, sockets, batches, states } = harness()

    await stream.subscribe([samsung, nvidia])
    const socket = lastSocket(sockets)
    expect(socket.url).toBe('ws://daemon/ws')
    expect(socket.headers).toEqual(headers)
    expect(stream.state()).toBe('connecting')
    expect(socket.sent).toEqual([])

    socket.open()
    expect(JSON.parse(socket.sent[0] ?? '')).toEqual({
      type: 'subscribe',
      symbols: [samsung, nvidia],
    })
    expect(states).toEqual(['connecting', 'open'])

    const quote = {
      type: 'quote',
      symbol: samsung,
      quote: { last: { amount: '71000', currency: 'KRW' }, asOf: '2026-10-05T01:00:00Z' },
    }
    socket.receive(JSON.stringify([quote]))
    socket.receive('{"type":"quote"}')
    socket.receive('not json')
    expect(batches).toEqual([[quote]])
  })

  it('열린 뒤 선언을 바꾸면 바로 보내고, 여는 중에 바꾸면 열린 뒤 마지막 것만 보냄', async () => {
    const { stream, sockets } = harness()

    await stream.subscribe([samsung])
    await stream.subscribe([nvidia])
    const socket = lastSocket(sockets)
    expect(sockets).toHaveLength(1)
    socket.open()
    expect(socket.sent.map((text) => JSON.parse(text).symbols)).toEqual([[nvidia]])

    await stream.subscribe([samsung, nvidia])
    expect(socket.sent).toHaveLength(2)
    expect(JSON.parse(socket.sent[1] ?? '').symbols).toEqual([samsung, nvidia])
  })

  it('끊기면 1초·2초·4초로 물러나며 다시 붙고 마지막 선언을 되풀이함', async () => {
    const { stream, sockets, scheduler, states } = harness()
    await stream.subscribe([samsung])
    lastSocket(sockets).open()

    lastSocket(sockets).drop()
    expect(stream.state()).toBe('closed')
    expect(scheduler.entries.map((entry) => entry.delayMs)).toEqual([1000])
    scheduler.fireNext()
    await settle()
    expect(sockets).toHaveLength(2)
    lastSocket(sockets).drop()
    scheduler.fireNext()
    await settle()
    lastSocket(sockets).drop()
    expect(scheduler.entries.map((entry) => entry.delayMs)).toEqual([1000, 2000, 4000])

    scheduler.fireNext()
    await settle()
    lastSocket(sockets).open()
    expect(JSON.parse(lastSocket(sockets).sent[0] ?? '').symbols).toEqual([samsung])
    expect(states.at(-1)).toBe('open')
  })

  it('열린 뒤 끊기면 기다리는 시간이 1초로 돌아감', async () => {
    const { stream, sockets, scheduler } = harness()
    await stream.subscribe([samsung])
    lastSocket(sockets).drop()
    scheduler.fireNext()
    await settle()
    lastSocket(sockets).open()

    lastSocket(sockets).drop()

    expect(scheduler.entries.map((entry) => entry.delayMs)).toEqual([1000, 1000])
  })

  it('닫으면 소켓을 닫고 다시 붙지 않으며, 다시 선언하면 새로 붙음', async () => {
    const { stream, sockets, scheduler, states } = harness()
    await stream.subscribe([samsung])
    const first = lastSocket(sockets)
    first.open()

    stream.close()
    expect(first.isClosed).toBe(true)
    expect(stream.state()).toBe('closed')
    first.drop()
    expect(scheduler.entries).toHaveLength(0)

    await stream.subscribe([nvidia])
    expect(sockets).toHaveLength(2)
    expect(states).toEqual(['connecting', 'open', 'closed', 'connecting'])
  })

  it('헤더를 읽는 동안 선언이 또 와도 연결은 하나만 열고 마지막 선언을 보냄', async () => {
    let release: (() => void) | null = null
    const { stream, sockets } = harness(
      () =>
        new Promise((resolve) => {
          release = () => resolve(headers)
        }),
    )

    const first = stream.subscribe([samsung])
    const second = stream.subscribe([nvidia])
    expect(sockets).toHaveLength(0)
    ;(release as (() => void) | null)?.()
    await Promise.all([first, second])

    expect(sockets).toHaveLength(1)
    lastSocket(sockets).open()
    expect(lastSocket(sockets).sent.map((text) => JSON.parse(text).symbols)).toEqual([[nvidia]])
  })

  it('헤더를 읽는 동안 닫으면 소켓을 열지 않음', async () => {
    let release: (() => void) | null = null
    const { stream, sockets, states } = harness(
      () =>
        new Promise((resolve) => {
          release = () => resolve(headers)
        }),
    )

    const pending = stream.subscribe([samsung])
    stream.close()
    ;(release as (() => void) | null)?.()
    await pending

    expect(sockets).toHaveLength(0)
    expect(stream.state()).toBe('closed')
    expect(states).toEqual(['connecting', 'closed'])
  })

  it('다시 붙기를 기다리는 동안 닫으면 예약을 취소함', async () => {
    const { stream, sockets, scheduler } = harness()
    await stream.subscribe([samsung])
    lastSocket(sockets).drop()

    stream.close()

    expect(scheduler.entries.every((entry) => entry.isCancelled)).toBe(true)
  })
})
