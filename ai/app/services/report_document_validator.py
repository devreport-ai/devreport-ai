from __future__ import annotations

import json
from collections.abc import Iterable
from pathlib import Path
from typing import Any
from uuid import UUID

from jsonschema import Draft202012Validator, FormatChecker
from jsonschema.exceptions import SchemaError, ValidationError

from app.core.errors import AIServiceError, ErrorCode
from app.schemas.generation import ReportMetadata


class ReportDocumentValidator:
    """AI가 생성한 문서를 공통 계약과 입력 근거에 맞춰 방어적으로 검증한다."""

    def __init__(self, schema_path: Path) -> None:
        self._schema_path = schema_path

    def validate(
        self,
        document: dict[str, Any],
        metadata: ReportMetadata,
        allowed_image_ids: Iterable[UUID],
    ) -> dict[str, Any]:
        try:
            schema = self._load_schema()
            Draft202012Validator.check_schema(schema)
            Draft202012Validator(schema, format_checker=FormatChecker()).validate(document)
            self._validate_metadata(document, metadata)
            self._validate_stable_ids(document)
            self._validate_image_references(document, allowed_image_ids)
        except (KeyError, SchemaError, TypeError, ValidationError, ValueError) as exception:
            raise AIServiceError(
                ErrorCode.AI_INVALID_RESPONSE,
                "AI 응답 형식이 공통 계약과 일치하지 않습니다.",
            ) from exception
        return document

    def _load_schema(self) -> dict[str, Any]:
        try:
            with self._schema_path.open(encoding="utf-8") as schema_file:
                schema = json.load(schema_file)
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exception:
            raise AIServiceError(
                ErrorCode.AI_INVALID_RESPONSE,
                "AI 응답 검증에 필요한 계약 파일을 읽을 수 없습니다.",
            ) from exception

        if not isinstance(schema, dict):
            raise ValueError("schema must be an object")
        return schema

    @staticmethod
    def _validate_metadata(document: dict[str, Any], metadata: ReportMetadata) -> None:
        expected = metadata.model_dump(mode="json", exclude_none=True)
        if document["metadata"] != expected:
            raise ValueError("metadata differs from request")

    @staticmethod
    def _validate_stable_ids(document: dict[str, Any]) -> None:
        section_ids: set[str] = set()
        block_ids: set[str] = set()
        for section in document["sections"]:
            section_id = section["id"]
            if section_id in section_ids:
                raise ValueError("duplicate section id")
            section_ids.add(section_id)

            for block in section["blocks"]:
                block_id = block["id"]
                if block_id in block_ids:
                    raise ValueError("duplicate block id")
                block_ids.add(block_id)

    @staticmethod
    def _validate_image_references(
        document: dict[str, Any], allowed_image_ids: Iterable[UUID]
    ) -> None:
        allowed_ids = {str(file_id) for file_id in allowed_image_ids}
        for section in document["sections"]:
            for block in section["blocks"]:
                if block["type"] == "image" and block["fileId"] not in allowed_ids:
                    raise ValueError("unknown image reference")
