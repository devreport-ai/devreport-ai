/** 기본 프로젝트 이름 번호 매기기 (#110). */
import { describe, expect, it } from 'vitest'
import { nextDefaultProjectName } from './defaultName'

describe('nextDefaultProjectName', () => {
  it('겹치는 이름이 없으면 기본 이름을 쓴다', () => {
    expect(nextDefaultProjectName([])).toBe('새 프로젝트')
    expect(nextDefaultProjectName(['다른 프로젝트'])).toBe('새 프로젝트')
  })

  it('이미 있으면 번호를 이어 붙인다', () => {
    expect(nextDefaultProjectName(['새 프로젝트'])).toBe('새 프로젝트 2')
    expect(nextDefaultProjectName(['새 프로젝트', '새 프로젝트 2'])).toBe('새 프로젝트 3')
  })

  it('중간 번호가 비면 그 번호를 쓴다', () => {
    expect(nextDefaultProjectName(['새 프로젝트', '새 프로젝트 3'])).toBe('새 프로젝트 2')
  })
})
