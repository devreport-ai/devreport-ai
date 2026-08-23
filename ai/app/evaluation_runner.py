"""고정 fixture를 실제 Gemini 파이프라인으로 실행해 평가 결과를 저장한다."""

from __future__ import annotations

import argparse
import json
from collections.abc import Sequence
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from uuid import UUID

from app.clients.gemini_client import GeminiClient
from app.core.config import Settings, get_settings
from app.core.deadline import Deadline
from app.evaluation import DEFAULT_FIXTURE_DIR, load_json
from app.prompts.report_generation import PROMPT_VERSION
from app.schemas.analysis import AnalysisContext, ImageEvidence, TextEvidence
from app.schemas.generation import GenerationRequest
from app.services.report_generation_pipeline import ReportGenerationPipeline

DEFAULT_OUTPUT_DIR = Path(__file__).resolve().parents[1] / "evals" / "generated"


def context_from_fixture(fixture: dict[str, Any], fixture_path: Path) -> AnalysisContext:
    def texts(key: str) -> tuple[TextEvidence, ...]:
        return tuple(
            TextEvidence(
                file_id=UUID(item["fileId"]),
                path=item["path"],
                mime_type=item["mimeType"],
                content=item["content"],
                truncated=False,
            )
            for item in fixture.get(key, [])
        )

    images = tuple(
        ImageEvidence(
            file_id=UUID(item["fileId"]),
            path=item["path"],
            mime_type=item["mimeType"],
            content=(fixture_path.parent / item["assetPath"]).read_bytes(),
        )
        for item in fixture.get("images", [])
    )
    return AnalysisContext(
        documents=texts("documents"), source_files=texts("sourceFiles"), images=images
    )


def request_from_fixture(fixture: dict[str, Any]) -> GenerationRequest:
    file_ids = [
        item["fileId"]
        for key in ("documents", "sourceFiles", "images")
        for item in fixture.get(key, [])
    ]
    return GenerationRequest.model_validate(fixture["request"] | {"fileIds": file_ids})


def generate_fixture(fixture_path: Path, output_path: Path, settings: Settings) -> None:
    fixture = load_json(fixture_path)
    pipeline = ReportGenerationPipeline(
        GeminiClient(
            api_key=settings.gemini_api_key,
            model=settings.gemini_model,
            timeout_seconds=settings.gemini_timeout_seconds,
            max_retries=settings.gemini_max_retries,
            deadline=Deadline(settings.generation_deadline_seconds),
        ),
        settings.report_schema_path,
    )
    document = pipeline.generate(
        request_from_fixture(fixture), context_from_fixture(fixture, fixture_path)
    )
    result = {
        "run": {
            "provider": "gemini",
            "model": settings.gemini_model,
            "promptVersion": PROMPT_VERSION,
            "generatedAt": datetime.now(UTC).isoformat(),
        },
        "document": document,
        "manualReview": {
            "reviewedBy": None,
            "reviewedAt": None,
            "checks": [
                {"criterion": criterion, "passed": None, "notes": ""}
                for criterion in fixture.get("expected", {}).get("manualReview", [])
            ],
        },
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = output_path.with_suffix(".tmp")
    temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(output_path)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, default=DEFAULT_FIXTURE_DIR)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--fixture", action="append", help="실행할 fixture ID(기본: 전체)")
    parser.add_argument("--overwrite", action="store_true")
    args = parser.parse_args(argv)
    fixture_paths = sorted(args.fixtures.glob("*.json"))
    if args.fixture:
        selected = set(args.fixture)
        fixture_paths = [path for path in fixture_paths if path.stem in selected]
    if not fixture_paths:
        parser.error("실행할 fixture가 없습니다.")

    settings = get_settings()
    for fixture_path in fixture_paths:
        output_path = args.output / fixture_path.name
        if output_path.exists() and not args.overwrite:
            parser.error(f"결과 파일이 이미 있습니다: {output_path} (--overwrite 필요)")
        generate_fixture(fixture_path, output_path, settings)
        print(f"generated {fixture_path.stem} -> {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
