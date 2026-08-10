# ReportDocument 계약

`ReportDocument`는 디자인과 무관한 보고서 콘텐츠다. Frontend는 stable ID를 기준으로
section과 block을 수정·이동·추가·삭제한다.

## ID 규칙

- section ID는 문서의 `sections` 안에서 유일해야 한다.
- block ID는 문서 전체에서 유일해야 한다.
- ID는 생성 후 콘텐츠나 순서가 바뀌어도 유지한다.
- 형식은 영문·숫자로 시작하는 1~64자의 영문·숫자·`_`·`-`다.

JSON Schema는 ID 형식을 검증한다. 속성 기준 배열 유일성은 JSON Schema로 표현할 수
없으므로 Backend가 저장 전에 별도로 검증한다.

## Block

| type | 필수 필드 | 선택 필드 |
| --- | --- | --- |
| `paragraph` | `id`, `type`, `content` | 없음 |
| `bulletList` | `id`, `type`, `items` | 없음 |
| `code` | `id`, `type`, `code` | `language` |
| `table` | `id`, `type`, `columns`, `rows` | 없음 |
| `image` | `id`, `type`, `fileId`, `alt` | `caption` |
| `callout` | `id`, `type`, `content` | `title` |
| `pageBreak` | `id`, `type` | 없음 |

table의 각 row는 `columns`와 같은 수의 cell을 가져야 한다. `image.fileId`는 해당
프로젝트에 존재하는 PNG/JPG 파일 UUID여야 한다. 파일 소유권·상태·MIME 검증은
Backend가 담당한다.

## Markdown 범위

`paragraph.content`, `bulletList.items`, table cell, `callout.content`에는 다음 inline
Markdown만 허용한다.

- 굵게, 기울임, 취소선
- inline code
- `http`/`https` 링크
- 줄바꿈

heading, 목록, 표, fenced code, Markdown image는 대응 block으로 표현한다. raw HTML은
허용하지 않는다. Frontend는 Markdown 렌더링 결과를 sanitize하고 위험한 URL scheme을
차단한다.

## Report envelope

목표 Report API는 `document`, `templateId`, `templateVersion`,
`presentationSettings`를 분리해 반환한다. 현재 raw `ReportDocument` 응답을 envelope로
전환하는 구현은 #32에서 진행한다. 템플릿 미선택 상태에서는 `templateId`와
`templateVersion`이 모두 `null`이다.

`presentationSettings`에는 템플릿이 정의한 scalar 값만 저장한다. 중첩 객체·배열이나
임의 HTML/CSS는 저장하지 않는다. 템플릿 변경은 `document`를 수정하지 않는다.
