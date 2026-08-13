import json
from pathlib import Path
from typing import Any
from uuid import UUID

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.core.errors import AIServiceError, ErrorCode
from app.main import app
from app.services.mock_report_generator import MockReportGenerator

client = TestClient(app)

REPO_ROOT = Path(__file__).resolve().parents[2]
SAMPLE_REPORT_PATH = REPO_ROOT / "contracts" / "examples" / "sample-report.json"
FILE_ID = UUID("00000000-0000-4000-8000-000000000001")
SOURCE_PATH = f"source/{FILE_ID}/src/App.java"
SOURCE_CONTENT = b"class App {}"
SECOND_FILE_ID = UUID("00000000-0000-4000-8000-000000000002")
SECOND_SOURCE_PATH = f"source/{SECOND_FILE_ID}/src/Service.java"
SECOND_SOURCE_CONTENT = b"class Service {}"


def valid_request() -> dict[str, object]:
    return {
        "fileIds": [str(FILE_ID)],
        "metadata": {"title": "Spring Boot 실습보고서", "author": "김예찬"},
        "instructions": "과제 요구사항에 맞춰 보고서를 작성해 주세요.",
    }


def valid_manifest() -> dict[str, Any]:
    return {
        "version": 1,
        "files": [
            {
                "fileId": str(FILE_ID),
                "category": "source",
                "path": SOURCE_PATH,
                "mimeType": "text/plain",
                "size": len(SOURCE_CONTENT),
            }
        ],
    }


def multipart_data(
    request: dict[str, object] | None = None,
    manifest: dict[str, Any] | None = None,
    source_path: str = SOURCE_PATH,
    source_content: bytes = SOURCE_CONTENT,
) -> list[tuple[str, tuple[str | None, str | bytes, str]]]:
    return [
        ("request", (None, json.dumps(request or valid_request()), "application/json")),
        (
            "manifest",
            ("manifest.json", json.dumps(manifest or valid_manifest()), "application/json"),
        ),
        ("files", (source_path, source_content, "text/plain")),
    ]


def test_generate_returns_schema_valid_sample_report():
    response = client.post("/internal/ai/reports/generate", files=multipart_data())

    assert response.status_code == 200
    assert response.json() == json.loads(SAMPLE_REPORT_PATH.read_text(encoding="utf-8"))


def test_generate_rejects_empty_file_ids_with_common_error_payload():
    request = valid_request() | {"fileIds": []}

    response = client.post("/internal/ai/reports/generate", files=multipart_data(request=request))

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_rejects_metadata_without_title():
    request = valid_request() | {"metadata": {"author": "김예찬"}}

    response = client.post("/internal/ai/reports/generate", files=multipart_data(request=request))

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_rejects_metadata_with_blank_title():
    request = valid_request() | {"metadata": {"title": "   "}}

    response = client.post("/internal/ai/reports/generate", files=multipart_data(request=request))

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_rejects_unknown_metadata_field():
    request = valid_request() | {"metadata": {"title": "보고서", "unknown": "value"}}

    response = client.post("/internal/ai/reports/generate", files=multipart_data(request=request))

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_rejects_metadata_with_invalid_date():
    request = valid_request() | {"metadata": {"title": "보고서", "date": "2026-99-99"}}

    response = client.post("/internal/ai/reports/generate", files=multipart_data(request=request))

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_rejects_blank_instructions_without_echoing_input():
    response = client.post(
        "/internal/ai/reports/generate",
        files=multipart_data(request=valid_request() | {"instructions": "   "}),
    )

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"
    assert "instructions must not be blank" not in response.text


def test_generate_returns_unavailable_when_mock_is_disabled():
    app.dependency_overrides[get_settings] = lambda: Settings(mock_report=False)
    try:
        response = client.post("/internal/ai/reports/generate", files=multipart_data())
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


def test_generate_rejects_manifest_file_count_mismatch():
    response = client.post(
        "/internal/ai/reports/generate",
        files=multipart_data(manifest={"version": 1, "files": []}),
    )

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_rejects_file_order_or_filename_mismatch():
    response = client.post(
        "/internal/ai/reports/generate",
        files=multipart_data(source_path=f"source/{FILE_ID}/src/Other.java"),
    )

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_rejects_files_in_a_different_order_than_manifest():
    manifest = valid_manifest()
    manifest["files"].append(
        {
            "fileId": str(SECOND_FILE_ID),
            "category": "source",
            "path": SECOND_SOURCE_PATH,
            "mimeType": "text/plain",
            "size": len(SECOND_SOURCE_CONTENT),
        }
    )
    request = valid_request() | {"fileIds": [str(FILE_ID), str(SECOND_FILE_ID)]}
    parts = multipart_data(request=request, manifest=manifest)
    parts[2] = ("files", (SECOND_SOURCE_PATH, SECOND_SOURCE_CONTENT, "text/plain"))
    parts.append(("files", (SOURCE_PATH, SOURCE_CONTENT, "text/plain")))

    response = client.post("/internal/ai/reports/generate", files=parts)

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_rejects_manifest_file_id_not_selected_in_request():
    request = valid_request() | {"fileIds": [str(SECOND_FILE_ID)]}

    response = client.post("/internal/ai/reports/generate", files=multipart_data(request=request))

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_rejects_manifest_path_outside_category_root():
    invalid_manifest = valid_manifest()
    invalid_manifest["files"][0]["path"] = "../src/App.java"

    response = client.post(
        "/internal/ai/reports/generate", files=multipart_data(manifest=invalid_manifest)
    )

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_rejects_manifest_total_size_limit():
    oversized_manifest = valid_manifest()
    oversized_manifest["files"][0]["size"] = 100 * 1024 * 1024 + 1

    response = client.post(
        "/internal/ai/reports/generate", files=multipart_data(manifest=oversized_manifest)
    )

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"
