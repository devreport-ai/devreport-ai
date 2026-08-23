import json
from pathlib import Path

from app.evaluation import evaluate_document, evaluate_runs

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
                "expected": {
                    "requiredTerms": ["Spring Boot"],
                    "allowedImageFileIds": ["00000000-0000-4000-8000-000000000001"],
                }
            }
        ),
        encoding="utf-8",
    )
    (baseline_dir / "sample.json").write_text(json.dumps(SAMPLE), encoding="utf-8")
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
    assert result["runs"]["baseline"]["average"] == 1.0
