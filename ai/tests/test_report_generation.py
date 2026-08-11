import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.core.errors import AIServiceError, ErrorCode
from app.main import app
from app.services.mock_report_generator import MockReportGenerator

client = TestClient(app)

REPO_ROOT = Path(__file__).resolve().parents[2]
SAMPLE_REPORT_PATH = REPO_ROOT / "contracts" / "examples" / "sample-report.json"


def valid_request() -> dict[str, object]:
    return {
        "fileIds": ["00000000-0000-4000-8000-000000000001"],
        "metadata": {"author": "김예찬"},
        "instructions": "과제 요구사항에 맞춰 보고서를 작성해 주세요.",
    }


def test_generate_returns_schema_valid_sample_report():
    response = client.post("/internal/ai/reports/generate", json=valid_request())

    assert response.status_code == 200
    assert response.json() == json.loads(SAMPLE_REPORT_PATH.read_text(encoding="utf-8"))


def test_generate_rejects_empty_file_ids_with_common_error_payload():
    request = valid_request() | {"fileIds": []}

    response = client.post("/internal/ai/reports/generate", json=request)

    assert response.status_code == 422
    assert response.json()["code"] == "AI_INVALID_REQUEST"
    assert response.json()["details"]["violations"][0]["field"] == "body.fileIds"


def test_generate_rejects_blank_instructions_without_echoing_input():
    response = client.post(
        "/internal/ai/reports/generate",
        json=valid_request() | {"instructions": "   "},
    )

    assert response.status_code == 422
    assert response.json()["code"] == "AI_INVALID_REQUEST"
    assert "instructions must not be blank" not in response.text


def test_generate_returns_unavailable_when_mock_is_disabled():
    app.dependency_overrides[get_settings] = lambda: Settings(mock_report=False)
    try:
        response = client.post("/internal/ai/reports/generate", json=valid_request())
    finally:
        app.dependency_overrides.pop(get_settings)

    assert response.status_code == 503
    assert response.json()["code"] == "AI_UNAVAILABLE"


def test_mock_generator_converts_invalid_utf8_contract_file_to_ai_error(tmp_path: Path):
    invalid_sample_path = tmp_path / "sample-report.json"
    invalid_sample_path.write_bytes(b"\xff")

    generator = MockReportGenerator(
        sample_report_path=invalid_sample_path,
        report_schema_path=SAMPLE_REPORT_PATH,
    )

    with pytest.raises(AIServiceError) as raised:
        generator.generate()

    assert raised.value.code == ErrorCode.AI_INVALID_RESPONSE
