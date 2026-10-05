import { useEffect } from 'react'

const TOAST_MS = 4000

/** 작은 토스트 하나. 시간이 지나면 onDone 으로 닫음. */
export function Toast({
  message,
  onDone,
}: {
  readonly message: string
  readonly onDone: () => void
}) {
  useEffect(() => {
    const timer = setTimeout(onDone, TOAST_MS)
    return () => clearTimeout(timer)
  }, [message, onDone])
  return (
    <div className="toast" role="status">
      {message}
    </div>
  )
}
