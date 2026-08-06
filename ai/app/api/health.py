from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.config import APP_VERSION, Settings, get_settings

router = APIRouter(tags=["health"])

SettingsDep = Annotated[Settings, Depends(get_settings)]


@router.get("/health", summary="Health Check")
def health(settings: SettingsDep) -> dict[str, object]:
    """Backend와 배포 환경이 AI Service 상태를 확인하는 엔드포인트.

    contractsFound가 false면 contracts 디렉터리를 못 찾은 것이므로
    보고서 검증이 실패한다. CONTRACTS_DIR을 확인해야 한다.
    """
    return {
        "status": "UP",
        "service": "devreport-ai-service",
        "version": APP_VERSION,
        "env": settings.app_env,
        "mockReport": settings.mock_report,
        "contractsFound": settings.report_schema_path.is_file(),
    }
