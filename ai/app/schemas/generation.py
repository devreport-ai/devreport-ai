from __future__ import annotations

from datetime import date as CalendarDate
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

MAX_BUNDLE_FILE_COUNT = 1_000
MAX_BUNDLE_TOTAL_SIZE = 100 * 1024 * 1024


class GenerationRequest(BaseModel):
    """Backend가 AI 보고서 생성에 전달하는 요청 메타데이터."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    file_ids: list[UUID] = Field(alias="fileIds", min_length=1)
    metadata: ReportMetadata
    instructions: str

    @field_validator("instructions")
    @classmethod
    def validate_instructions(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("instructions must not be blank")
        return value


class ReportMetadata(BaseModel):
    """ReportDocument metadata와 동일한 생성 요청 메타데이터 계약."""

    model_config = ConfigDict(extra="forbid")

    title: str = Field(min_length=1)
    author: str | None = None
    course: str | None = None
    date: CalendarDate | None = None

    @field_validator("title")
    @classmethod
    def validate_title(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("title must not be blank")
        return value


class ManifestFile(BaseModel):
    """Backend가 multipart 파일 part와 연결하기 위해 제공하는 파일 메타데이터."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    file_id: UUID = Field(alias="fileId")
    category: Literal["source", "documents", "images"]
    path: str = Field(min_length=1)
    mime_type: str = Field(alias="mimeType", min_length=1)
    size: int = Field(ge=0)

    @model_validator(mode="after")
    def validate_bundle_path(self) -> ManifestFile:
        parts = self.path.split("/")
        if (
            self.path.startswith("/")
            or "\\" in self.path
            or len(parts) < 3
            or any(part in {"", ".", ".."} for part in parts)
            or parts[0] != self.category
            or parts[1] != str(self.file_id)
        ):
            raise ValueError("path must be a bundle-relative path for its category and fileId")
        return self


class GenerationManifest(BaseModel):
    """Backend가 생성한 multipart bundle의 버전과 파일 목록."""

    model_config = ConfigDict(extra="forbid")

    version: Literal[1]
    files: list[ManifestFile] = Field(max_length=MAX_BUNDLE_FILE_COUNT)

    @model_validator(mode="after")
    def validate_total_size(self) -> GenerationManifest:
        if sum(file.size for file in self.files) > MAX_BUNDLE_TOTAL_SIZE:
            raise ValueError("bundle exceeds maximum total size")
        return self
