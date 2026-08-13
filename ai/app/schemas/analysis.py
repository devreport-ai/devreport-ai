from __future__ import annotations

from dataclasses import dataclass
from uuid import UUID


@dataclass(frozen=True)
class TextEvidence:
    """Gemini에 전달할 수 있도록 정규화한 문서 또는 소스 텍스트 근거."""

    file_id: UUID
    path: str
    mime_type: str
    content: str
    truncated: bool


@dataclass(frozen=True)
class ImageEvidence:
    """요청 처리 수명 안에서만 유지하는 Gemini Vision 입력 이미지 근거."""

    file_id: UUID
    path: str
    mime_type: str
    content: bytes


@dataclass(frozen=True)
class AnalysisContext:
    """보고서 분석 단계가 공유하는 제한된 bundle 표현."""

    documents: tuple[TextEvidence, ...]
    source_files: tuple[TextEvidence, ...]
    images: tuple[ImageEvidence, ...]
