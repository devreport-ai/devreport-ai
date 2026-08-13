/** 계약이 허용한 인라인 6종만 통과시키는지 고정한다. XSS 차단이 걸린 함수라 전수 검사한다. */
import { describe, expect, it } from 'vitest'
import { parseInline } from './inline'

describe('parseInline', () => {
  it('허용 문법 6종을 전부 인식한다', () => {
    expect(parseInline('**굵게**')).toEqual([{ type: 'bold', content: '굵게' }])
    expect(parseInline('*기울임*')).toEqual([{ type: 'italic', content: '기울임' }])
    expect(parseInline('~~취소~~')).toEqual([{ type: 'strike', content: '취소' }])
    expect(parseInline('`코드`')).toEqual([{ type: 'code', content: '코드' }])
    expect(parseInline('[링크](https://a.dev)')).toEqual([
      { type: 'link', content: '링크', href: 'https://a.dev' },
    ])
    expect(parseInline('줄\n바꿈')).toEqual([
      { type: 'text', content: '줄' },
      { type: 'br' },
      { type: 'text', content: '바꿈' },
    ])
  })

  it('문장 속에 섞여 있어도 자른다', () => {
    expect(parseInline('앞 **강조** 뒤')).toEqual([
      { type: 'text', content: '앞 ' },
      { type: 'bold', content: '강조' },
      { type: 'text', content: ' 뒤' },
    ])
  })

  it('javascript: 링크는 링크로 만들지 않는다', () => {
    // 계약: http/https 만 허용. 위험한 scheme 은 일반 텍스트로 남긴다.
    const nodes = parseInline('[클릭](javascript:alert(1))')
    expect(nodes.every((n) => n.type !== 'link')).toBe(true)
  })

  it('HTML 태그는 문법으로 취급하지 않는다', () => {
    // raw HTML 금지. 태그가 와도 그냥 글자다. 렌더러가 innerHTML 을 안 쓰므로 실행되지 않는다.
    expect(parseInline('<script>alert(1)</script>')).toEqual([
      { type: 'text', content: '<script>alert(1)</script>' },
    ])
  })

  it('짝이 안 맞는 기호는 그대로 둔다', () => {
    expect(parseInline('2 * 3 = 6')).toEqual([{ type: 'text', content: '2 * 3 = 6' }])
  })

  it('굵게 안의 기울임 같은 중첩은 지원하지 않는다 (계약에 없음)', () => {
    // ***a*** 의 우선순위는 계약에 없다. 굵게로 잡히고 바깥 * 는 글자로 남는 현재 동작을 고정한다.
    expect(parseInline('***a***')).toEqual([
      { type: 'text', content: '*' },
      { type: 'bold', content: 'a' },
      { type: 'text', content: '*' },
    ])
  })
})
