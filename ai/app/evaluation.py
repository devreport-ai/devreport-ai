"""고정 fixture로 생성 결과의 구조·근거·내용 품질을 비교한다."""

from __future__ import annotations

import argparse
import json
import re
from collections.abc import Sequence
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator, FormatChecker
from jsonschema.exceptions import SchemaError, ValidationError

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_FIXTURE_DIR = Path(__file__).resolve().parents[1] / "evals" / "fixtures"
DEFAULT_SCHEMA_PATH = REPO_ROOT / "contracts" / "report-document.schema.json"
WORD_PATTERN = re.compile(r"[A-Za-z0-9가-힣]+")
SENTENCE_SEPARATOR = re.compile(r"[.!?。！？]+")


def evaluate_document(
    document: dict[str, Any],
    expected: dict[str, Any],
    schema: dict[str, Any],
    source_snippets: Sequence[str] | None = None,
) -> dict[str, Any]:
    """한 fixture의 생성 결과를 구조와 의미 품질 지표로 평가한다."""
    schema_error = validate_schema(document, schema)
    required_terms = expected.get("requiredTerms", [])
    forbidden_terms = expected.get("forbiddenTerms", [])
    serialized = json.dumps(document, ensure_ascii=False).casefold()
    missing_terms = [term for term in required_terms if term.casefold() not in serialized]
    forbidden_matches = [term for term in forbidden_terms if term.casefold() in serialized]

    allowed_image_ids = set(expected.get("allowedImageFileIds", []))
    image_ids = set(image_file_ids(document))
    invalid_image_ids = sorted(image_ids - allowed_image_ids)
    invalid_code_quotes = (
        unmatched_code_quotes(document, source_snippets) if source_snippets is not None else []
    )
    requirement_links = evaluate_requirement_links(
        document, expected.get("requirements", []), source_snippets or []
    )

    block_types = document_block_types(document)
    non_empty_blocks = count_non_empty_blocks(document)
    required_block_types = set(expected.get("requiredBlockTypes", []))
    missing_block_types = sorted(required_block_types - block_types)
    specificity_checks = [
        non_empty_blocks >= expected.get("minNonEmptyBlocks", 1),
        not missing_block_types,
    ]

    average_sentence_words = average_sentence_word_count(document)
    max_sentence_words = expected.get("maxAverageSentenceWords", 40)
    readability_checks = [
        average_sentence_words > 0,
        average_sentence_words <= max_sentence_words,
        empty_content_block_count(document) == 0,
    ]

    scores = {
        "requirementsSatisfaction": fraction(
            len(required_terms) - len(missing_terms), len(required_terms)
        ),
        "requirementEvidenceLinkage": fraction(
            sum(item["passed"] for item in requirement_links), len(requirement_links)
        ),
        "evidenceConsistency": 1.0
        if not invalid_image_ids and not invalid_code_quotes and not forbidden_matches
        else 0.0,
        "specificity": fraction(sum(specificity_checks), len(specificity_checks)),
        "readability": fraction(sum(readability_checks), len(readability_checks)),
        "schemaValidity": 0.0 if schema_error else 1.0,
    }
    return {
        "scores": scores,
        "overall": round(sum(scores.values()) / len(scores), 3),
        "details": {
            "missingRequiredTerms": missing_terms,
            "forbiddenTerms": forbidden_matches,
            "invalidImageFileIds": invalid_image_ids,
            "invalidCodeQuotes": invalid_code_quotes,
            "requirementLinks": requirement_links,
            "unsupportedClaims": [
                item["id"] for item in requirement_links if item["actualStatus"] == "unsupported"
            ],
            "missingBlockTypes": missing_block_types,
            "nonEmptyBlocks": non_empty_blocks,
            "blockTypes": sorted(block_types),
            "averageSentenceWords": round(average_sentence_words, 2),
            "schemaError": schema_error,
        },
    }


def validate_schema(document: dict[str, Any], schema: dict[str, Any]) -> str | None:
    try:
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(document)
    except (SchemaError, ValidationError, TypeError) as exception:
        return type(exception).__name__
    return None


def image_file_ids(document: dict[str, Any]) -> list[str]:
    return [
        block["fileId"]
        for section in document.get("sections", [])
        for block in section.get("blocks", [])
        if block.get("type") == "image" and isinstance(block.get("fileId"), str)
    ]


