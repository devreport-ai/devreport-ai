# Contracts

- [프로젝트 파일 기반 AI 생성 계약 결정](report-generation.md)
- [ReportDocument 편집 계약](report-document.md)
- [ReportDocument JSON Schema](report-document.schema.json)
- [Report API envelope JSON Schema](report.schema.json)
- [OpenAPI](openapi.yaml)
- [ReportDocument 예시](examples/sample-report.json)
- [#32 이후 Report 조회·수정 API 목표 응답 예시](examples/sample-report-response.json)

JSON Schema는 Draft 2020-12, OpenAPI는 3.1을 사용한다.

`sample-report-response.json`은 #32에서 `GET /api/reports/{reportId}`와
`PUT /api/reports/{reportId}`를 Report envelope로 전환한 뒤 사용할 목표 응답이다.
현재 두 endpoint는 `ReportDocument`를 직접 반환한다.
