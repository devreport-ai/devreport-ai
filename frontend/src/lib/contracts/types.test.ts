/**
 * 계약에서 파생된 판별 로직 테스트.
 *
 * 여기 있는 두 함수는 계약 문서의 규칙을 코드로 옮긴 것이라, 틀리면 화면이 조용히
 * 잘못 동작한다. 예를 들어 PDF 를 분석 대상으로 고를 수 있게 되면 사용자는
 * "올렸는데 왜 보고서에 안 나오지" 를 겪는다.
 */
import { describe, expect, it } from 'vitest'
import { isAiInputFile, isTerminalStatus } from './types'

function file(originalName: string, contentType: string) {
  return { originalName, contentType }
}

describe('isAiInputFile', () => {
  it('AI 가 읽는 형식을 고를 수 있게 한다', () => {
    // contracts/report-generation.md: AI 입력은 ZIP·MD·TXT·PNG·JPG 다.
    expect(isAiInputFile(file('src.zip', 'application/zip'))).toBe(true)
    expect(isAiInputFile(file('README.md', 'text/markdown'))).toBe(true)
    expect(isAiInputFile(file('notes.txt', 'text/plain'))).toBe(true)
    expect(isAiInputFile(file('shot.png', 'image/png'))).toBe(true)
    expect(isAiInputFile(file('shot.jpeg', 'image/jpeg'))).toBe(true)
  })

  it('PDF 와 DOCX 는 업로드는 되지만 분석 대상이 아니다', () => {
    // 업로드 API 는 받아주지만 AI 생성 입력에서는 제외된다. 이 구분이 이 함수의 존재 이유다.
    expect(isAiInputFile(file('과제.pdf', 'application/pdf'))).toBe(false)
    expect(
      isAiInputFile(
        file(
          '보고서.docx',
          'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        ),
      ),
    ).toBe(false)
  })

  it('대문자 확장자도 인식한다', () => {
    expect(isAiInputFile(file('SHOT.PNG', 'image/png'))).toBe(true)
  })
})

describe('isTerminalStatus', () => {
  it('더 진행되지 않는 상태에서 true 를 준다', () => {
    // 폴링을 멈출 기준이다. 하나라도 빠지면 무한 폴링이 된다.
    expect(isTerminalStatus('COMPLETED')).toBe(true)
    expect(isTerminalStatus('FAILED')).toBe(true)
    expect(isTerminalStatus('CANCELED')).toBe(true)
  })

  it('진행 중인 상태에서 false 를 준다', () => {
    expect(isTerminalStatus('PENDING')).toBe(false)
    expect(isTerminalStatus('PROCESSING')).toBe(false)
  })
})
