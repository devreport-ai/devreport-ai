import json
from pathlib import Path

from app.evaluation import evaluate_document, evaluate_gate, evaluate_runs
from app.evaluation_runner import context_from_fixture, request_from_fixture

REPO_ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((REPO_ROOT / "contracts/report-document.schema.json").read_text())
SAMPLE = json.loads((REPO_ROOT / "contracts/examples/sample-report.json").read_text())


def test_evaluates_schema_terms_evidence_specificity_and_readability():
    expected = {
        "requiredTerms": ["Spring Boot", "JWT"],
        "forbiddenTerms": ["결제"],
        "allowedImageFileIds": ["00000000-0000-4000-8000-000000000001"],
        "minNonEmptyBlocks": 4,
        "requiredBlockTypes": ["code", "table"],
        "maxAverageSentenceWords": 40,
    }

    result = evaluate_document(SAMPLE, expected, SCHEMA)

    assert result["overall"] == 1.0
    assert all(score == 1.0 for score in result["scores"].values())


def test_reports_hallucinated_image_reference():
    document = json.loads(json.dumps(SAMPLE))
    document["sections"][0]["blocks"][4]["fileId"] = "00000000-0000-4000-8000-000000000099"

    result = evaluate_document(
        document,
        {"allowedImageFileIds": ["00000000-0000-4000-8000-000000000001"]},
        SCHEMA,
    )

    assert result["scores"]["evidenceConsistency"] == 0.0
    assert result["details"]["invalidImageFileIds"] == ["00000000-0000-4000-8000-000000000099"]


def test_requires_requirement_and_implementation_evidence_in_the_same_section():
    expected = {
        "requiredTerms": ["Spring Boot"],
        "requirements": [
            {
                "id": "req-api",
                "status": "satisfied",
                "requirementTerms": ["Spring Boot"],
                "evidenceTerms": ["ProjectController", "PostMapping"],
            }
        ],
    }

    sources = ["class ProjectController { @PostMapping void create() {} }"]
    abstract = evaluate_document(SAMPLE, expected, SCHEMA, sources)
    concrete_document = json.loads(json.dumps(SAMPLE))
    concrete_document["sections"][0]["blocks"][0]["content"] += (
        " Spring Boot 요구사항은 ProjectController의 PostMapping 구현으로 충족했다."
    )
    concrete = evaluate_document(concrete_document, expected, SCHEMA, sources)
    unsupported = evaluate_document(concrete_document, expected, SCHEMA, ["class Other {}"])

    assert abstract["scores"]["requirementsSatisfaction"] == 1.0
    assert abstract["scores"]["requirementEvidenceLinkage"] == 0.0
    assert concrete["scores"]["requirementEvidenceLinkage"] == 1.0
    assert unsupported["scores"]["requirementEvidenceLinkage"] == 0.0


def test_reports_unverified_requirement_claim_and_invented_code():
    expected = {
        "requirements": [
            {
                "id": "req-jwt",
                "status": "unverified",
                "requirementTerms": ["JWT"],
            }
        ]
    }

    unsupported = evaluate_document(SAMPLE, expected, SCHEMA, ["class Real {}"])

    assert unsupported["details"]["unsupportedClaims"] == ["req-jwt"]
    assert unsupported["details"]["invalidCodeQuotes"] == [
        "@SpringBootApplication\npublic class BackendApplication {}"
    ]


def test_compares_result_directories_for_the_same_fixtures(tmp_path: Path):
    fixture_dir = tmp_path / "fixtures"
    baseline_dir = tmp_path / "baseline"
    improved_dir = tmp_path / "improved"
    fixture_dir.mkdir()
    baseline_dir.mkdir()
    improved_dir.mkdir()
    (fixture_dir / "sample.json").write_text(
        json.dumps(
            {
                "sourceFiles": [
                    {"content": "@SpringBootApplication\npublic class BackendApplication {}"}
                ],
                "expected": {
                    "requiredTerms": ["Spring Boot"],
                    "allowedImageFileIds": ["00000000-0000-4000-8000-000000000001"],
                },
            }
        ),
        encoding="utf-8",
    )
    baseline = json.loads(json.dumps(SAMPLE))
    baseline["sections"][0]["blocks"][2]["code"] = "public class Invented {}"
    (baseline_dir / "sample.json").write_text(json.dumps(baseline), encoding="utf-8")
    (improved_dir / "sample.json").write_text(
        json.dumps(
            {
                "run": {"provider": "gemini", "model": "test", "promptVersion": "v3"},
                "document": SAMPLE,
            }
        ),
        encoding="utf-8",
    )

    result = evaluate_runs(
        fixture_dir,
        [f"baseline={baseline_dir}", f"improved={improved_dir}"],
        REPO_ROOT / "contracts/report-document.schema.json",
    )

    assert set(result["runs"]) == {"baseline", "improved"}
    assert result["runs"]["improved"]["metadata"]["promptVersion"] == "v3"
    assert result["runs"]["baseline"]["average"] < result["runs"]["improved"]["average"]
    assert result["runs"]["baseline"]["fixtures"]["sample"]["details"]["invalidCodeQuotes"] == [
        "public class Invented {}"
    ]


