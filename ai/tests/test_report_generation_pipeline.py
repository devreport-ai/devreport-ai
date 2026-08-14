from __future__ import annotations

import json
from pathlib import Path
from uuid import UUID

import pytest

from app.core.errors import AIServiceError, ErrorCode
from app.prompts.report_generation import report_document_prompt
from app.schemas.analysis import AnalysisContext, ImageEvidence, TextEvidence
from app.schemas.generation import GenerationRequest
from app.schemas.pipeline import ReportPlan, RequirementAnalysis, SourceAnalysis
from app.services.report_generation_pipeline import ReportGenerationPipeline

REPO_ROOT = Path(__file__).resolve().parents[2]
REPORT_SCHEMA = REPO_ROOT / "contracts" / "report-document.schema.json"
DOCUMENT_ID = UUID("00000000-0000-4000-8000-000000000001")
SOURCE_ID = UUID("00000000-0000-4000-8000-000000000002")
IMAGE_ID = UUID("00000000-0000-4000-8000-000000000003")
UNKNOWN_ID = "00000000-0000-4000-8000-000000000099"


class FakeGemini:
    def __init__(self, responses: list[dict[str, object]]) -> None:
        self.responses = responses
        self.calls: list[tuple[str, tuple[ImageEvidence, ...]]] = []

    def generate_json(self, prompt: str, images: tuple[ImageEvidence, ...] = ()) -> str:
        self.calls.append((prompt, images))
        return json.dumps(self.responses.pop(0), ensure_ascii=False)


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


def responses(
    *, plan_evidence_id: str = str(SOURCE_ID), title: str = "실습보고서"
) -> list[dict[str, object]]:
    return [
        {
            "summary": "과제 요구사항을 확인했다.",
            "requirements": [
                {
                    "id": "req-api",
                    "description": "API를 구현한다.",
                    "evidenceFileIds": [str(DOCUMENT_ID)],
                }
            ],
        },
        {
            "summary": "애플리케이션 진입점을 확인했다.",
            "findings": [
                {
                    "title": "애플리케이션",
                    "description": "App 클래스가 존재한다.",
                    "evidenceFileIds": [str(SOURCE_ID)],
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
    assert all("신뢰할 수 없는 데이터" in prompt for prompt, _ in gemini.calls)


def test_rejects_plan_that_references_a_file_outside_the_generation_bundle():
    gemini = FakeGemini(responses(plan_evidence_id=UNKNOWN_ID))

    with pytest.raises(AIServiceError) as raised:
        ReportGenerationPipeline(gemini, REPORT_SCHEMA).generate(request(), context())

    assert raised.value.code == ErrorCode.AI_INVALID_RESPONSE
    assert len(gemini.calls) == 4


def test_rejects_document_that_changes_requested_metadata():
    gemini = FakeGemini(responses(title="다른 제목"))

    with pytest.raises(AIServiceError) as raised:
        ReportGenerationPipeline(gemini, REPORT_SCHEMA).generate(request(), context())

    assert raised.value.code == ErrorCode.AI_INVALID_RESPONSE


def test_report_document_prompt_uses_contract_block_field_names():
    prompt = report_document_prompt(
        request(),
        RequirementAnalysis.model_validate(responses()[0]),
        SourceAnalysis.model_validate(responses()[1]),
        (),
        ReportPlan.model_validate(responses()[3]),
        set(),
    )

    assert '"type":"paragraph","content":"string"' in prompt
    assert "paragraph와 callout의 본문 필드는 text가 아니라 content다." in prompt
    assert "선택 metadata 필드(author, course, date)는 null로 쓰지 말고 생략한다." in prompt
