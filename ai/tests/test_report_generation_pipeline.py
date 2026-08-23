from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path
from uuid import UUID

import pytest

from app.clients.gemini_client import REPORT_DOCUMENT_MAX_OUTPUT_TOKENS
from app.core.errors import AIServiceError, ErrorCode
from app.prompts.report_generation import report_document_prompt
from app.schemas.analysis import (
    AnalysisContext,
    ImageEvidence,
    OmittedFile,
    PdfEvidence,
    TextEvidence,
)
from app.schemas.generation import GenerationRequest
from app.schemas.pipeline import ReportPlan, RequirementAnalysis, SourceAnalysis
from app.services.report_generation_pipeline import ReportGenerationPipeline

REPO_ROOT = Path(__file__).resolve().parents[2]
REPORT_SCHEMA = REPO_ROOT / "contracts" / "report-document.schema.json"
DOCUMENT_ID = UUID("00000000-0000-4000-8000-000000000001")
SOURCE_ID = UUID("00000000-0000-4000-8000-000000000002")
IMAGE_ID = UUID("00000000-0000-4000-8000-000000000003")
PDF_ID = UUID("00000000-0000-4000-8000-000000000005")
UNKNOWN_ID = "00000000-0000-4000-8000-000000000099"
UNREADABLE_ID = UUID("00000000-0000-4000-8000-000000000004")


class FakeGemini:
    def __init__(self, responses: list[dict[str, object]]) -> None:
        self.responses = responses
        self.calls: list[tuple[str, tuple[ImageEvidence, ...], tuple[PdfEvidence, ...]]] = []
        self.output_token_limits: list[int | None] = []
        self.response_models: list[object] = []

    def generate_json(
        self,
        prompt: str,
        images: tuple[ImageEvidence, ...] = (),
        *,
        pdfs: tuple[PdfEvidence, ...] = (),
        max_output_tokens: int | None = None,
        response_model: object = None,
        response_validator: object = None,
    ) -> object:
        self.calls.append((prompt, images, pdfs))
        self.output_token_limits.append(max_output_tokens)
        self.response_models.append(response_model)
        response = json.dumps(self.responses.pop(0), ensure_ascii=False)
        if response_validator is None:
            return response
        return response_validator(response)


def request() -> GenerationRequest:
    return GenerationRequest.model_validate(
        {
            "fileIds": [str(DOCUMENT_ID), str(SOURCE_ID), str(IMAGE_ID)],
            "metadata": {"title": "실습보고서", "author": "김예찬"},
            "instructions": "확인 가능한 사실만 보고서에 작성해 주세요.",
        }
    )


def context() -> AnalysisContext:
    return AnalysisContext(
        documents=(
            TextEvidence(
                DOCUMENT_ID, "documents/id/assignment.md", "text/markdown", "요구사항", False
            ),
        ),
        source_files=(
            TextEvidence(SOURCE_ID, "source/id/App.java", "text/plain", "class App {}", False),
        ),
        images=(ImageEvidence(IMAGE_ID, "images/id/result.png", "image/png", b"png-bytes"),),
    )


def pdf_context() -> AnalysisContext:
    return replace(
        context(),
        pdfs=(PdfEvidence(PDF_ID, "documents/id/assignment.pdf", "application/pdf", b"pdf"),),
    )


def responses(
    *, plan_evidence_id: str = str(SOURCE_ID), title: str = "실습보고서"
) -> list[dict[str, object]]:
    return [
        {
            "summary": "과제 요구사항을 확인했다.",
            "documentRoles": [
                {
                    "fileId": str(DOCUMENT_ID),
                    "role": "assignment",
                    "rationale": "과제 요구사항을 설명하는 문서다.",
                }
            ],
            "requirements": [
                {
                    "id": "req-api",
                    "description": "API를 구현한다.",
                    "acceptanceCriteria": ["API 요청이 동작한다."],
                    "evidenceFileIds": [str(DOCUMENT_ID)],
                }
            ],
        },
        {
            "summary": "애플리케이션 진입점을 확인했다.",
            "findings": [
                {
                    "id": "finding-app",
                    "title": "애플리케이션",
                    "description": "App 클래스가 존재한다.",
                    "implementationEvidence": ["App 클래스가 소스에 선언되어 있다."],
                    "evidenceFileIds": [str(SOURCE_ID)],
                    "snippets": [
                        {
                            "fileId": str(SOURCE_ID),
                            "path": "source/id/App.java",
                            "startLine": 1,
                            "endLine": 1,
                            "sourceTruncated": False,
                            "content": "class App {}",
                        }
                    ],
                }
            ],
        },
        {"alt": "실행 결과", "caption": "그림 1. 실행 화면", "findings": ["응답 화면이 보인다."]},
        {
            "sections": [
                {
                    "id": "overview",
                    "title": "프로젝트 개요",
                    "purpose": "구현 내용을 설명한다.",
                    "evidenceFileIds": [plan_evidence_id],
                    "sourceFindingIds": ["finding-app"],
                    "imageFileIds": [str(IMAGE_ID)],
                }
            ]
        },
        {
            "metadata": {"title": title, "author": "김예찬"},
            "sections": [
                {
                    "id": "overview",
                    "title": "프로젝트 개요",
                    "blocks": [
                        {
                            "id": "overview-summary",
                            "type": "paragraph",
                            "content": "API를 구현했다.",
                        },
                        {
                            "id": "overview-code",
                            "type": "code",
                            "code": "class App {}",
                            "language": "java",
                        },
                        {
                            "id": "overview-image",
                            "type": "image",
                            "fileId": str(IMAGE_ID),
                            "alt": "실행 결과",
                            "caption": "그림 1. 실행 화면",
                        },
                    ],
                }
            ],
        },
    ]


