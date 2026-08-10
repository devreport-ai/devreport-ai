import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import BaseModel, field_validator

from app.core.errors import AIServiceError, ErrorCode, register_exception_handlers
from app.main import app

client = TestClient(app)

COMMON_KEYS = {"code", "message", "details", "timestamp"}
FAKE_KEY = "AIzaSyD-1234567890abcdefghij"


class SampleBody(BaseModel):
    project_id: str
    retries: int
    api_key: str

    @field_validator("api_key")
    @classmethod
    def check_key_format(cls, value: str) -> str:
        # 입력값을 메시지에 끼워 넣는 validator. 이런 문구가 밖으로 나가면 안 된다.
        if not value.startswith("AIza"):
            raise ValueError(f"잘못된 키 형식입니다: {value}")
        return value


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

    @probe.get("/crash")
    def crash() -> None:
        raise RuntimeError(f"gemini 호출 실패 key={FAKE_KEY}")

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

    violations = {v["field"]: v for v in body["details"]["violations"]}
    assert violations["body.project_id"]["message"] == "필수 항목이 누락되었습니다."
    assert violations["body.retries"]["message"] == "정수로 변환할 수 없습니다."


def test_validation_error_does_not_echo_input_value(probe_client: TestClient):
    # Pydantic 기본 오류는 입력값 원문(input)을 포함한다.
    response = probe_client.post(
        "/echo",
        json={"project_id": "p-1", "retries": "many", "api_key": FAKE_KEY},
    )

    assert response.status_code == 422
    assert FAKE_KEY not in response.text


def test_validation_error_does_not_echo_custom_validator_message(probe_client: TestClient):
    # 사용자 정의 validator가 입력값을 메시지에 담아도 응답에는 고정 문구만 나가야 한다.
    response = probe_client.post(
        "/echo",
        json={"project_id": "p-1", "retries": 1, "api_key": "leaked-secret-value"},
    )

    assert response.status_code == 422
    assert "leaked-secret-value" not in response.text
    violations = response.json()["details"]["violations"]
    assert violations[0]["field"] == "body.api_key"
    assert violations[0]["message"] == "값이 올바르지 않습니다."


def test_ai_service_error_maps_to_declared_status(probe_client: TestClient):
    response = probe_client.get("/boom")

    assert response.status_code == 502
    body = response.json()
    assert set(body) == COMMON_KEYS
    assert body["code"] == ErrorCode.AI_RESPONSE_INVALID


def test_unhandled_error_uses_common_error_payload(probe_client: TestClient):
    response = probe_client.get("/crash")

    assert response.status_code == 500
    body = response.json()
    assert set(body) == COMMON_KEYS
    assert body["code"] == ErrorCode.GENERATION_FAILED
    # 예외 원문에 키가 섞여 있어도 응답에는 예외 타입만 남아야 한다.
    assert FAKE_KEY not in response.text
    assert body["details"] == {"exception": "RuntimeError"}
