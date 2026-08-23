import json
from pathlib import Path
from typing import Any
from uuid import UUID

import pytest
from fastapi.testclient import TestClient

from app.api import reports
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
INTERNAL_TOKEN = "test-internal-token"


@pytest.fixture(autouse=True)
def override_settings():
    previous = app.dependency_overrides.get(get_settings)
    app.dependency_overrides[get_settings] = lambda: Settings(ai_internal_token=INTERNAL_TOKEN)
    yield
    if previous is None:
        app.dependency_overrides.pop(get_settings, None)
    else:
        app.dependency_overrides[get_settings] = previous


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


def generate(files: list[tuple[str, tuple[str | None, str | bytes, str]]]):
    return client.post(
        "/internal/ai/reports/generate",
        files=files,
        headers={"X-Internal-Token": INTERNAL_TOKEN},
    )


def test_generate_returns_schema_valid_sample_report():
    response = generate(multipart_data())

    assert response.status_code == 200
    assert response.json() == json.loads(SAMPLE_REPORT_PATH.read_text(encoding="utf-8"))


def test_generate_rejects_empty_file_ids_with_common_error_payload():
    request = valid_request() | {"fileIds": []}

    response = generate(multipart_data(request=request))

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_rejects_metadata_without_title():
    request = valid_request() | {"metadata": {"author": "김예찬"}}

    response = generate(multipart_data(request=request))

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_rejects_metadata_with_blank_title():
    request = valid_request() | {"metadata": {"title": "   "}}

    response = generate(multipart_data(request=request))

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_rejects_unknown_metadata_field():
    request = valid_request() | {"metadata": {"title": "보고서", "unknown": "value"}}

    response = generate(multipart_data(request=request))

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_rejects_metadata_with_invalid_date():
    request = valid_request() | {"metadata": {"title": "보고서", "date": "2026-99-99"}}

    response = generate(multipart_data(request=request))

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_rejects_blank_instructions_without_echoing_input():
    response = generate(multipart_data(request=valid_request() | {"instructions": "   "}))

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"
    assert "instructions must not be blank" not in response.text


def test_generate_returns_unavailable_when_mock_is_disabled():
    previous = app.dependency_overrides.get(get_settings)
    app.dependency_overrides[get_settings] = lambda: Settings(
        mock_report=False, ai_internal_token=INTERNAL_TOKEN
    )
    try:
        response = generate(multipart_data())
    finally:
        if previous is None:
            app.dependency_overrides.pop(get_settings, None)
        else:
            app.dependency_overrides[get_settings] = previous

    assert response.status_code == 503
    assert response.json()["code"] == "AI_UNAVAILABLE"


def test_generate_uses_pipeline_when_mock_is_disabled(monkeypatch: pytest.MonkeyPatch):
    captured: dict[str, object] = {}

    class FakePipeline:
        def __init__(self, gemini: object, report_schema_path: Path) -> None:
            captured["gemini"] = gemini
            captured["report_schema_path"] = report_schema_path

        def generate(self, request: object, context: object) -> dict[str, object]:
            captured["request"] = request
            captured["context"] = context
            return {"metadata": {"title": "실습보고서"}, "sections": []}

    monkeypatch.setattr(reports, "ReportGenerationPipeline", FakePipeline)
    app.dependency_overrides[get_settings] = lambda: Settings(
        mock_report=False, ai_internal_token=INTERNAL_TOKEN, gemini_api_key="test-api-key"
    )
    try:
        response = client.post(
            "/internal/ai/reports/generate",
            files=multipart_data(),
            headers={"X-Internal-Token": INTERNAL_TOKEN},
        )
    finally:
        app.dependency_overrides.pop(get_settings)

    assert response.status_code == 200
    assert response.json() == {"metadata": {"title": "실습보고서"}, "sections": []}
    assert captured["report_schema_path"] == Settings().report_schema_path
    assert captured["request"] is not None
    assert captured["context"] is not None


def test_generate_passes_user_key_and_selected_model_to_the_gemini_client(
    monkeypatch: pytest.MonkeyPatch,
):
    captured: dict[str, object] = {}

    class FakePipeline:
        def __init__(self, gemini: object, report_schema_path: Path) -> None:
            captured["gemini"] = gemini

        def generate(self, request: object, context: object) -> dict[str, object]:
            return {"metadata": {"title": "실습보고서"}, "sections": []}

    monkeypatch.setattr(reports, "ReportGenerationPipeline", FakePipeline)
    app.dependency_overrides[get_settings] = lambda: Settings(
        mock_report=False, ai_internal_token=INTERNAL_TOKEN, gemini_api_key="server-key"
    )
    request = valid_request() | {"provider": "GEMINI", "model": "gemini-3.7-flash"}
    try:
        response = client.post(
            "/internal/ai/reports/generate",
            files=multipart_data(request=request),
            headers={"X-Internal-Token": INTERNAL_TOKEN, "X-Provider-Api-Key": "AIzaUserKey"},
        )
    finally:
        app.dependency_overrides.pop(get_settings)

    assert response.status_code == 200
    gemini = captured["gemini"]
    assert gemini._api_key == "AIzaUserKey"
    assert gemini._model == "gemini-3.7-flash"


