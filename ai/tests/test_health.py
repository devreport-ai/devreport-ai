from fastapi.testclient import TestClient

from app.core.config import Settings
from app.main import app, create_app

client = TestClient(app)


def test_health_returns_up():
    response = client.get("/health")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UP"
    assert body["service"] == "devreport-ai-service"


def test_health_finds_contracts_directory():
    # contracts 스키마를 못 찾으면 이후 ReportDocument 검증이 전부 실패하므로
    # 경로 설정이 깨졌는지 여기서 먼저 걸러낸다.
    assert client.get("/health").json()["contractsFound"] is True


def test_production_hides_api_docs(monkeypatch):
    monkeypatch.setattr(
        "app.main.get_settings",
        lambda: Settings(
            app_env="prod",
            mock_report=False,
            ai_internal_token="internal-token",
            gemini_api_key="gemini-key",
        ),
    )
    production_client = TestClient(create_app())

    for path in ("/docs", "/redoc", "/openapi.json"):
        assert production_client.get(path).status_code == 404
