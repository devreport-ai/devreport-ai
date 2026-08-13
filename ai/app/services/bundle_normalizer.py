from __future__ import annotations

from collections.abc import Sequence

from fastapi import UploadFile

from app.core.errors import AIServiceError, ErrorCode
from app.schemas.analysis import AnalysisContext, ImageEvidence, TextEvidence
from app.schemas.generation import GenerationManifest, ManifestFile

MAX_TEXT_CHARS_PER_FILE = 200_000
MAX_TOTAL_TEXT_CHARS = 1_000_000
IMAGE_MIME_TYPES = {"image/jpeg", "image/png"}


class BundleNormalizer:
    """검증된 multipart bundle을 Gemini 분석에 필요한 최소 입력으로 정규화한다."""

    async def normalize(
        self, manifest: GenerationManifest, files: Sequence[UploadFile]
    ) -> AnalysisContext:
        documents: list[TextEvidence] = []
        source_files: list[TextEvidence] = []
        images: list[ImageEvidence] = []
        remaining_text_chars = MAX_TOTAL_TEXT_CHARS

        for manifest_file, uploaded_file in zip(manifest.files, files, strict=True):
            content = await uploaded_file.read()
            if manifest_file.category == "images":
                images.append(self._image_evidence(manifest_file, content))
                continue

            evidence = self._text_evidence(manifest_file, content, remaining_text_chars)
            remaining_text_chars -= len(evidence.content)
            if manifest_file.category == "documents":
                documents.append(evidence)
            else:
                source_files.append(evidence)

        return AnalysisContext(tuple(documents), tuple(source_files), tuple(images))

    @staticmethod
    def _text_evidence(
        manifest_file: ManifestFile, content: bytes, remaining_chars: int
    ) -> TextEvidence:
        try:
            text = content.decode("utf-8")
        except UnicodeDecodeError as exception:
            raise file_processing_failed() from exception

        limit = min(MAX_TEXT_CHARS_PER_FILE, remaining_chars)
        return TextEvidence(
            file_id=manifest_file.file_id,
            path=manifest_file.path,
            mime_type=manifest_file.mime_type,
            content=text[:limit],
            truncated=len(text) > limit,
        )

    @staticmethod
    def _image_evidence(manifest_file: ManifestFile, content: bytes) -> ImageEvidence:
        if normalize_content_type(manifest_file.mime_type) not in IMAGE_MIME_TYPES:
            raise file_processing_failed()
        return ImageEvidence(
            file_id=manifest_file.file_id,
            path=manifest_file.path,
            mime_type=manifest_file.mime_type,
            content=content,
        )


def normalize_content_type(value: str) -> str:
    return value.split(";", maxsplit=1)[0].strip().lower()


def file_processing_failed() -> AIServiceError:
    return AIServiceError(
        ErrorCode.AI_FILE_PROCESSING_FAILED,
        "AI 분석용 파일을 처리할 수 없습니다.",
    )
