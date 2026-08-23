import pytest

from app.core.config import FREE_TIER_GEMINI_MODEL, Settings
from app.core.errors import AIServiceError, ErrorCode
from app.schemas.generation import GenerationRequest
from app.services.model_selection import resolve_model_selection

FILE_ID = "00000000-0000-4000-8000-000000000001"


def request(**overrides: object) -> GenerationRequest:
    payload: dict[str, object] = {
        "fileIds": [FILE_ID],
        "metadata": {"title": "보고서"},
        "instructions": "작성",
    }
    payload.update(overrides)
    return GenerationRequest.model_validate(payload)


def settings() -> Settings:
    return Settings(gemini_api_key="server-key")


def test_uses_server_key_and_default_model_when_nothing_is_selected():
    selection = resolve_model_selection(request(), settings(), None)

    assert selection.model == FREE_TIER_GEMINI_MODEL
    assert selection.api_key == "server-key"
    assert selection.uses_user_key is False


def test_uses_user_key_for_any_allowed_model():
    selection = resolve_model_selection(
        request(provider="GEMINI", model="gemini-3.5-pro"), settings(), "  user-key "
    )

    assert selection.model == "gemini-3.5-pro"
    assert selection.api_key == "user-key"
    assert selection.uses_user_key is True


def test_user_key_overrides_server_key_for_the_default_model():
    selection = resolve_model_selection(request(), settings(), "user-key")

    assert selection.api_key == "user-key"
    assert selection.uses_user_key is True


def test_rejects_non_default_model_without_user_key():
    with pytest.raises(AIServiceError) as raised:
        resolve_model_selection(request(provider="GEMINI", model="gemini-3.5-pro"), settings(), "")

    assert raised.value.code == ErrorCode.AI_MODEL_NOT_ALLOWED


def test_uses_user_key_for_claude():
    selection = resolve_model_selection(
        request(provider="ANTHROPIC", model="claude-sonnet-5"), settings(), "sk-ant-user"
    )

    assert selection.provider == "ANTHROPIC"
    assert selection.model == "claude-sonnet-5"
    assert selection.api_key == "sk-ant-user"
    assert selection.uses_user_key is True


def test_rejects_claude_without_user_key_because_there_is_no_server_key():
    with pytest.raises(AIServiceError) as raised:
        resolve_model_selection(
            request(provider="ANTHROPIC", model="claude-sonnet-5"), settings(), None
        )

    assert raised.value.code == ErrorCode.AI_MODEL_NOT_ALLOWED


@pytest.mark.parametrize(
    ("provider", "model"),
    [("GEMINI", "gemini-9-ultra"), ("ANTHROPIC", "claude-opus-5")],
)
def test_rejects_models_outside_the_allowlist(provider: str, model: str):
    with pytest.raises(AIServiceError) as raised:
        resolve_model_selection(request(provider=provider, model=model), settings(), "user-key")

    assert raised.value.code == ErrorCode.AI_MODEL_NOT_ALLOWED


def test_request_requires_provider_and_model_together():
    with pytest.raises(ValueError):
        request(model="gemini-3.5-pro")
    with pytest.raises(ValueError):
        request(provider="GEMINI")


def test_settings_require_default_model_inside_allowlist():
    with pytest.raises(ValueError):
        Settings(gemini_allowed_models=("gemini-3.5-pro",))


def test_build_client_picks_the_provider_client():
    from app.api.reports import build_client
    from app.clients.claude_client import ClaudeClient
    from app.clients.gemini_client import GeminiClient
    from app.services.model_selection import ModelSelection

    claude = build_client(ModelSelection("ANTHROPIC", "claude-sonnet-5", "sk", True), settings())
    gemini = build_client(ModelSelection("GEMINI", FREE_TIER_GEMINI_MODEL, "k", False), settings())

    assert isinstance(claude, ClaudeClient)
    assert isinstance(gemini, GeminiClient)
