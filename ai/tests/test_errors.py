import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import BaseModel

from app.core.errors import AIServiceError, ErrorCode, register_exception_handlers
from app.main import app

client = TestClient(app)

COMMON_KEYS = {"code", "message", "details", "timestamp"}


class SampleBody(BaseModel):
    project_id: str
    retries: int
    api_key: str


@pytest.fixture
def probe_client() -> TestClient:
    """공통 에러 규격을 검증하기 위한 최소 앱."""
    probe = FastAPI()
    register_exception_handlers(probe)

    @probe.post("/echo")
    def echo(body: SampleBody) -> dict[str, str]:
        return {"projectId": body.project_id}

    @probe.get("/boom")
    def boom() -> None:
        raise AIServiceError(ErrorCode.AI_RESPONSE_INVALID, "AI 응답 형식이 올바르지 않습니다.")

    return TestClient(probe, raise_server_exceptions=False)


def test_unknown_path_uses_common_error_payload():
    response = client.get("/not-exist")

    assert response.status_code == 404
    body = response.json()
    assert set(body) == COMMON_KEYS
    assert body["code"] == ErrorCode.INVALID_REQUEST


def test_wrong_method_uses_common_error_payload():
    response = client.post("/health")

    assert response.status_code == 405
    body = response.json()
    assert set(body) == COMMON_KEYS
    assert body["code"] == ErrorCode.INVALID_REQUEST
    # Starlette 기본 동작인 Allow 헤더를 잃지 않아야 한다.
    assert "allow" in {k.lower() for k in response.headers}


def test_validation_error_uses_common_error_payload(probe_client: TestClient):
    response = probe_client.post("/echo", json={"retries": "many"})

    assert response.status_code == 422
    body = response.json()
    assert set(body) == COMMON_KEYS
    assert body["code"] == ErrorCode.INVALID_REQUEST
    fields = {violation["field"] for violation in body["details"]["violations"]}
    assert "body.project_id" in fields
    assert "body.retries" in fields


def test_validation_error_does_not_echo_api_key(probe_client: TestClient):
    # Pydantic 기본 오류는 입력값 원문을 포함한다. 키가 새어나가면 안 된다.
    response = probe_client.post(
        "/echo",
        json={"project_id": "p-1", "retries": "many", "api_key": "AIzaSyD-1234567890abcdefghij"},
    )

    assert response.status_code == 422
    assert "AIzaSyD-1234567890abcdefghij" not in response.text


def test_ai_service_error_maps_to_declared_status(probe_client: TestClient):
    response = probe_client.get("/boom")

    assert response.status_code == 502
    body = response.json()
    assert set(body) == COMMON_KEYS
    assert body["code"] == ErrorCode.AI_RESPONSE_INVALID
