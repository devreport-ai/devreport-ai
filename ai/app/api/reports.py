from typing import Annotated, Any

from fastapi import APIRouter, Depends

from app.core.config import Settings, get_settings
from app.core.errors import AIServiceError, ErrorCode
from app.schemas.generation import GenerationRequest
from app.services.mock_report_generator import MockReportGenerator

router = APIRouter(prefix="/internal/ai/reports", tags=["internal reports"])

SettingsDep = Annotated[Settings, Depends(get_settings)]


@router.post("/generate", summary="Generate a ReportDocument")
def generate_report(_request: GenerationRequest, settings: SettingsDep) -> dict[str, Any]:
    """현재 연동 단계에서는 검증된 샘플 문서를 반환한다.

    multipart bundle과 Gemini 분석은 후속 공동 계약 task에서 연결한다.
    """
    if not settings.mock_report:
        raise AIServiceError(
            ErrorCode.AI_UNAVAILABLE,
            "Gemini 보고서 생성 기능이 아직 준비되지 않았습니다.",
        )

    return MockReportGenerator(
        sample_report_path=settings.sample_report_path,
        report_schema_path=settings.report_schema_path,
    ).generate()
