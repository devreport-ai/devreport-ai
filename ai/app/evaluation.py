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
    document: dict[str, Any], expected: dict[str, Any], schema: dict[str, Any]
) -> dict[str, Any]:
    """한 fixture의 생성 결과를 다섯 가지 지표로 평가한다."""
    schema_error = validate_schema(document, schema)
    required_terms = expected.get("requiredTerms", [])
    forbidden_terms = expected.get("forbiddenTerms", [])
    serialized = json.dumps(document, ensure_ascii=False).casefold()
    missing_terms = [term for term in required_terms if term.casefold() not in serialized]
    forbidden_matches = [term for term in forbidden_terms if term.casefold() in serialized]

    allowed_image_ids = set(expected.get("allowedImageFileIds", []))
    image_ids = set(image_file_ids(document))
    invalid_image_ids = sorted(image_ids - allowed_image_ids)

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
        "evidenceConsistency": 1.0 if not invalid_image_ids and not forbidden_matches else 0.0,
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


def load_result(path: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    value = load_json(path)
    document = value.get("document", value)
    if not isinstance(document, dict):
        raise ValueError(f"ReportDocument object required: {path}")
    metadata = value.get("run", {})
    return document, metadata if isinstance(metadata, dict) else {}


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
            document, metadata = load_result(result_path)
            fixture_results[fixture_id] = evaluate_document(
                document, fixture.get("expected", {}), schema
            ) | {"run": metadata}
        valid_results = [result for result in fixture_results.values() if "scores" in result]
        runs[label] = {
            "metadata": next((result["run"] for result in valid_results if result.get("run")), {}),
            "fixtures": fixture_results,
            "average": round(
                sum(result["overall"] for result in valid_results) / len(valid_results), 3
            )
            if valid_results
            else 0.0,
        }
    return {"fixtures": sorted(fixtures), "runs": runs}


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, default=DEFAULT_FIXTURE_DIR)
    parser.add_argument("--schema", type=Path, default=DEFAULT_SCHEMA_PATH)
    parser.add_argument(
        "--run",
        action="append",
        required=True,
        help="label=directory 형태로 동일 fixture 결과 디렉터리를 하나 이상 지정",
    )
    args = parser.parse_args(argv)
    print(
        json.dumps(
            evaluate_runs(args.fixtures, args.run, args.schema), ensure_ascii=False, indent=2
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
