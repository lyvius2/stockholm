import type { DaemonStatus } from '../preload/bridge'

/** 데몬 접속 정보. 포트는 backend/application-engine.yml 의 2609 와 같아야 함. */
export const DAEMON_BASE_URL = 'http://127.0.0.1:2609'

const HEALTH_PATH = '/actuator/health'
const POLL_INTERVAL_MS = 500

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

// TODO(1단계 7번 데몬 기동): jlink JRE + bootJar 자식 프로세스 기동과 로컬 토큰 전달을 여기에 붙임

function isUp(body: unknown): boolean {
  return typeof body === 'object' && body !== null && 'status' in body && body.status === 'UP'
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