def test_builds_generation_input_from_fixture(tmp_path: Path):
    image_path = tmp_path / "dashboard.png"
    image_path.write_bytes(b"png")
    fixture = {
        "request": {"metadata": {"title": "보고서"}, "instructions": "근거를 설명한다."},
        "documents": [
            {
                "fileId": "10000000-0000-4000-8000-000000000001",
                "path": "documents/id/a.md",
                "mimeType": "text/markdown",
                "content": "요구사항",
            }
        ],
        "sourceFiles": [],
        "images": [
            {
                "fileId": "10000000-0000-4000-8000-000000000002",
                "path": "images/id/dashboard.png",
                "mimeType": "image/png",
                "assetPath": "dashboard.png",
            }
        ],
    }

    context = context_from_fixture(fixture, tmp_path / "fixture.json")
    request = request_from_fixture(fixture)

    assert context.documents[0].content == "요구사항"
    assert context.images[0].content == b"png"
    assert len(request.file_ids) == 2


def test_quality_gate_fails_with_a_distinct_semantic_regression_reason(tmp_path: Path):
    fixture_dir = tmp_path / "fixtures"
    baseline_dir = tmp_path / "baseline"
    candidate_dir = tmp_path / "candidate"
    fixture_dir.mkdir()
    baseline_dir.mkdir()
    candidate_dir.mkdir()
    criterion = "구현 근거가 연결되는가"
    fixture = {
        "sourceFiles": [{"content": "@SpringBootApplication\npublic class BackendApplication {}"}],
        "expected": {
            "requirements": [
                {
                    "id": "req-api",
                    "status": "satisfied",
                    "requirementTerms": ["Spring Boot"],
                    "evidenceTerms": ["ProjectController"],
                }
            ],
            "manualReview": [criterion],
        },
    }
    (fixture_dir / "sample.json").write_text(json.dumps(fixture), encoding="utf-8")
    metadata = {
        "provider": "gemini",
        "model": "test",
        "promptVersion": "v3",
        "generatedAt": "2026-08-23T00:00:00+00:00",
    }
    review = {
        "reviewedBy": "reviewer",
        "reviewedAt": "2026-08-23",
        "checks": [{"criterion": criterion, "passed": True, "notes": "확인"}],
    }
    baseline = json.loads(json.dumps(SAMPLE))
    baseline["sections"][0]["blocks"][0]["content"] += " ProjectController Spring Boot"
    (baseline_dir / "sample.json").write_text(
        json.dumps({"run": metadata, "document": baseline, "manualReview": review}),
        encoding="utf-8",
    )
    (candidate_dir / "sample.json").write_text(
        json.dumps({"run": metadata, "document": SAMPLE, "manualReview": review}),
        encoding="utf-8",
    )
    config = {
        "baseline": {"label": "baseline", "directory": "baseline"},
        "candidate": {"label": "candidate", "directory": "candidate"},
        "minimumScores": {"requirementEvidenceLinkage": 1.0},
        "maxScoreRegression": 0.0,
        "requireCompletedManualReview": True,
    }
    config_path = tmp_path / "quality-gate.json"
    config_path.write_text(json.dumps(config), encoding="utf-8")

    result = evaluate_gate(
        fixture_dir, REPO_ROOT / "contracts/report-document.schema.json", config_path
    )

    assert result["gate"]["passed"] is False
    assert any("requirementEvidenceLinkage" in failure for failure in result["gate"]["failures"])


def test_quality_gate_rejects_missing_baseline_fixture_and_self_comparison(tmp_path: Path):
    fixture_dir = tmp_path / "fixtures"
    baseline_dir = tmp_path / "baseline"
    candidate_dir = tmp_path / "candidate"
    fixture_dir.mkdir()
    baseline_dir.mkdir()
    candidate_dir.mkdir()
    (fixture_dir / "sample.json").write_text(json.dumps({}), encoding="utf-8")
    (candidate_dir / "sample.json").write_text(json.dumps(SAMPLE), encoding="utf-8")
    config_path = tmp_path / "quality-gate.json"
    config = {
        "baseline": {"label": "baseline", "directory": "baseline"},
        "candidate": {"label": "candidate", "directory": "candidate"},
    }
    config_path.write_text(json.dumps(config), encoding="utf-8")

    missing = evaluate_gate(
        fixture_dir, REPO_ROOT / "contracts/report-document.schema.json", config_path
    )
    config["candidate"]["directory"] = "baseline"
    config_path.write_text(json.dumps(config), encoding="utf-8")
    same = evaluate_gate(
        fixture_dir, REPO_ROOT / "contracts/report-document.schema.json", config_path
    )

    assert any("baseline/sample" in failure for failure in missing["gate"]["failures"])
    assert "baseline과 candidate 결과 디렉터리는 달라야 함" in same["gate"]["failures"]
