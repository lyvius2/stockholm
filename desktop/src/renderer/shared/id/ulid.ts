// Crockford base32. 토스 멱등 키는 [A-Za-z0-9_-]{1,36} 이라 26자 ULID 가 그대로 들어감
const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
const TIME_LENGTH = 10
const RANDOM_LENGTH = 16

/**
 * ULID(시간 48비트 + 난수 80비트, 26자).
 * 주문 모달이 제출 버튼을 누를 때 한 번 만들어 재시도에도 같은 값을 보내는 멱등 키로 씀.
 */
export function newUlid(now: number = Date.now()): string {
  let time = ''
  let remaining = now
  for (let i = 0; i < TIME_LENGTH; i += 1) {
    time = (ALPHABET[remaining % 32] ?? '0') + time
    remaining = Math.floor(remaining / 32)
  }
  const bytes = new Uint8Array(RANDOM_LENGTH)
  crypto.getRandomValues(bytes)
  let random = ''
  for (const byte of bytes) random += ALPHABET[byte % 32] ?? '0'
  return time + random
}
