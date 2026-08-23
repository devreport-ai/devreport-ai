from __future__ import annotations

from dataclasses import dataclass

from app.core.config import ANTHROPIC_PROVIDER, GEMINI_PROVIDER, Settings
from app.core.errors import AIServiceError, ErrorCode
from app.schemas.generation import GenerationRequest


@dataclass(frozen=True)
class ModelSelection:
    """이번 생성에 사용할 provider·model과 API Key. api_key는 로그·응답에 싣지 않는다."""

    provider: str
    model: str
    api_key: str | None
    uses_user_key: bool


def resolve_model_selection(
    request: GenerationRequest, settings: Settings, provider_api_key: str | None
) -> ModelSelection:
    """요청의 provider·model과 사용자 키 유무로 실행 구성을 정한다.

    - provider·model이 없으면 서버 기본 Gemini 모델을 사용한다.
    - 사용자 키(X-Provider-Api-Key)가 있으면 allowlist 안의 어떤 모델이든 그 키로 실행한다.
    - 사용자 키가 없으면 서버 키로 서버 기본 Gemini 모델만 실행한다. Claude는 서버 키가 없다.
    """
    provider = request.provider or GEMINI_PROVIDER
    model = request.model or settings.gemini_model
    if model not in allowed_models(provider, settings):
        raise AIServiceError(
            ErrorCode.AI_MODEL_NOT_ALLOWED, "허용되지 않은 provider 또는 모델입니다."
        )

    user_key = normalize_api_key(provider_api_key)
    if user_key is not None:
        return ModelSelection(provider, model, user_key, uses_user_key=True)
    if provider != GEMINI_PROVIDER or model != settings.gemini_model:
        raise AIServiceError(
            ErrorCode.AI_MODEL_NOT_ALLOWED, "서버 키로는 기본 모델만 사용할 수 있습니다."
        )
    return ModelSelection(provider, model, settings.gemini_api_key, uses_user_key=False)


def allowed_models(provider: str, settings: Settings) -> tuple[str, ...]:
    if provider == GEMINI_PROVIDER:
        return settings.gemini_allowed_models
    if provider == ANTHROPIC_PROVIDER:
        return settings.anthropic_allowed_models
    return ()


def normalize_api_key(value: str | None) -> str | None:
    if value is None:
        return None
    stripped = value.strip()
    return stripped or None