def test_generates_document_from_staged_analysis_and_passes_only_image_to_vision_call():
    gemini = FakeGemini(responses())

    document = ReportGenerationPipeline(gemini, REPORT_SCHEMA).generate(request(), context())

    assert document["metadata"] == {"title": "실습보고서", "author": "김예찬"}
    assert len(gemini.calls) == 5
    assert gemini.calls[2][1] == context().images
    assert all("신뢰할 수 없는 데이터" in prompt for prompt, _, _ in gemini.calls)


def test_passes_pdf_bytes_only_to_the_requirement_analysis_stage():
    gemini = FakeGemini(responses())

    ReportGenerationPipeline(gemini, REPORT_SCHEMA).generate(request(), pdf_context())

    assert gemini.calls[0][2] == pdf_context().pdfs
    assert all(not call[2] for call in gemini.calls[1:])
    assert "assignment.pdf" in gemini.calls[0][0]


def test_rejects_plan_that_references_a_file_outside_the_generation_bundle():
    gemini = FakeGemini(responses(plan_evidence_id=UNKNOWN_ID))

    with pytest.raises(AIServiceError) as raised:
        ReportGenerationPipeline(gemini, REPORT_SCHEMA).generate(request(), context())

    assert raised.value.code == ErrorCode.AI_INVALID_RESPONSE
    assert len(gemini.calls) == 4


def test_rejects_source_snippet_that_does_not_match_the_input_lines():
    staged_responses = responses()
    staged_responses[1]["findings"][0]["snippets"][0]["content"] = "class Invented {}"
    gemini = FakeGemini(staged_responses)

    with pytest.raises(AIServiceError) as raised:
        ReportGenerationPipeline(gemini, REPORT_SCHEMA).generate(request(), context())

    assert raised.value.code == ErrorCode.AI_INVALID_RESPONSE
    assert len(gemini.calls) == 2


def test_rejects_source_snippet_with_a_false_truncation_marker():
    staged_responses = responses()
    staged_responses[1]["findings"][0]["snippets"][0]["sourceTruncated"] = True
    gemini = FakeGemini(staged_responses)

    with pytest.raises(AIServiceError) as raised:
        ReportGenerationPipeline(gemini, REPORT_SCHEMA).generate(request(), context())

    assert raised.value.code == ErrorCode.AI_INVALID_RESPONSE


def test_rejects_document_role_that_references_a_file_outside_documents():
    staged_responses = responses()
    staged_responses[0]["documentRoles"][0]["fileId"] = UNKNOWN_ID
    gemini = FakeGemini(staged_responses)

    with pytest.raises(AIServiceError) as raised:
        ReportGenerationPipeline(gemini, REPORT_SCHEMA).generate(request(), context())

    assert raised.value.code == ErrorCode.AI_INVALID_RESPONSE
    assert len(gemini.calls) == 1


def test_overwrites_document_metadata_with_the_requested_metadata():
    # metadata는 요청에서 확정된 값이므로 모델이 바꿔 써도 생성이 실패하지 않는다.
    gemini = FakeGemini(responses(title="다른 제목"))

    document = ReportGenerationPipeline(gemini, REPORT_SCHEMA).generate(request(), context())

    assert document["metadata"] == {"title": "실습보고서", "author": "김예찬"}


