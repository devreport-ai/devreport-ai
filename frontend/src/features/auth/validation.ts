/**
 * 입력 검증 — 계약(openapi.yaml)의 제한만 그대로 옮긴다. 최종 판정은 서버.
 */

/** 계약: format: email, maxLength 320 */
export function emailError(email: string): string | null {
  const value = email.trim()
  if (value === '') return '이메일을 입력해 주세요.'
  // 느슨한 형식 검사. 엄밀한 판정은 서버가 한다.
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) return '이메일 형식이 아닙니다.'
  if (value.length > 320) return '이메일은 320자를 넘을 수 없습니다.'
  return null
}

/** 계약: minLength 8, 최대 72바이트(BCrypt 상한). 바이트 기준이라 한글은 24자. */
export function passwordError(password: string): string | null {
  if (password === '') return '비밀번호를 입력해 주세요.'
  if ([...password].length < 8) return '비밀번호는 8자 이상이어야 합니다.'
  if (new TextEncoder().encode(password).length > 72)
    return '비밀번호가 너무 깁니다. (영문 72자, 한글 24자까지)'
  return null
}

/** 계약: required, maxLength 100 */
export function nameError(name: string): string | null {
  const value = name.trim()
  if (value === '') return '이름을 입력해 주세요.'
  if (value.length > 100) return '이름은 100자를 넘을 수 없습니다.'
  return null
}
