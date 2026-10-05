/** 지연 실행. 돌려주는 함수로 취소함. 테스트가 시간을 손으로 돌리기 위해 주입함. */
export type Scheduler = (task: () => void, delayMs: number) => () => void

export function timerScheduler(task: () => void, delayMs: number): () => void {
  const timer = setTimeout(task, delayMs)
  return () => clearTimeout(timer)
}