def test_generate_rejects_non_default_model_without_user_key():
    request = valid_request() | {"provider": "GEMINI", "model": "gemini-3.7-flash"}
    response = client.post(
        "/internal/ai/reports/generate",
        files=multipart_data(request=request),
        headers={"X-Internal-Token": INTERNAL_TOKEN},
    )

    assert response.status_code == 400
    assert response.json()["code"] == "AI_MODEL_NOT_ALLOWED"


def test_generate_rejects_model_without_provider():
    response = client.post(
        "/internal/ai/reports/generate",
        files=multipart_data(request=valid_request() | {"model": "gemini-3.7-flash"}),
        headers={"X-Internal-Token": INTERNAL_TOKEN},
    )

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


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
    response = generate(multipart_data(manifest={"version": 1, "files": []}))

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_rejects_file_order_or_filename_mismatch():
    response = generate(multipart_data(source_path=f"source/{FILE_ID}/src/Other.java"))

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

    response = generate(parts)

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_rejects_manifest_file_id_not_selected_in_request():
    request = valid_request() | {"fileIds": [str(SECOND_FILE_ID)]}

    response = generate(multipart_data(request=request))

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_rejects_manifest_path_outside_category_root():
    invalid_manifest = valid_manifest()
    invalid_manifest["files"][0]["path"] = "../src/App.java"

    response = generate(multipart_data(manifest=invalid_manifest))

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_rejects_manifest_total_size_limit():
    oversized_manifest = valid_manifest()
    oversized_manifest["files"][0]["size"] = 100 * 1024 * 1024 + 1

    response = generate(multipart_data(manifest=oversized_manifest))

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_rejects_missing_internal_token_without_echoing_secret():
    response = client.post("/internal/ai/reports/generate", files=multipart_data())

    assert response.status_code == 401
    assert response.json()["code"] == "AI_UNAUTHORIZED"
    assert INTERNAL_TOKEN not in response.text


def test_generate_rejects_invalid_internal_token_without_echoing_secret():
    response = client.post(
        "/internal/ai/reports/generate",
        files=multipart_data(),
        headers={"X-Internal-Token": "wrong-token"},
    )

    assert response.status_code == 401
    assert response.json()["code"] == "AI_UNAUTHORIZED"
    assert "wrong-token" not in response.text


def test_generate_rejects_duplicated_file_ids():
    request = valid_request() | {"fileIds": [str(FILE_ID), str(FILE_ID)]}

    response = generate(multipart_data(request=request))

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_rejects_empty_manifest_that_would_produce_a_report_without_evidence():
    empty_manifest = json.dumps({"version": 1, "files": []})
    parts = [
        ("request", (None, json.dumps(valid_request()), "application/json")),
        ("manifest", ("manifest.json", empty_manifest, "application/json")),
    ]

    response = generate(parts)

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_rejects_non_ascii_internal_token_as_unauthorized():
    # 헤더는 latin-1로 디코딩되므로 비ASCII 값이 그대로 들어온다.
    # compare_digest는 이런 문자열에 TypeError를 던져 500이 될 수 있다.
    response = client.post(
        "/internal/ai/reports/generate",
        files=multipart_data(),
        headers={"X-Internal-Token": "토큰".encode()},
    )

    assert response.status_code == 401
    assert response.json()["code"] == "AI_UNAUTHORIZED"


def test_generate_rejects_manifest_with_duplicate_paths():
    # fileId는 ZIP 하나에서 나온 파일들이 공유하므로 중복이 정상이고, path는 유일해야 한다.
    duplicated = valid_manifest()
    duplicated["files"].append(dict(duplicated["files"][0]))
    parts = multipart_data(manifest=duplicated)
    parts.append(("files", (SOURCE_PATH, SOURCE_CONTENT, "text/plain")))

    response = generate(parts)

    assert response.status_code == 400
    assert response.json()["code"] == "AI_INVALID_REQUEST"


def test_generate_accepts_manifest_sharing_one_file_id_across_zip_entries():
    zip_manifest = {
        "version": 1,
        "files": [
            {
                "fileId": str(FILE_ID),
                "category": "source",
                "path": SOURCE_PATH,
                "mimeType": "text/plain",
                "size": len(SOURCE_CONTENT),
            },
            {
                "fileId": str(FILE_ID),
                "category": "source",
                "path": f"source/{FILE_ID}/src/Service.java",
                "mimeType": "text/plain",
                "size": len(SECOND_SOURCE_CONTENT),
            },
        ],
    }
    parts = multipart_data(manifest=zip_manifest)
    parts.append(
        ("files", (f"source/{FILE_ID}/src/Service.java", SECOND_SOURCE_CONTENT, "text/plain"))
    )

    response = generate(parts)

    assert response.status_code == 200
