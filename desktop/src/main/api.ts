import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import type { ApiRequest, ApiResponse } from '../preload/bridge'
import { DAEMON_BASE_URL } from './daemon'
import { timerScheduler, type Scheduler } from './timers'

const LOCAL_TOKEN_HEADER = 'X-Stockholm-Local-Token'
const LOCAL_TOKEN_FILE = 'local-token'
const HTTP_UNAUTHORIZED = 401

/** 토큰이 실려 오는 경로. main 이 보관하고 렌더러에는 넘기지 않음. */
const TOKEN_BEARING_PATHS: ReadonlyMap<string, 'session' | 'wizard'> = new Map([
  ['/session/login', 'session'],
  ['/setup/admin/totp', 'wizard'],
  ['/setup/session', 'wizard'],
])

/**
 * 401 이어도 세션이 살아 있는 경우: 로그인·step-up 에서 비밀번호나 TOTP 가 거부된 것.
 * 그 밖의 401(세션 필터·로컬 토큰 필터·SessionInvalidException)은 세션이 끝난 것임.
 */
const CREDENTIAL_REJECTED_CODES: ReadonlySet<string> = new Set([
  'TotpRejectedException',
  'AuthenticationFailedException',
])

interface TokenBearingBody {
  token?: string
  wizardToken?: string
  expiresAt?: string
}

/**
 * 데몬 로컬 API 의 대리인. 로컬 토큰 파일과 세션 토큰은 여기에만 있음.
 * 렌더러는 IPC 로 요청만 보내고 응답에서 토큰은 걷어 낸 채 받음.
 * 세션이 끝나면(로그아웃·만료·401) 듣는 쪽에 알려 스트림과 화면이 함께 닫히게 함.
 */
export class DaemonApi {
  private localToken: string | null = null
  private sessionToken: string | null = null
  private cancelExpiry: (() => void) | null = null
  private readonly sessionEndedListeners: Array<() => void> = []

  constructor(
    private readonly dataDir: string,
    private readonly baseUrl: string = DAEMON_BASE_URL,
    private readonly schedule: Scheduler = timerScheduler,
    private readonly now: () => number = Date.now,
  ) {}

  hasSession(): boolean {
    return this.sessionToken !== null
  }

  clearSession(): void {
    this.endSession()
  }

  /** 세션이 끝날 때(로그아웃·만료·비우기) 알림. 로컬 WebSocket 이 이것을 듣고 연결을 닫음. */
  onSessionEnded(listener: () => void): void {
    this.sessionEndedListeners.push(listener)
  }

  /** 로컬 WebSocket 연결에 붙일 헤더. 세션이 없으면 null(연결하지 않음). */
  async authHeaders(): Promise<Record<string, string> | null> {
    if (this.sessionToken === null) return null
    // 데몬이 다시 떴으면 토큰 파일이 바뀌어 있으므로 붙을 때마다 다시 읽음
    return {
      [LOCAL_TOKEN_HEADER]: await this.reloadLocalToken(),
      Authorization: `Bearer ${this.sessionToken}`,
    }
  }

  async request(request: ApiRequest): Promise<ApiResponse> {
    const localToken = await this.readLocalToken()
    let response = await this.send(request, localToken)
    if (response.status === HTTP_UNAUTHORIZED) {
      // 데몬이 다시 떠서 로컬 토큰이 바뀐 것이면 새 토큰으로 한 번만 다시 보냄
      const reloaded = await this.reloadLocalToken()
      if (reloaded !== localToken) response = await this.send(request, reloaded)
    }
    if (response.status === HTTP_UNAUTHORIZED && !isCredentialRejected(response.body)) {
      this.endSession()
    }
    return {
      status: response.status,
      body: this.absorbTokens(request.path, response.status, response.body),
    }
  }

  private async send(request: ApiRequest, localToken: string): Promise<ApiResponse> {
    const headers: Record<string, string> = { [LOCAL_TOKEN_HEADER]: localToken }
    if (request.body !== undefined) headers['Content-Type'] = 'application/json'
    if (this.sessionToken !== null) headers['Authorization'] = `Bearer ${this.sessionToken}`
    const init: RequestInit = { method: request.method, headers }
    if (request.body !== undefined) init.body = JSON.stringify(request.body)
    const response = await fetch(`${this.baseUrl}${request.path}`, init)
    return { status: response.status, body: await parseBody(response) }
  }

  private absorbTokens(path: string, status: number, body: unknown): unknown {
    if (status >= 300 || !isRecord(body)) return body
    const kind = TOKEN_BEARING_PATHS.get(path)
    if (kind === undefined) {
      if (path === '/session/logout') this.endSession()
      return body
    }
    const { token, wizardToken, ...rest } = body as TokenBearingBody & Record<string, unknown>
    const issued = kind === 'session' ? token : wizardToken
    if (typeof issued === 'string' && issued.length > 0) {
      this.sessionToken = issued
      if (kind === 'session') this.scheduleExpiry(rest.expiresAt)
    }
    return rest
  }

  // 세션은 로그인 시각부터 고정 길이라 만료 시각에 맞춰 스스로 끝냄.
  // 열린 WebSocket 은 데몬이 세션을 다시 확인하지 않으므로 이 타이머가 아니면 만료 뒤에도 남음
  private scheduleExpiry(expiresAt: unknown): void {
    this.cancelExpiry?.()
    this.cancelExpiry = null
    if (typeof expiresAt !== 'string') return
    const delayMs = Date.parse(expiresAt) - this.now()
    if (Number.isNaN(delayMs)) return
    this.cancelExpiry = this.schedule(() => this.endSession(), Math.max(0, delayMs))
  }

  private endSession(): void {
    this.cancelExpiry?.()
    this.cancelExpiry = null
    if (this.sessionToken === null) return
    this.sessionToken = null
    this.sessionEndedListeners.forEach((listener) => listener())
  }

  private async readLocalToken(): Promise<string> {
    if (this.localToken === null) return this.reloadLocalToken()
    return this.localToken
  }

  // 데몬이 기동할 때마다 새로 쓰므로 실패하면 다음 요청에서 다시 읽음
  private async reloadLocalToken(): Promise<string> {
    this.localToken = (await readFile(join(this.dataDir, LOCAL_TOKEN_FILE), 'utf8')).trim()
    return this.localToken
  }

  /** 데몬이 다시 뜨면 토큰 파일이 바뀌므로 잊음. */
  forgetLocalToken(): void {
    this.localToken = null
  }
}

function isCredentialRejected(body: unknown): boolean {
  return (
    isRecord(body) &&
    typeof body['code'] === 'string' &&
    CREDENTIAL_REJECTED_CODES.has(body['code'])
  )
}

async function parseBody(response: Response): Promise<unknown> {
  const text = await response.text()
  if (text.length === 0) return null
  try {
    return JSON.parse(text) as unknown
  } catch {
    return text
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
