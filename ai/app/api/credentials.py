import logging
from typing import Annotated, Any, Literal

from fastapi import APIRouter, Depends, Header
from pydantic import BaseModel, ConfigDict
from starlette.concurrency import run_in_threadpool

from app.api.reports import INTERNAL_TOKEN_HEADER, PROVIDER_API_KEY_HEADER, is_authorized
from app.clients.gemini_client import verify_gemini_api_key
from app.core.config import GEMINI_PROVIDER, Settings, get_settings
from app.core.errors import AIServiceError, ErrorCode
from app.services.model_selection import normalize_api_key

router = APIRouter(prefix="/internal/ai/credentials", tags=["internal credentials"])
log = logging.getLogger(__name__)
SettingsDep = Annotated[Settings, Depends(get_settings)]


class CredentialVerifyRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    provider: Literal["GEMINI", "ANTHROPIC"]


@router.post("/verify", summary="Verify a user-provided provider API Key")
async def verify_credential(
    body: CredentialVerifyRequest,
    settings: SettingsDep,
    internal_token: Annotated[str | None, Header(alias=INTERNAL_TOKEN_HEADER)] = None,
    provider_api_key: Annotated[str | None, Header(alias=PROVIDER_API_KEY_HEADER)] = None,
) -> dict[str, Any]:
    """Backend가 사용자 API Key를 저장하기 전에 provider API로 유효성을 확인한다.

    키는 헤더로만 받고 응답·로그에 싣지 않는다.
    """
    if not is_authorized(internal_token, settings.ai_internal_token):
        raise AIServiceError(ErrorCode.AI_UNAUTHORIZED, "내부 요청 인증에 실패했습니다.")

    api_key = normalize_api_key(provider_api_key)
    if api_key is None:
        raise AIServiceError(ErrorCode.AI_CREDENTIAL_INVALID, "API Key가 비어 있습니다.")
    if body.provider != GEMINI_PROVIDER:
        raise AIServiceError(ErrorCode.AI_MODEL_NOT_ALLOWED, "지원하지 않는 provider입니다.")

    if settings.mock_report:
        # Mock 모드는 provider를 호출하지 않는다. 실패 흐름 확인용으로 "invalid" 접두사만 거부한다.
        if api_key.startswith("invalid"):
            raise AIServiceError(ErrorCode.AI_CREDENTIAL_INVALID, "API Key가 올바르지 않습니다.")
    else:
        await run_in_threadpool(
            verify_gemini_api_key,
            api_key,
            settings.credential_verify_timeout_seconds,
        )
    log.info("API Key 검증 완료 provider=%s", body.provider)
    return {"valid": True, "provider": body.provider}
