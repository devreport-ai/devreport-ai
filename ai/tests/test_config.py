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


def test_production_validation_error_does_not_echo_secrets():
    with pytest.raises(ValidationError) as raised:
        Settings(
            app_env="prod",
            mock_report=True,
            ai_internal_token="internal-secret",
            gemini_api_key="gemini-secret",
        )

    assert "internal-secret" not in str(raised.value)
    assert "gemini-secret" not in str(raised.value)


def test_uses_free_tier_flash_model_by_default():
    assert Settings().gemini_model == FREE_TIER_GEMINI_MODEL


def test_rejects_paid_model_override():
    with pytest.raises(ValidationError):
        Settings(gemini_model="gemini-2.5-pro")
