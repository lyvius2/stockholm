/**
 * 데몬이 주는 decimal 문자열의 표시용 포맷.
 * 금액을 number 로 바꾸지 않고 문자열만 다룸(계산은 데몬이 함).
 */

/** `-1234567.5` → `-1,234,567.5`. 숫자 꼴이 아니면 그대로 돌려줌. */
export function formatDecimal(text: string): string {
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(text.trim())
  if (match === null) return text
  const [, sign, whole, fraction] = match
  const grouped = (whole ?? '').replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return fraction === undefined ? `${sign}${grouped}` : `${sign}${grouped}.${fraction}`
}

/** 비율 문자열(`0.0036`)을 백분율 표기(`0.36%`)로. 소수 둘째 자리까지, 문자열 연산만. */
export function formatPercentFromRatio(ratio: string, digits = 2): string {
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(ratio.trim())
  if (match === null) return ratio
  const [, sign, whole, fraction = ''] = match
  // ×100 은 소수점을 두 칸 옮기는 것과 같음
  const integerPart = `${whole}${fraction.slice(0, 2).padEnd(2, '0')}`.replace(/^0+(?=\d)/, '')
  const rounded = roundDigits(integerPart, fraction.slice(2), digits)
  return `${sign}${rounded}%`
}

/** 통화 코드에 따라 기호와 소수 자리를 붙임. */
export function formatMoney(amount: string, currency: string): string {
  const formatted = formatDecimal(amount)
  return currency === 'USD' ? `$${formatted}` : `${formatted}원`
}

/** 부호 분류. 0 은 flat. */
export function signOf(text: string): 'up' | 'down' | 'flat' {
  const trimmed = text.trim()
  if (/^-?0*(\.0*)?$/.test(trimmed)) return 'flat'
  return trimmed.startsWith('-') ? 'down' : 'up'
}

// 정수부 문자열과 남은 소수 문자열을 digits 자리로 반올림(문자열 산술)
function roundDigits(whole: string, fraction: string, digits: number): string {
  const kept = fraction.slice(0, digits).padEnd(digits, '0')
  const next = fraction.charAt(digits)
  let combined = `${whole || '0'}${kept}`
  if (next !== '' && Number(next) >= 5) combined = incrementDigits(combined)
  const head = combined.slice(0, combined.length - digits) || '0'
  const tail = combined.slice(combined.length - digits)
  return digits === 0 ? head : `${head}.${tail}`
}

function incrementDigits(digits: string): string {
  const chars = digits.split('')
  for (let i = chars.length - 1; i >= 0; i -= 1) {
    if (chars[i] !== '9') {
      chars[i] = String(Number(chars[i]) + 1)
      return chars.join('')
    }
    chars[i] = '0'
  }
  return `1${chars.join('')}`
}
