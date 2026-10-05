import type { Scheduler } from '../../../src/main/timers'

interface Scheduled {
  readonly task: () => void
  readonly delayMs: number
  isCancelled: boolean
}

/** 시간을 손으로 돌리는 스케줄러. 예약을 기록하고 테스트가 하나씩 실행함. */
export class ManualScheduler {
  readonly entries: Scheduled[] = []
  readonly schedule: Scheduler = (task, delayMs) => {
    const entry: Scheduled = { task, delayMs, isCancelled: false }
    this.entries.push(entry)
    return () => {
      entry.isCancelled = true
    }
  }

  fireNext(): void {
    const next = this.entries.find((entry) => !entry.isCancelled)
    if (next === undefined) throw new Error('예약된 작업이 없음')
    next.isCancelled = true
    next.task()
  }

  pendingDelays(): number[] {
    return this.entries.filter((entry) => !entry.isCancelled).map((entry) => entry.delayMs)
  }
}