def unmatched_code_quotes(document: dict[str, Any], source_snippets: Sequence[str]) -> list[str]:
    normalized_sources = tuple(normalize_code(source) for source in source_snippets)
    return [
        code
        for section in document.get("sections", [])
        for block in section.get("blocks", [])
        if block.get("type") == "code"
        and isinstance((code := block.get("code")), str)
        and normalize_code(code)
        and not any(normalize_code(code) in source for source in normalized_sources)
    ]


def normalize_code(value: str) -> str:
    return value.replace("\r\n", "\n").replace("\r", "\n").strip()


def evaluate_requirement_links(
    document: dict[str, Any],
    requirements: Sequence[dict[str, Any]],
    source_snippets: Sequence[str],
) -> list[dict[str, Any]]:
    """같은 섹션 안의 요구사항 표현과 구현 근거 또는 미확인 표현을 연결한다."""
    sections = [
        json.dumps(section, ensure_ascii=False).casefold()
        for section in document.get("sections", [])
    ]
    sources = [source.casefold() for source in source_snippets]
    results: list[dict[str, Any]] = []
    for requirement in requirements:
        requirement_terms = requirement.get("requirementTerms", [])
        evidence_terms = requirement.get("evidenceTerms", [])
        mentioned = [
            index
            for index, section in enumerate(sections)
            if contains_all(section, requirement_terms)
        ]
        expected_status = requirement.get("status", "satisfied")
        if expected_status == "unverified":
            uncertainty_terms = requirement.get(
                "uncertaintyTerms", ["미확인", "확인할 수 없", "근거가 없", "확인되지 않"]
            )
            matched = next(
                (
                    index
                    for index in mentioned
                    if any(term.casefold() in sections[index] for term in uncertainty_terms)
                ),
                None,
            )
            actual_status = "unverified" if matched is not None else "unsupported"
        else:
            source_supported = any(contains_all(source, evidence_terms) for source in sources)
            matched = next(
                (
                    index
                    for index in mentioned
                    if source_supported and contains_all(sections[index], evidence_terms)
                ),
                None,
            )
            actual_status = "satisfied" if matched is not None else "missing-evidence"
        if not mentioned:
            actual_status = "missing"
        results.append(
            {
                "id": requirement.get("id", "unknown"),
                "expectedStatus": expected_status,
                "actualStatus": actual_status,
                "sectionIndex": matched,
                "passed": actual_status == expected_status,
            }
        )
    return results


def contains_all(text: str, terms: Sequence[str]) -> bool:
    return bool(terms) and all(term.casefold() in text for term in terms)


def document_block_types(document: dict[str, Any]) -> set[str]:
    return {
        block.get("type")
        for section in document.get("sections", [])
        for block in section.get("blocks", [])
        if isinstance(block.get("type"), str)
    }


def count_non_empty_blocks(document: dict[str, Any]) -> int:
    return sum(
        1
        for section in document.get("sections", [])
        for block in section.get("blocks", [])
        if block.get("type") != "pageBreak" and block_has_content(block)
    )


def empty_content_block_count(document: dict[str, Any]) -> int:
    return sum(
        1
        for section in document.get("sections", [])
        for block in section.get("blocks", [])
        if block.get("type") != "pageBreak" and not block_has_content(block)
    )


def block_has_content(block: dict[str, Any]) -> bool:
    values = [
        block.get("content"),
        block.get("code"),
        block.get("items"),
        block.get("rows"),
        block.get("alt"),
        block.get("caption"),
    ]
    return any(value for value in values)


def average_sentence_word_count(document: dict[str, Any]) -> float:
    sentences = [
        sentence.strip()
        for sentence in SENTENCE_SEPARATOR.split(" ".join(document_text(document)))
        if sentence.strip()
    ]
    if not sentences:
        return 0.0
    return sum(len(WORD_PATTERN.findall(sentence)) for sentence in sentences) / len(sentences)


def document_text(document: dict[str, Any]) -> list[str]:
    values: list[str] = []
    for section in document.get("sections", []):
        values.append(str(section.get("title", "")))
        for block in section.get("blocks", []):
            for key in ("content", "code", "alt", "caption"):
                if isinstance(block.get(key), str):
                    values.append(block[key])
            for key in ("items", "columns", "rows"):
                value = block.get(key)
                if isinstance(value, list):
                    values.extend(flatten_strings(value))
    return values


