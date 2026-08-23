# Contracts

- [프로젝트 파일 기반 AI 생성 계약 결정](report-generation.md)
- [ReportDocument 편집 계약](report-document.md)
- [ReportDocument JSON Schema](report-document.schema.json)
- [Report API envelope JSON Schema](report.schema.json)
- [OpenAPI](openapi.yaml)
- [ReportDocument 예시](examples/sample-report.json)
- [Report 조회·수정 API 응답 예시](examples/sample-report-response.json)

JSON Schema는 Draft 2020-12, OpenAPI는 3.1을 사용한다.

`sample-report-response.json`은 `GET /api/reports/{reportId}`와
`PUT /api/reports/{reportId}`가 반환하는 Report envelope 예시다.
