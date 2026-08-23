import pytest
from fastapi.testclient import TestClient

from app.api import credentials
from app.core.config import Settings, get_settings
from app.core.errors import AIServiceError, ErrorCode
from app.main import app

client = TestClient(app)
INTERNAL_TOKEN = "test-internal-token"
VERIFY_URL = "/internal/ai/credentials/verify"


def use_settings(**overrides: object) -> None:
    app.dependency_overrides[get_settings] = lambda: Settings(
        ai_internal_token=INTERNAL_TOKEN, **overrides
    )


@pytest.fixture(autouse=True)
def override_settings():
    previous = app.dependency_overrides.get(get_settings)
    use_settings()
    yield
    if previous is None:
        app.dependency_overrides.pop(get_settings, None)
    else:
        app.dependency_overrides[get_settings] = previous


def test_rejects_missing_internal_token_without_touching_the_key():
    response = client.post(
        VERIFY_URL, json={"provider": "GEMINI"}, headers={"X-Provider-Api-Key": "user-key"}
    )

    assert response.status_code == 401
    assert response.json()["code"] == ErrorCode.AI_UNAUTHORIZED
    assert "user-key" not in response.text


def test_rejects_blank_key():
    response = client.post(
        VERIFY_URL,
        json={"provider": "GEMINI"},
        headers={"X-Internal-Token": INTERNAL_TOKEN, "X-Provider-Api-Key": "   "},
    )

    assert response.status_code == 401
    assert response.json()["code"] == ErrorCode.AI_CREDENTIAL_INVALID


def test_rejects_unsupported_provider():
    response = client.post(
        VERIFY_URL,
        json={"provider": "ANTHROPIC"},
        headers={"X-Internal-Token": INTERNAL_TOKEN, "X-Provider-Api-Key": "sk-ant-key"},
    )

    assert response.status_code == 400
    assert response.json()["code"] == ErrorCode.AI_MODEL_NOT_ALLOWED


def test_mock_mode_accepts_keys_without_calling_the_provider():
    response = client.post(
        VERIFY_URL,
        json={"provider": "GEMINI"},
        headers={"X-Internal-Token": INTERNAL_TOKEN, "X-Provider-Api-Key": "AIzaUserKey"},
    )

    assert response.status_code == 200
    assert response.json() == {"valid": True, "provider": "GEMINI"}
    assert "AIzaUserKey" not in response.text


def test_mock_mode_rejects_invalid_prefixed_keys():
    response = client.post(
        VERIFY_URL,
        json={"provider": "GEMINI"},
        headers={"X-Internal-Token": INTERNAL_TOKEN, "X-Provider-Api-Key": "invalid-key"},
    )

    assert response.status_code == 401
    assert response.json()["code"] == ErrorCode.AI_CREDENTIAL_INVALID


def test_calls_gemini_verifier_when_mock_is_disabled(monkeypatch: pytest.MonkeyPatch):
    captured: dict[str, object] = {}

    def fake_verify(api_key: str, timeout_seconds: float) -> None:
        captured["api_key"] = api_key
        captured["timeout"] = timeout_seconds

    monkeypatch.setattr(credentials, "verify_gemini_api_key", fake_verify)
    use_settings(
        mock_report=False, gemini_api_key="server-key", credential_verify_timeout_seconds=3
    )

    response = client.post(
        VERIFY_URL,
        json={"provider": "GEMINI"},
        headers={"X-Internal-Token": INTERNAL_TOKEN, "X-Provider-Api-Key": "AIzaUserKey"},
    )

    assert response.status_code == 200
    assert captured == {"api_key": "AIzaUserKey", "timeout": 3}


def test_converts_verifier_failure_to_error_payload(monkeypatch: pytest.MonkeyPatch):
    def failing_verify(api_key: str, timeout_seconds: float) -> None:
        raise AIServiceError(ErrorCode.AI_CREDENTIAL_INVALID, "Gemini API Key가 올바르지 않습니다.")

    monkeypatch.setattr(credentials, "verify_gemini_api_key", failing_verify)
    use_settings(mock_report=False, gemini_api_key="server-key")

    response = client.post(
        VERIFY_URL,
        json={"provider": "GEMINI"},
        headers={"X-Internal-Token": INTERNAL_TOKEN, "X-Provider-Api-Key": "AIzaUserKey"},
    )

    assert response.status_code == 401
    assert response.json()["code"] == ErrorCode.AI_CREDENTIAL_INVALID
    assert "AIzaUserKey" not in response.text
