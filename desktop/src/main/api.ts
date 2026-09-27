import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import type { ApiRequest, ApiResponse } from '../preload/bridge'
import { DAEMON_BASE_URL } from './daemon'

const LOCAL_TOKEN_HEADER = 'X-Stockholm-Local-Token'
const LOCAL_TOKEN_FILE = 'local-token'

/** 토큰이 실려 오는 경로. main 이 보관하고 렌더러에는 넘기지 않음. */
const TOKEN_BEARING_PATHS: ReadonlyMap<string, 'session' | 'wizard'> = new Map([
  ['/session/login', 'session'],
  ['/setup/admin/totp', 'wizard'],
  ['/setup/session', 'wizard'],
])

interface TokenBearingBody {
  token?: string
  wizardToken?: string
}

/**
 * 데몬 로컬 API 의 대리인. 로컬 토큰 파일과 세션 토큰은 여기에만 있음.
 * 렌더러는 IPC 로 요청만 보내고 응답에서 토큰은 걷어 낸 채 받음.
 */
export class DaemonApi {
  private localToken: string | null = null
  private sessionToken: string | null = null

  constructor(
    private readonly dataDir: string,
    private readonly baseUrl: string = DAEMON_BASE_URL,
  ) {}

  hasSession(): boolean {
    return this.sessionToken !== null
  }

  clearSession(): void {
    this.sessionToken = null
  }

  async request(request: ApiRequest): Promise<ApiResponse> {
    const headers: Record<string, string> = { [LOCAL_TOKEN_HEADER]: await this.readLocalToken() }
    if (request.body !== undefined) headers['Content-Type'] = 'application/json'
    if (this.sessionToken !== null) headers['Authorization'] = `Bearer ${this.sessionToken}`
    const init: RequestInit = { method: request.method, headers }
    if (request.body !== undefined) init.body = JSON.stringify(request.body)
    const response = await fetch(`${this.baseUrl}${request.path}`, init)
    const body = await parseBody(response)
    return { status: response.status, body: this.absorbTokens(request.path, response.status, body) }
  }

  private absorbTokens(path: string, status: number, body: unknown): unknown {
    if (status >= 300 || !isRecord(body)) return body
    const kind = TOKEN_BEARING_PATHS.get(path)
    if (kind === undefined) {
      if (path === '/session/logout') this.sessionToken = null
      return body
    }
    const { token, wizardToken, ...rest } = body as TokenBearingBody & Record<string, unknown>
    const issued = kind === 'session' ? token : wizardToken
    if (typeof issued === 'string' && issued.length > 0) this.sessionToken = issued
    return rest
  }

  private async readLocalToken(): Promise<string> {
    if (this.localToken === null) {
      // 데몬이 기동할 때마다 새로 쓰므로 실패하면 다음 요청에서 다시 읽음
      this.localToken = (await readFile(join(this.dataDir, LOCAL_TOKEN_FILE), 'utf8')).trim()
    }
    return this.localToken
  }

  /** 데몬이 다시 뜨면 토큰 파일이 바뀌므로 잊음. */
  forgetLocalToken(): void {
    this.localToken = null
  }
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
