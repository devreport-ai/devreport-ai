/**
 * 계약에서 파생된 판별 로직 테스트.
 *
 * 여기 있는 두 함수는 계약 문서의 규칙을 코드로 옮긴 것이라, 틀리면 화면이 조용히
 * 잘못 동작한다. PDF도 분석 대상으로 고를 수 있어야 업로드한 자료가 생성에 반영된다.
 */
import { describe, expect, it } from 'vitest'
import { AI_INPUT_ACCEPT, aiInputExclusionReason, isAiInputFile, isTerminalStatus } from './types'

function file(originalName: string, contentType: string) {
  return { originalName, contentType }
}

describe('isAiInputFile', () => {
  it('AI 가 읽는 형식을 고를 수 있게 한다', () => {
    // contracts/report-generation.md: AI 입력은 문서·소스·이미지를 직접 받는다.
    expect(isAiInputFile(file('src.zip', 'application/zip'))).toBe(true)
    expect(
      isAiInputFile(
        file(
          '과제.docx',
          'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        ),
      ),
    ).toBe(true)
    expect(isAiInputFile(file('README.md', 'text/markdown'))).toBe(true)
    expect(isAiInputFile(file('App.java', 'text/x-java-source'))).toBe(true)
    expect(isAiInputFile(file('main.py', 'application/octet-stream'))).toBe(true)
    expect(isAiInputFile(file('notes.txt', 'text/plain'))).toBe(true)
    expect(isAiInputFile(file('shot.png', 'image/png'))).toBe(true)
    expect(isAiInputFile(file('shot.jpeg', 'image/jpeg'))).toBe(true)
    expect(isAiInputFile(file('과제.pdf', 'application/pdf'))).toBe(true)
  })

  it('지원하지 않는 형식은 선택할 수 없고 이유를 알려 준다', () => {
    expect(isAiInputFile(file('animation.gif', 'image/gif'))).toBe(false)
    expect(aiInputExclusionReason(file('animation.gif', 'image/gif'))).toBe(
      'AI 분석을 지원하지 않는 파일 형식입니다.',
    )
  })

  it('대문자 확장자도 인식한다', () => {
    expect(isAiInputFile(file('SHOT.PNG', 'image/png'))).toBe(true)
  })

  it('확장자가 없으면 MIME 으로 판단하되 계약에 있는 형식만 통과시킨다', () => {
    // text/ 프리픽스로 보면 계약에 없는 text/html 까지 통과해 버린다.
    expect(isAiInputFile(file('README', 'text/markdown'))).toBe(true)
    expect(isAiInputFile(file('page', 'text/html'))).toBe(false)
  })

  it('Content-Type 에 파라미터가 붙어도 인식한다', () => {
    expect(isAiInputFile(file('notes', 'text/plain; charset=utf-8'))).toBe(true)
  })

  it('업로드 선택창이 직접 소스 확장자를 포함한다', () => {
    expect(AI_INPUT_ACCEPT).toContain('.docx')
    expect(AI_INPUT_ACCEPT).toContain('.java')
    expect(AI_INPUT_ACCEPT).toContain('.pdf')
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
