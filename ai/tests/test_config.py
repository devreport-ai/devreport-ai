import pytest
from pydantic import ValidationError

from app.core.config import FREE_TIER_GEMINI_MODEL, Settings


@pytest.mark.parametrize("missing", ["ai_internal_token", "gemini_api_key"])
def test_production_requires_secrets(missing: str):
    values = {
        "app_env": "prod",
        "mock_report": False,
        "ai_internal_token": "internal-token",
        "gemini_api_key": "gemini-key",
    }
    values[missing] = None

    with pytest.raises(ValidationError):
        Settings(**values)


def test_production_disables_mock_report():
    with pytest.raises(ValidationError):
        Settings(
            app_env="production",
            mock_report=True,
            ai_internal_token="internal-token",
            gemini_api_key="gemini-key",
        )


def test_validation_error_does_not_echo_secret_input():
    secret = "internal-secret"

    with pytest.raises(ValidationError) as raised:
        Settings(app_env=secret)

    assert secret not in str(raised.value)


@pytest.mark.parametrize("environment", ["", "prd"])
def test_rejects_blank_or_unknown_environment(environment: str):
    with pytest.raises(ValidationError):
        Settings(app_env=environment)


def test_uses_free_tier_flash_model_by_default():
    assert Settings().gemini_model == FREE_TIER_GEMINI_MODEL


def test_rejects_paid_model_override():
    with pytest.raises(ValidationError):
        Settings(gemini_model="gemini-2.5-pro")


def test_rejects_generation_deadline_shorter_than_a_single_call_timeout():
    with pytest.raises(ValidationError):
        Settings(gemini_timeout_seconds=120, generation_deadline_seconds=60)


def test_keeps_the_generation_deadline_below_the_backend_response_timeout():
    settings = Settings()

    # Backend AI_SERVICE_RESPONSE_TIMEOUT 기본값 300초보다 짧아야
    # Backend가 포기한 뒤에도 Gemini 호출이 남아 있는 상황을 막는다.
    assert settings.generation_deadline_seconds < 300
    assert settings.gemini_timeout_seconds <= settings.generation_deadline_seconds
