import json
import logging
from secrets import compare_digest
from typing import Annotated, Any, TypeVar

from fastapi import APIRouter, Depends, File, Form, Header, UploadFile
from pydantic import BaseModel, ValidationError
from starlette.concurrency import run_in_threadpool

from app.clients.gemini_client import GeminiClient
from app.core.config import Settings, get_settings
from app.core.deadline import Deadline
from app.core.errors import AIServiceError, ErrorCode
from app.schemas.generation import GenerationManifest, GenerationRequest
from app.services.bundle_normalizer import BundleNormalizer
from app.services.mock_report_generator import MockReportGenerator
from app.services.model_selection import resolve_model_selection
from app.services.multipart_bundle_validator import MultipartBundleValidator
from app.services.report_generation_pipeline import ReportGenerationPipeline

router = APIRouter(prefix="/internal/ai/reports", tags=["internal reports"])
INTERNAL_TOKEN_HEADER = "X-Internal-Token"
# 사용자가 등록한 provider API Key. Backend가 생성 실행 시에만 헤더로 전달한다.
PROVIDER_API_KEY_HEADER = "X-Provider-Api-Key"

log = logging.getLogger(__name__)
SettingsDep = Annotated[Settings, Depends(get_settings)]
ModelT = TypeVar("ModelT", bound=BaseModel)


@router.post("/generate", summary="Generate a ReportDocument")
async def generate_report(
    request: Annotated[str, Form()],
    manifest: Annotated[UploadFile, File()],
    settings: SettingsDep,
    files: Annotated[list[UploadFile] | None, File()] = None,
    internal_token: Annotated[str | None, Header(alias=INTERNAL_TOKEN_HEADER)] = None,
    provider_api_key: Annotated[str | None, Header(alias=PROVIDER_API_KEY_HEADER)] = None,
) -> dict[str, Any]:
    """검증된 Backend multipart bundle을 Mock 또는 Gemini 파이프라인으로 처리한다."""
    if not is_authorized(internal_token, settings.ai_internal_token):
        raise AIServiceError(ErrorCode.AI_UNAUTHORIZED, "내부 요청 인증에 실패했습니다.")

    generation_request = parse_json_model(request, GenerationRequest)
    generation_manifest = parse_json_model(await manifest.read(), GenerationManifest)
    MultipartBundleValidator.validate(generation_manifest, files or [], generation_request.file_ids)
    selection = resolve_model_selection(generation_request, settings, provider_api_key)
    log.info(
        "생성 요청 수신 files=%d mock=%s provider=%s model=%s user_key=%s",
        len(generation_manifest.files),
        settings.mock_report,
        selection.provider,
        selection.model,
        selection.uses_user_key,
    )

    if settings.mock_report:
        # Mock은 계약 확인용이므로 bundle 내용을 읽지 않고 샘플 문서를 반환한다.
        return MockReportGenerator(
            sample_report_path=settings.sample_report_path,
            report_schema_path=settings.report_schema_path,
        ).generate()

    context = await BundleNormalizer(settings.max_analyzed_images).normalize(
        generation_manifest, files or []
    )
    pipeline = ReportGenerationPipeline(
        GeminiClient(
            api_key=selection.api_key,
            model=selection.model,
            timeout_seconds=settings.gemini_timeout_seconds,
            max_retries=settings.gemini_max_retries,
            deadline=Deadline(settings.generation_deadline_seconds),
        ),
        settings.report_schema_path,
    )
    return await run_in_threadpool(pipeline.generate, generation_request, context)


def is_authorized(internal_token: str | None, expected_token: str | None) -> bool:
    # compare_digest는 비ASCII 문자열에 TypeError를 던진다. 잘못된 헤더가 500이 되지
    # 않도록 바이트로 비교한다.
    if not internal_token or not expected_token:
        return False
    return compare_digest(internal_token.encode("utf-8"), expected_token.encode("utf-8"))


def parse_json_model(raw_value: str | bytes, model: type[ModelT]) -> ModelT:
    try:
        value = json.loads(raw_value)
        return model.model_validate(value)
    except (json.JSONDecodeError, UnicodeDecodeError, ValidationError) as exception:
        raise AIServiceError(
            ErrorCode.AI_INVALID_REQUEST,
            "AI 생성 요청 형식이 올바르지 않습니다.",
        ) from exception
