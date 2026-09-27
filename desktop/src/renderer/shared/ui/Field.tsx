import type { InputHTMLAttributes } from 'react'

interface FieldProps extends InputHTMLAttributes<HTMLInputElement> {
  readonly label: string
  readonly hint?: string
}

/** 라벨 + 입력 한 줄. 비밀 입력은 `type="password"` 로 넘김. */
export function Field({ label, hint, id, ...input }: FieldProps) {
  const inputId = id ?? `field-${label}`
  return (
    <label className="field" htmlFor={inputId}>
      <span className="field-label">{label}</span>
      <input id={inputId} {...input} />
      {hint !== undefined && <span className="field-hint">{hint}</span>}
    </label>
  )
}
