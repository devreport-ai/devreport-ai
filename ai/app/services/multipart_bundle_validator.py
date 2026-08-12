from __future__ import annotations

from collections.abc import Sequence
from uuid import UUID

from fastapi import UploadFile

from app.core.errors import AIServiceError, ErrorCode
from app.schemas.generation import GenerationManifest


class MultipartBundleValidator:
    """manifest와 반복 files part가 Backend의 bundle 계약에 맞는지 검증한다."""

    @staticmethod
    def validate(
        manifest: GenerationManifest,
        files: Sequence[UploadFile],
        allowed_file_ids: Sequence[UUID],
    ) -> None:
        if len(manifest.files) != len(files):
            raise invalid_bundle()
        if any(file.file_id not in allowed_file_ids for file in manifest.files):
            raise invalid_bundle()

        for manifest_file, uploaded_file in zip(manifest.files, files, strict=True):
            if uploaded_file.filename != manifest_file.path:
                raise invalid_bundle()
            if normalize_content_type(uploaded_file.content_type) != normalize_content_type(
                manifest_file.mime_type
            ):
                raise invalid_bundle()
            if uploaded_file.size is None or uploaded_file.size != manifest_file.size:
                raise invalid_bundle()


def normalize_content_type(value: str | None) -> str:
    return (value or "").split(";", maxsplit=1)[0].strip().lower()


def invalid_bundle() -> AIServiceError:
    return AIServiceError(
        ErrorCode.AI_INVALID_REQUEST,
        "AI 생성 bundle 형식이 올바르지 않습니다.",
    )
