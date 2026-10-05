import {
  formatDecimal,
  formatMoney,
  formatPercentFromRatio,
  signOf,
} from '@renderer/shared/format/decimal'

/** 금액은 문자열로만 다루고 표시 포맷만 함. */
describe('decimal 표기', () => {
  it('천 단위 구분과 소수부·부호를 그대로 둠', () => {
    expect(formatDecimal('1234567')).toBe('1,234,567')
    expect(formatDecimal('-1234567.50')).toBe('-1,234,567.50')
    expect(formatDecimal('0.5')).toBe('0.5')
    expect(formatDecimal('abc')).toBe('abc')
  })

  it('비율 문자열을 백분율로, 둘째 자리에서 반올림(문자열 산술)', () => {
    expect(formatPercentFromRatio('0.0036')).toBe('0.36%')
    expect(formatPercentFromRatio('-0.00535')).toBe('-0.54%')
    expect(formatPercentFromRatio('0.1')).toBe('10.00%')
    expect(formatPercentFromRatio('0.0999999')).toBe('10.00%')
    expect(formatPercentFromRatio('1')).toBe('100.00%')
  })

  it('통화별 기호와 부호 분류', () => {
    expect(formatMoney('74300', 'KRW')).toBe('74,300원')
    expect(formatMoney('120.50', 'USD')).toBe('$120.50')
    expect(signOf('12.30')).toBe('up')
    expect(signOf('-0.01')).toBe('down')
    expect(signOf('0.00')).toBe('flat')
    expect(signOf('-0')).toBe('flat')
  })
})
