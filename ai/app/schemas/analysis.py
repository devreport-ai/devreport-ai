from __future__ import annotations

from dataclasses import dataclass
from typing import Literal
from uuid import UUID

OmissionReason = Literal[
    "unreadable",
    "empty",
    "text-budget-exhausted",
    "image-limit-exceeded",
    "image-too-large",
]


@dataclass(frozen=True)
class TextEvidence:
    """Gemini에 전달할 수 있도록 정규화한 문서 또는 소스 텍스트 근거."""

    file_id: UUID
    path: str
    mime_type: str
    content: str
    truncated: bool
    # UTF-8이 아닌 인코딩을 폴백으로 읽었거나 일부 문자를 복원하지 못한 경우 true.
    lossy: bool = False


@dataclass(frozen=True)
class ImageEvidence:
    """요청 처리 수명 안에서만 유지하는 Gemini Vision 입력 이미지 근거."""

    file_id: UUID
    path: str
    mime_type: str
    content: bytes


@dataclass(frozen=True)
class OmittedFile:
    """bundle에는 있었지만 분석 근거로 쓰지 못한 파일."""

    file_id: UUID
    path: str
    reason: OmissionReason


@dataclass(frozen=True)
class AnalysisContext:
    """보고서 분석 단계가 공유하는 제한된 bundle 표현."""

    documents: tuple[TextEvidence, ...]
    source_files: tuple[TextEvidence, ...]
    images: tuple[ImageEvidence, ...]
    omitted: tuple[OmittedFile, ...] = ()

    @property
    def has_evidence(self) -> bool:
        return bool(self.documents or self.source_files or self.images)
