from __future__ import annotations

import json
import logging
from collections.abc import Callable, Iterator, Sequence
from contextlib import contextmanager
from pathlib import Path
from time import monotonic
from typing import Any, TypeVar, cast
from uuid import UUID

from pydantic import BaseModel, ValidationError

from app.clients.structured_client import (
    REPORT_DOCUMENT_MAX_OUTPUT_TOKENS,
    StructuredGenerationClient,
)
from app.core.errors import AIServiceError, ErrorCode
from app.prompts.report_generation import (
    image_analysis_prompt,
    report_document_prompt,
    report_plan_prompt,
    requirement_analysis_prompt,
    source_analysis_prompt,
)
from app.schemas.analysis import AnalysisContext, ImageEvidence, PdfEvidence
from app.schemas.generation import GenerationRequest
from app.schemas.pipeline import (
    ImageAnalysis,
    ImageObservation,
    ReportPlan,
    RequirementAnalysis,
    SourceAnalysis,
)
from app.services.report_document_validator import ReportDocumentValidator

log = logging.getLogger(__name__)
ModelT = TypeVar("ModelT", bound=BaseModel)


class ReportGenerationPipeline:
    """provider 분석 결과를 단계적으로 축적해 검증된 ReportDocument로 조합한다."""

    def __init__(self, client: StructuredGenerationClient, report_schema_path: Path) -> None:
        self._client = client
        self._document_validator = ReportDocumentValidator(report_schema_path)

    def generate(self, request: GenerationRequest, context: AnalysisContext) -> dict[str, Any]:
        document_ids = {str(item.file_id) for item in context.documents}
        document_ids.update(str(pdf.file_id) for pdf in context.pdfs)
        source_ids = {str(item.file_id) for item in context.source_files}
        file_ids = document_ids | source_ids
        image_ids = {str(image.file_id) for image in context.images}
        log.info(
            "보고서 생성 시작 documents=%d pdfs=%d sourceFiles=%d images=%d omitted=%d",
            len(context.documents),
            len(context.pdfs),
            len(context.source_files),
            len(context.images),
            len(context.omitted),
        )

        requirements = self._generate_model(
            "requirements",
            RequirementAnalysis,
            requirement_analysis_prompt(context.documents, context.omitted, context.pdfs),
            validate=lambda result: self._validate_requirement_references(result, document_ids),
            pdfs=context.pdfs,
        )
        source = self._generate_model(
            "source",
            SourceAnalysis,
            source_analysis_prompt(context.source_files, context.omitted),
            validate=lambda result: self._validate_source_references(result, source_ids),
        )
        images = tuple(self._analyze_image(image) for image in context.images)

        plan = self._generate_model(
            "plan",
            ReportPlan,
            report_plan_prompt(request, requirements, source, images, file_ids, image_ids),
            validate=lambda result: self._validate_plan_references(result, file_ids, image_ids),
        )

        document = self._generate_document(
            report_document_prompt(
                request, requirements, source, images, plan, image_ids, context.omitted
            ),
            request,
            self._image_ids(context),
        )
        log.info("보고서 생성 완료 sections=%d", len(document.get("sections", ())))
        return document

    def _analyze_image(self, image: ImageEvidence) -> ImageAnalysis:
        observation = self._generate_model(
            "image", ImageObservation, image_analysis_prompt(image), images=(image,)
        )
        return ImageAnalysis(file_id=image.file_id, observation=observation)

    def _generate_model(
        self,
        stage: str,
        model: type[ModelT],
        prompt: str,
        images: Sequence[ImageEvidence] = (),
        pdfs: Sequence[PdfEvidence] = (),
        validate: Callable[[ModelT], None] | None = None,
    ) -> ModelT:
        def validate_response(response: str) -> ModelT:
            try:
                result = model.model_validate_json(response)
            except (ValidationError, ValueError) as exception:
                raise AIServiceError(
                    ErrorCode.AI_INVALID_RESPONSE,
                    "AI 분석 응답 형식이 올바르지 않습니다.",
                ) from exception
            if validate:
                validate(result)
            return result

        with stage_log(stage):
            result = self._client.generate_json(
                prompt,
                images,
                pdfs=pdfs,
                response_model=model,
                response_validator=validate_response,
            )
        return cast(ModelT, result)

    def _generate_document(
        self, prompt: str, request: GenerationRequest, image_ids: tuple[UUID, ...]
    ) -> dict[str, Any]:
        def validate_response(response: str) -> dict[str, Any]:
            try:
                document = json.loads(response)
            except json.JSONDecodeError as exception:
                raise AIServiceError(
                    ErrorCode.AI_INVALID_RESPONSE,
                    "AI 보고서 응답 형식이 올바르지 않습니다.",
                ) from exception
            if not isinstance(document, dict):
                raise AIServiceError(
                    ErrorCode.AI_INVALID_RESPONSE,
                    "AI 보고서 응답 형식이 올바르지 않습니다.",
                )
            return self._document_validator.validate(document, request.metadata, image_ids)

        with stage_log("document"):
            # 계약 스키마는 블록 oneOf를 쓰므로 structured output 대신 프롬프트로 형식을 고정한다.
            result = self._client.generate_json(
                prompt,
                max_output_tokens=REPORT_DOCUMENT_MAX_OUTPUT_TOKENS,
                response_validator=validate_response,
            )
        return cast(dict[str, Any], result)

    @staticmethod
    def _validate_requirement_references(
        requirements: RequirementAnalysis, allowed_document_ids: set[str]
    ) -> None:
        role_file_ids = {str(document.file_id) for document in requirements.document_roles}
        referenced_file_ids = {
            str(file_id)
            for requirement in requirements.requirements
            for file_id in requirement.evidence_file_ids
        }
        if (
            not role_file_ids <= allowed_document_ids
            or not referenced_file_ids <= allowed_document_ids
        ):
            raise unknown_evidence()

    @staticmethod
    def _validate_source_references(source: SourceAnalysis, allowed_source_ids: set[str]) -> None:
        referenced_file_ids = {
            str(file_id) for finding in source.findings for file_id in finding.evidence_file_ids
        }
        if not referenced_file_ids <= allowed_source_ids:
            raise unknown_evidence()

    @staticmethod
    def _validate_plan_references(
        plan: ReportPlan, allowed_file_ids: set[str], allowed_image_ids: set[str]
    ) -> None:
        referenced_file_ids = {
            str(file_id) for section in plan.sections for file_id in section.evidence_file_ids
        }
        referenced_image_ids = {
            str(file_id) for section in plan.sections for file_id in section.image_file_ids
        }
        valid_references = (
            referenced_file_ids <= allowed_file_ids and referenced_image_ids <= allowed_image_ids
        )
        if not valid_references:
            raise unknown_evidence()

    @staticmethod
    def _image_ids(context: AnalysisContext) -> tuple[UUID, ...]:
        return tuple(image.file_id for image in context.images)


@contextmanager
def stage_log(stage: str) -> Iterator[None]:
    """단계별 소요 시간을 남겨 어느 단계에서 실패했는지 추적할 수 있게 한다."""
    started_at = monotonic()
    try:
        yield
    except Exception:
        log.warning("분석 단계 실패 stage=%s elapsed=%.2fs", stage, monotonic() - started_at)
        raise
    log.info("분석 단계 완료 stage=%s elapsed=%.2fs", stage, monotonic() - started_at)


def unknown_evidence() -> AIServiceError:
    return AIServiceError(
        ErrorCode.AI_INVALID_RESPONSE,
        "Gemini 분석 근거가 입력 파일과 일치하지 않습니다.",
    )
