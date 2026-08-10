from __future__ import annotations

from typing import Any
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator


class GenerationRequest(BaseModel):
    """AI-01 동안 Backend GenerationRequest와 호환되는 임시 내부 요청 계약.

    실제 파일 bundle의 multipart 계약은 Backend-AI 공동 task에서 이 모델을 대체한다.
    """

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    file_ids: list[UUID] = Field(alias="fileIds", min_length=1)
    metadata: dict[str, Any]
    instructions: str

    @field_validator("instructions")
    @classmethod
    def validate_instructions(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("instructions must not be blank")
        return value
