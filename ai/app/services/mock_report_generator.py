from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator, FormatChecker
from jsonschema.exceptions import SchemaError, ValidationError

from app.core.errors import AIServiceError, ErrorCode


class MockReportGenerator:
    """공통 계약 검증을 거친 샘플 ReportDocument를 반환한다."""

    def __init__(self, sample_report_path: Path, report_schema_path: Path) -> None:
        self._sample_report_path = sample_report_path
        self._report_schema_path = report_schema_path

    def generate(self) -> dict[str, Any]:
        document = self._load_json(self._sample_report_path)
        schema = self._load_json(self._report_schema_path)

        try:
            Draft202012Validator.check_schema(schema)
            Draft202012Validator(schema, format_checker=FormatChecker()).validate(document)
        except (SchemaError, ValidationError) as exception:
            raise AIServiceError(
                ErrorCode.AI_INVALID_RESPONSE,
                "AI 응답 형식이 공통 계약과 일치하지 않습니다.",
            ) from exception

        return document

    @staticmethod
    def _load_json(path: Path) -> dict[str, Any]:
        try:
            with path.open(encoding="utf-8") as json_file:
                value = json.load(json_file)
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exception:
            raise AIServiceError(
                ErrorCode.AI_INVALID_RESPONSE,
                "AI 응답 검증에 필요한 계약 파일을 읽을 수 없습니다.",
            ) from exception

        if not isinstance(value, dict):
            raise AIServiceError(
                ErrorCode.AI_INVALID_RESPONSE,
                "AI 응답 형식이 공통 계약과 일치하지 않습니다.",
            )
        return value
