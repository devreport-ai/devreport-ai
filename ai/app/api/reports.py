import json
from typing import Annotated, Any, TypeVar

from fastapi import APIRouter, Depends, File, Form, UploadFile
from pydantic import BaseModel, ValidationError

from app.core.config import Settings, get_settings
from app.core.errors import AIServiceError, ErrorCode
from app.schemas.generation import GenerationManifest, GenerationRequest
from app.services.mock_report_generator import MockReportGenerator
from app.services.multipart_bundle_validator import MultipartBundleValidator

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
    """검증된 multipart bundle을 받아 현재 연동 단계에서는 샘플 문서를 반환한다.

    파일 내용 분석과 Gemini 호출은 후속 task에서 연결한다.
    """
    generation_request = parse_json_model(request, GenerationRequest)
    generation_manifest = parse_json_model(await manifest.read(), GenerationManifest)
    MultipartBundleValidator.validate(generation_manifest, files or [], generation_request.file_ids)

    if not settings.mock_report:
        raise AIServiceError(
            ErrorCode.AI_UNAVAILABLE,
            "Gemini 보고서 생성 기능이 아직 준비되지 않았습니다.",
        )

    return MockReportGenerator(
        sample_report_path=settings.sample_report_path,
        report_schema_path=settings.report_schema_path,
    ).generate()


def parse_json_model(raw_value: str | bytes, model: type[ModelT]) -> ModelT:
    try:
        value = json.loads(raw_value)
        return model.model_validate(value)
    except (json.JSONDecodeError, UnicodeDecodeError, ValidationError) as exception:
        raise AIServiceError(
            ErrorCode.AI_INVALID_REQUEST,
            "AI 생성 요청 형식이 올바르지 않습니다.",
        ) from exception
