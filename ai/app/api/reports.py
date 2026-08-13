import json
from typing import Annotated, Any, TypeVar

from fastapi import APIRouter, Depends, File, Form, UploadFile
from pydantic import BaseModel, ValidationError
from starlette.concurrency import run_in_threadpool

from app.clients.gemini_client import GeminiClient
from app.core.config import Settings, get_settings
from app.core.errors import AIServiceError, ErrorCode
from app.schemas.generation import GenerationManifest, GenerationRequest
from app.services.bundle_normalizer import BundleNormalizer
from app.services.mock_report_generator import MockReportGenerator
from app.services.multipart_bundle_validator import MultipartBundleValidator
from app.services.report_generation_pipeline import ReportGenerationPipeline

router = APIRouter(prefix="/internal/ai/reports", tags=["internal reports"])

SettingsDep = Annotated[Settings, Depends(get_settings)]
ModelT = TypeVar("ModelT", bound=BaseModel)


@router.post("/generate", summary="Generate a ReportDocument")
async def generate_report(
    request: Annotated[str, Form()],
    manifest: Annotated[UploadFile, File()],
    settings: SettingsDep,
    files: Annotated[list[UploadFile] | None, File()] = None,
) -> dict[str, Any]:
    """검증된 Backend multipart bundle을 Mock 또는 Gemini 파이프라인으로 처리한다."""
    generation_request = parse_json_model(request, GenerationRequest)
    generation_manifest = parse_json_model(await manifest.read(), GenerationManifest)
    MultipartBundleValidator.validate(generation_manifest, files or [], generation_request.file_ids)
    context = await BundleNormalizer().normalize(generation_manifest, files or [])

    if settings.mock_report:
        return MockReportGenerator(
            sample_report_path=settings.sample_report_path,
            report_schema_path=settings.report_schema_path,
        ).generate()

    pipeline = ReportGenerationPipeline(
        GeminiClient(
            api_key=settings.gemini_api_key,
            model=settings.gemini_model,
            timeout_seconds=settings.gemini_timeout_seconds,
            max_retries=settings.gemini_max_retries,
        ),
        settings.report_schema_path,
    )
    return await run_in_threadpool(pipeline.generate, generation_request, context)


def parse_json_model(raw_value: str | bytes, model: type[ModelT]) -> ModelT:
    try:
        value = json.loads(raw_value)
        return model.model_validate(value)
    except (json.JSONDecodeError, UnicodeDecodeError, ValidationError) as exception:
        raise AIServiceError(
            ErrorCode.AI_INVALID_REQUEST,
            "AI 생성 요청 형식이 올바르지 않습니다.",
        ) from exception
