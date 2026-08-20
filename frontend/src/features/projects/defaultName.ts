/** 이름 없이 만든 프로젝트의 기본 이름 (#110). */
const BASE = '새 프로젝트'

/**
 * 기존 이름과 겹치지 않는 기본 이름을 만든다.
 * `새 프로젝트` → 이미 있으면 `새 프로젝트 2`, `새 프로젝트 3` 순으로 이어간다.
 * 목록은 현재 페이지만 볼 수 있어 뒷페이지와 겹칠 수 있다. 그때는 서버 이름이 중복될 뿐 생성은 성공한다.
 */
export function nextDefaultProjectName(existing: readonly string[]): string {
  const used = new Set(existing.map((name) => name.trim()))
  if (!used.has(BASE)) return BASE

  for (let n = 2; ; n++) {
    const candidate = `${BASE} ${n}`
    if (!used.has(candidate)) return candidate
  }
}