def test_uses_structured_output_schema_for_analysis_stages_only():
    gemini = FakeGemini(responses())

    ReportGenerationPipeline(gemini, REPORT_SCHEMA).generate(request(), context())

    analysis_models = gemini.response_models[:-1]
    assert all(model is not None for model in analysis_models)
    # 계약 스키마는 블록 oneOf를 쓰므로 최종 문서 단계는 프롬프트로만 형식을 고정한다.
    assert gemini.response_models[-1] is None


def test_tells_the_model_which_files_were_omitted_from_the_bundle():
    gemini = FakeGemini(responses())
    omitted = OmittedFile(UNREADABLE_ID, "source/id/legacy.java", "unreadable")

    ReportGenerationPipeline(gemini, REPORT_SCHEMA).generate(
        request(), replace(context(), omitted=(omitted,))
    )

    source_prompt = gemini.calls[1][0]
    assert "source/id/legacy.java" in source_prompt
    assert "unreadable" in source_prompt


def test_uses_larger_output_token_limit_for_final_report_document():
    gemini = FakeGemini(responses())

    ReportGenerationPipeline(gemini, REPORT_SCHEMA).generate(request(), context())

    assert gemini.output_token_limits[:-1] == [None, None, None, None]
    assert gemini.output_token_limits[-1] == REPORT_DOCUMENT_MAX_OUTPUT_TOKENS


def test_final_prompt_contains_only_plan_selected_source_snippets():
    staged_responses = responses()
    unused_finding = json.loads(json.dumps(staged_responses[1]["findings"][0]))
    unused_finding.update(
        {
            "id": "finding-unused",
            "title": "선택되지 않은 근거",
            "description": "최종 프롬프트에서 제외되어야 한다.",
            "implementationEvidence": ["선택되지 않은 구현 근거"],
        }
    )
    staged_responses[1]["findings"].append(unused_finding)
    gemini = FakeGemini(staged_responses)

    ReportGenerationPipeline(gemini, REPORT_SCHEMA).generate(request(), context())

    final_prompt = gemini.calls[-1][0]
    assert "class App {}" in final_prompt
    assert '"sourceFindingIds":["finding-app"]' in final_prompt
    assert "선택되지 않은 구현 근거" not in final_prompt


def test_rejects_plan_when_selected_snippets_exceed_the_final_input_budget():
    base_finding = responses()[1]["findings"][0]
    findings = []
    for index in range(11):
        finding = json.loads(json.dumps(base_finding))
        finding["id"] = f"finding-{index}"
        finding["snippets"][0]["content"] = "x" * 4_000
        findings.append(finding)
    source = SourceAnalysis.model_validate({"summary": "요약", "findings": findings})
    plan = ReportPlan.model_validate(
        {
            "sections": [
                {
                    "id": "overview",
                    "title": "개요",
                    "purpose": "근거 설명",
                    "evidenceFileIds": [str(SOURCE_ID)],
                    "sourceFindingIds": [finding.id for finding in source.findings],
                    "imageFileIds": [],
                }
            ]
        }
    )

    with pytest.raises(AIServiceError) as raised:
        ReportGenerationPipeline._validate_plan_references(
            plan, {str(SOURCE_ID)}, set(), {str(SOURCE_ID)}, source
        )

    assert raised.value.code == ErrorCode.AI_INVALID_RESPONSE


def test_rejects_final_code_block_that_is_not_in_a_selected_source_snippet():
    staged_responses = responses()
    staged_responses[-1]["sections"][0]["blocks"][1]["code"] = "class Invented {}"
    gemini = FakeGemini(staged_responses)

    with pytest.raises(AIServiceError) as raised:
        ReportGenerationPipeline(gemini, REPORT_SCHEMA).generate(request(), context())

    assert raised.value.code == ErrorCode.AI_INVALID_RESPONSE
    assert "코드 인용" in raised.value.message


def test_report_document_prompt_uses_contract_block_field_names():
    prompt = report_document_prompt(
        request(),
        RequirementAnalysis.model_validate(responses()[0]),
        SourceAnalysis.model_validate(responses()[1]).findings,
        (),
        ReportPlan.model_validate(responses()[3]),
        set(),
    )

    assert '"type":"paragraph","content":"string"' in prompt
    assert "프롬프트 버전: report-generation-v4" in prompt
    assert "implementationEvidence" in prompt
    assert "sourceTruncated" in prompt
    assert "paragraph와 callout의 본문 필드는 text가 아니라 content다." in prompt
    assert "선택 metadata 필드(author, course, date)는 null로 쓰지 말고 생략한다." in prompt
