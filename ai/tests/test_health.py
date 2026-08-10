from fastapi.testclient import TestClient

from app.main import app

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