def flatten_strings(value: list[Any]) -> list[str]:
    result: list[str] = []
    for item in value:
        if isinstance(item, str):
            result.append(item)
        elif isinstance(item, list):
            result.extend(flatten_strings(item))
    return result


def fraction(numerator: int, denominator: int) -> float:
    return numerator / denominator if denominator else 1.0


def load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as source:
        value = json.load(source)
    if not isinstance(value, dict):
        raise ValueError(f"JSON object required: {path}")
    return value


def load_result(path: Path) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    value = load_json(path)
    document = value.get("document", value)
    if not isinstance(document, dict):
        raise ValueError(f"ReportDocument object required: {path}")
    metadata = value.get("run", {})
    manual_review = value.get("manualReview", {})
    return (
        document,
        metadata if isinstance(metadata, dict) else {},
        manual_review if isinstance(manual_review, dict) else {},
    )


def parse_run(value: str) -> tuple[str, Path]:
    label, separator, directory = value.partition("=")
    if not separator or not label or not directory:
        raise ValueError("--run 형식은 label=directory여야 합니다.")
    return label, Path(directory)


def evaluate_runs(
    fixture_dir: Path, result_runs: Sequence[str], schema_path: Path
) -> dict[str, Any]:
    schema = load_json(schema_path)
    fixtures = {path.stem: load_json(path) for path in sorted(fixture_dir.glob("*.json"))}
    if not fixtures:
        raise ValueError(f"fixture가 없습니다: {fixture_dir}")

    runs: dict[str, Any] = {}
    for run_spec in result_runs:
        label, result_dir = parse_run(run_spec)
        fixture_results: dict[str, Any] = {}
        for fixture_id, fixture in fixtures.items():
            result_path = result_dir / f"{fixture_id}.json"
            if not result_path.exists():
                fixture_results[fixture_id] = {"error": f"결과 파일이 없습니다: {result_path}"}
                continue
            document, metadata, manual_review = load_result(result_path)
            fixture_results[fixture_id] = evaluate_document(
                document,
                fixture.get("expected", {}),
                schema,
                [source.get("content", "") for source in fixture.get("sourceFiles", [])],
            ) | {
                "run": metadata,
                "manualReview": evaluate_manual_review(
                    fixture.get("expected", {}).get("manualReview", []), manual_review
                ),
            }
        valid_results = [result for result in fixture_results.values() if "scores" in result]
        score_names = {name for result in valid_results for name in result["scores"]}
        manual_passed = sum(result["manualReview"]["passed"] for result in valid_results)
        manual_total = sum(result["manualReview"]["total"] for result in valid_results)
        runs[label] = {
            "metadata": next((result["run"] for result in valid_results if result.get("run")), {}),
            "fixtures": fixture_results,
            "average": round(
                sum(result["overall"] for result in valid_results) / len(valid_results), 3
            )
            if valid_results
            else 0.0,
            "scoreAverages": {
                name: round(
                    sum(result["scores"].get(name, 0.0) for result in valid_results)
                    / len(valid_results),
                    3,
                )
                for name in sorted(score_names)
            }
            if valid_results
            else {},
            "manualReviewPassRate": round(fraction(manual_passed, manual_total), 3),
        }
    return {"fixtures": sorted(fixtures), "runs": runs}


def evaluate_manual_review(criteria: Sequence[str], review: dict[str, Any]) -> dict[str, Any]:
    checks = review.get("checks", [])
    recorded = {
        check.get("criterion"): check
        for check in checks
        if isinstance(check, dict) and isinstance(check.get("criterion"), str)
    }
    missing = [criterion for criterion in criteria if criterion not in recorded]
    unanswered = [
        criterion
        for criterion in criteria
        if criterion in recorded and not isinstance(recorded[criterion].get("passed"), bool)
    ]
    passed = sum(recorded[item].get("passed") is True for item in criteria if item in recorded)
    return {
        "reviewedBy": review.get("reviewedBy"),
        "reviewedAt": review.get("reviewedAt"),
        "completed": bool(criteria)
        and not missing
        and not unanswered
        and bool(review.get("reviewedBy"))
        and bool(review.get("reviewedAt")),
        "passed": passed,
        "total": len(criteria),
        "missingCriteria": missing,
        "unansweredCriteria": unanswered,
    }


