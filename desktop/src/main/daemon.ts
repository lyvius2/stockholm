import { spawn, type ChildProcess } from 'node:child_process'
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import type { DaemonStatus } from '../preload/bridge'

/** 데몬 접속 정보. 포트는 backend/application-engine.yml 의 2609 와 같아야 함. */
export const DAEMON_BASE_URL = 'http://127.0.0.1:2609'

const HEALTH_PATH = '/actuator/health'
const POLL_INTERVAL_MS = 500
const DAEMON_HEAP = '-Xmx384m'

/** 헬스 엔드포인트가 UP 인지 한 번 확인함. 실패는 예외가 아니라 false 로 돌려줌. */
export async function probeDaemon(baseUrl: string = DAEMON_BASE_URL): Promise<DaemonStatus> {
  try {
    const response = await fetch(`${baseUrl}${HEALTH_PATH}`)
    if (!response.ok) return { reachable: false, baseUrl }
    const body: unknown = await response.json()
    return { reachable: isUp(body), baseUrl }
  } catch {
    return { reachable: false, baseUrl }
  }
}

/** 데몬이 뜰 때까지 기다림. 제한 시간 안에 못 뜨면 false. */
export async function waitForDaemon(
  timeoutMs: number,
  baseUrl: string = DAEMON_BASE_URL,
): Promise<boolean> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const status = await probeDaemon(baseUrl)
    if (status.reachable) return true
    await sleep(POLL_INTERVAL_MS)
  }
  return false
}

/**
 * 묶여 있는 JRE 와 bootJar 로 데몬을 자식 프로세스로 띄움.
 * 개발 실행(`./gradlew dev`)처럼 이미 떠 있으면 붙기만 하고, 번들이 없으면 띄우지 않음.
 */
export class DaemonProcess {
  private child: ChildProcess | null = null

  constructor(
    private readonly bundleDir: string,
    private readonly dataDir: string,
  ) {}

  async startIfNeeded(): Promise<'attached' | 'spawned' | 'unavailable'> {
    if ((await probeDaemon()).reachable) return 'attached'
    const java = join(this.bundleDir, 'jre', 'bin', 'java')
    const jar = join(this.bundleDir, 'stockholm.jar')
    if (!existsSync(java) || !existsSync(jar)) return 'unavailable'
    this.child = spawn(
      java,
      [
        DAEMON_HEAP,
        '-jar',
        jar,
        '--spring.profiles.active=engine',
        `--stockholm.data-dir=${this.dataDir}`,
      ],
      {
        stdio: 'ignore',
        detached: false,
      },
    )
    this.child.on('exit', () => {
      this.child = null
    })
    return 'spawned'
  }

  stop(): void {
    this.child?.kill('SIGTERM')
    this.child = null
  }
}

function isUp(body: unknown): boolean {
  return typeof body === 'object' && body !== null && 'status' in body && body.status === 'UP'
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
