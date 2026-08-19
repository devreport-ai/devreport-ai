from __future__ import annotations

import json
from collections.abc import Callable, Sequence
from pathlib import Path
from typing import Any, TypeVar, cast
from uuid import UUID

from pydantic import BaseModel, ValidationError

from app.clients.gemini_client import REPORT_DOCUMENT_MAX_OUTPUT_TOKENS, GeminiClient
from app.core.errors import AIServiceError, ErrorCode
from app.prompts.report_generation import (
    image_analysis_prompt,
    report_document_prompt,
    report_plan_prompt,
    requirement_analysis_prompt,
    source_analysis_prompt,
)
from app.schemas.analysis import AnalysisContext, ImageEvidence
from app.schemas.generation import GenerationRequest
from app.schemas.pipeline import (
    ImageAnalysis,
    ImageObservation,
    ReportPlan,
    RequirementAnalysis,
    SourceAnalysis,
)
from app.services.report_document_validator import ReportDocumentValidator

ModelT = TypeVar("ModelT", bound=BaseModel)


class ReportGenerationPipeline:
    """Gemini 분석 결과를 단계적으로 축적해 검증된 ReportDocument로 조합한다."""

    def __init__(self, gemini: GeminiClient, report_schema_path: Path) -> None:
        self._gemini = gemini
        self._document_validator = ReportDocumentValidator(report_schema_path)

    def generate(self, request: GenerationRequest, context: AnalysisContext) -> dict[str, Any]:
        file_ids = {str(item.file_id) for item in (*context.documents, *context.source_files)}
        image_ids = {str(image.file_id) for image in context.images}
        requirements = self._generate_model(
            RequirementAnalysis,
            requirement_analysis_prompt(context.documents),
            validate=lambda result: self._validate_requirement_references(result, file_ids),
        )
        source = self._generate_model(
            SourceAnalysis,
            source_analysis_prompt(context.source_files),
            validate=lambda result: self._validate_source_references(result, file_ids),
        )
        images = tuple(self._analyze_image(image) for image in context.images)

        plan = self._generate_model(
            ReportPlan,
            report_plan_prompt(request, requirements, source, images, file_ids, image_ids),
            validate=lambda result: self._validate_plan_references(result, file_ids, image_ids),
        )

        return self._generate_document(
            report_document_prompt(request, requirements, source, images, plan, image_ids),
            request,
            self._image_ids(context),
        )

    def _analyze_image(self, image: ImageEvidence) -> ImageAnalysis:
        observation = self._generate_model(
            ImageObservation, image_analysis_prompt(image), images=(image,)
        )
        return ImageAnalysis(file_id=image.file_id, observation=observation)

    def _generate_model(
        self,
        model: type[ModelT],
        prompt: str,
        images: Sequence[ImageEvidence] = (),
        validate: Callable[[ModelT], None] | None = None,
    ) -> ModelT:
        def validate_response(response: str) -> ModelT:
            try:
                result = model.model_validate_json(response)
            except (ValidationError, ValueError) as exception:
                raise AIServiceError(
                    ErrorCode.AI_INVALID_RESPONSE,
                    "Gemini 분석 응답 형식이 올바르지 않습니다.",
                ) from exception
            if validate:
                validate(result)
            return result

        result = self._gemini.generate_json(prompt, images, response_validator=validate_response)
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
                    "Gemini 보고서 응답 형식이 올바르지 않습니다.",
                ) from exception
            if not isinstance(document, dict):
                raise AIServiceError(
                    ErrorCode.AI_INVALID_RESPONSE,
                    "Gemini 보고서 응답 형식이 올바르지 않습니다.",
                )
            return self._document_validator.validate(document, request.metadata, image_ids)

        result = self._gemini.generate_json(
            prompt,
            max_output_tokens=REPORT_DOCUMENT_MAX_OUTPUT_TOKENS,
            response_validator=validate_response,
        )
        return cast(dict[str, Any], result)

    @staticmethod
    def _validate_requirement_references(
        requirements: RequirementAnalysis, allowed_file_ids: set[str]
    ) -> None:
        referenced_file_ids = {
            str(file_id)
            for requirement in requirements.requirements
            for file_id in requirement.evidence_file_ids
        }
        if not referenced_file_ids <= allowed_file_ids:
            raise AIServiceError(
                ErrorCode.AI_INVALID_RESPONSE,
                "Gemini 분석 근거가 입력 파일과 일치하지 않습니다.",
            )

    @staticmethod
    def _validate_source_references(source: SourceAnalysis, allowed_file_ids: set[str]) -> None:
        referenced_file_ids = {
            str(file_id) for finding in source.findings for file_id in finding.evidence_file_ids
        }
        if not referenced_file_ids <= allowed_file_ids:
            raise AIServiceError(
                ErrorCode.AI_INVALID_RESPONSE,
                "Gemini 분석 근거가 입력 파일과 일치하지 않습니다.",
            )

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
            raise AIServiceError(
                ErrorCode.AI_INVALID_RESPONSE,
                "Gemini 분석 근거가 입력 파일과 일치하지 않습니다.",
            )

    @staticmethod
    def _image_ids(context: AnalysisContext) -> tuple[UUID, ...]:
        return tuple(image.file_id for image in context.images)