def evaluate_gate(fixture_dir: Path, schema_path: Path, config_path: Path) -> dict[str, Any]:
    config = load_json(config_path)
    baseline_spec = gate_run_spec(config_path, config["baseline"])
    candidate_spec = gate_run_spec(config_path, config.get("candidate", config["baseline"]))
    evaluation = evaluate_runs(fixture_dir, [baseline_spec, candidate_spec], schema_path)
    baseline = evaluation["runs"][config["baseline"]["label"]]
    candidate = evaluation["runs"][config.get("candidate", config["baseline"])["label"]]
    failures: list[str] = []
    comparison_ready = baseline_spec.partition("=")[2] != candidate_spec.partition("=")[2]
    if not comparison_ready:
        failures.append("baseline과 candidate 결과 디렉터리는 달라야 함")

    for run_label, run in (("baseline", baseline), ("candidate", candidate)):
        for fixture_id, result in run["fixtures"].items():
            if "error" in result:
                failures.append(f"{run_label}/{fixture_id}: {result['error']}")
                comparison_ready = False

    for fixture_id, result in candidate["fixtures"].items():
        if "error" in result:
            continue
        missing_metadata = [
            key
            for key in ("provider", "model", "promptVersion", "generatedAt")
            if not result["run"].get(key)
        ]
        if missing_metadata:
            failures.append(f"{fixture_id}: 실행 메타데이터 누락 {', '.join(missing_metadata)}")
        if config.get("requireCompletedManualReview") and not result["manualReview"]["completed"]:
            failures.append(f"{fixture_id}: 수동 평가가 완료되지 않음")

    for name, minimum in config.get("minimumScores", {}).items():
        actual = candidate["scoreAverages"].get(name, 0.0)
        if actual < minimum:
            failures.append(f"{name}: 최소 점수 {minimum} 미달 ({actual})")

    minimum_manual_rate = config.get("minimumManualReviewPassRate", 0.0)
    if candidate["manualReviewPassRate"] < minimum_manual_rate:
        failures.append(
            "manualReviewPassRate: "
            f"최소 통과율 {minimum_manual_rate} 미달 ({candidate['manualReviewPassRate']})"
        )

    if comparison_ready:
        max_regression = config.get("maxScoreRegression", 0.0)
        for name, baseline_score in baseline["scoreAverages"].items():
            candidate_score = candidate["scoreAverages"].get(name, 0.0)
            regression = round(baseline_score - candidate_score, 3)
            if regression > max_regression:
                failures.append(f"{name}: baseline 대비 {regression} 회귀")
        if baseline["average"] - candidate["average"] > config.get("maxOverallRegression", 0.0):
            regression = round(baseline["average"] - candidate["average"], 3)
            failures.append(f"overall: baseline 대비 {regression} 회귀")

    for detail_name, maximum in config.get("maximumDetailCounts", {}).items():
        actual = sum(
            len(result["details"].get(detail_name, []))
            for result in candidate["fixtures"].values()
            if "details" in result
        )
        if actual > maximum:
            failures.append(f"{detail_name}: 최대 {maximum}개 초과 ({actual})")

    return evaluation | {"gate": {"passed": not failures, "failures": failures}}


def gate_run_spec(config_path: Path, run: dict[str, Any]) -> str:
    directory = (config_path.parent / run["directory"]).resolve()
    return f"{run['label']}={directory}"


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, default=DEFAULT_FIXTURE_DIR)
    parser.add_argument("--schema", type=Path, default=DEFAULT_SCHEMA_PATH)
    parser.add_argument(
        "--run",
        action="append",
        help="label=directory 형태로 동일 fixture 결과 디렉터리를 하나 이상 지정",
    )
    parser.add_argument("--gate", type=Path, help="저장 결과 회귀 게이트 설정 JSON")
    args = parser.parse_args(argv)
    if bool(args.run) == bool(args.gate):
        parser.error("--run 또는 --gate 중 하나만 지정해야 합니다.")
    result = (
        evaluate_gate(args.fixtures, args.schema, args.gate)
        if args.gate
        else evaluate_runs(args.fixtures, args.run, args.schema)
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result.get("gate", {}).get("passed", True) else 1


if __name__ == "__main__":
    raise SystemExit(main())
