from __future__ import annotations

from collections.abc import Sequence

from fastapi import UploadFile

from app.core.errors import AIServiceError, ErrorCode
from app.schemas.analysis import (
    AnalysisContext,
    ImageEvidence,
    OmissionReason,
    OmittedFile,
    TextEvidence,
)
from app.schemas.generation import GenerationManifest, ManifestFile

MAX_TEXT_CHARS_PER_FILE = 200_000
MAX_TOTAL_TEXT_CHARS = 1_000_000
MAX_ANALYZED_IMAGES = 10
# 이미지는 inline bytes로 보내므로 요청 크기 한도(약 20 MB)를 넘지 않게 제한한다.
MAX_IMAGE_BYTES = 7 * 1024 * 1024
IMAGE_MIME_TYPES = {"image/jpeg", "image/png"}

# Backend는 확장자로만 소스를 선별하므로 CP949/EUC-KR 소스가 그대로 들어온다.
# UTF-8 → CP949 순으로 시도하고, 그래도 안 되면 복원 불가 문자를 대체해 읽는다.
TEXT_ENCODINGS = ("utf-8", "cp949")
# 대체 문자 비율이 이 값을 넘으면 텍스트가 아니라 바이너리로 보고 근거에서 제외한다.
MAX_REPLACEMENT_RATIO = 0.1
REPLACEMENT_CHARACTER = "�"


class BundleNormalizer:
    """검증된 multipart bundle을 Gemini 분석에 필요한 최소 입력으로 정규화한다."""

    def __init__(self, max_analyzed_images: int = MAX_ANALYZED_IMAGES) -> None:
        self._max_analyzed_images = max_analyzed_images

    async def normalize(
        self, manifest: GenerationManifest, files: Sequence[UploadFile]
    ) -> AnalysisContext:
        documents: list[TextEvidence] = []
        source_files: list[TextEvidence] = []
        images: list[ImageEvidence] = []
        omitted: list[OmittedFile] = []
        remaining_text_chars = MAX_TOTAL_TEXT_CHARS

        for manifest_file, uploaded_file in zip(manifest.files, files, strict=True):
            content = await uploaded_file.read()
            if manifest_file.category == "images":
                if len(images) >= self._max_analyzed_images:
                    omitted.append(omit(manifest_file, "image-limit-exceeded"))
                    continue
                if len(content) > MAX_IMAGE_BYTES:
                    omitted.append(omit(manifest_file, "image-too-large"))
                    continue
                images.append(self._image_evidence(manifest_file, content))
                continue

            if remaining_text_chars <= 0:
                omitted.append(omit(manifest_file, "text-budget-exhausted"))
                continue

            evidence = self._text_evidence(manifest_file, content, remaining_text_chars)
            if evidence is None:
                # 텍스트로 읽을 수 없는 파일 하나 때문에 생성 전체를 실패시키지 않는다.
                omitted.append(omit(manifest_file, "unreadable"))
                continue
            if not evidence.content.strip():
                # 빈 파일을 근거로 세면 내용이 없는 bundle이 생성까지 진행된다.
                omitted.append(omit(manifest_file, "empty"))
                continue

            remaining_text_chars -= len(evidence.content)
            if manifest_file.category == "documents":
                documents.append(evidence)
            else:
                source_files.append(evidence)

        context = AnalysisContext(
            tuple(documents), tuple(source_files), tuple(images), tuple(omitted)
        )
        if not context.has_evidence:
            # 근거가 하나도 없으면 지시문만으로 사실을 지어내게 되므로 생성하지 않는다.
            raise file_processing_failed()
        return context

    @staticmethod
    def _text_evidence(
        manifest_file: ManifestFile, content: bytes, remaining_chars: int
    ) -> TextEvidence | None:
        decoded = decode_text(content)
        if decoded is None:
            return None

        text, lossy = decoded
        limit = min(MAX_TEXT_CHARS_PER_FILE, remaining_chars)
        return TextEvidence(
            file_id=manifest_file.file_id,
            path=manifest_file.path,
            mime_type=manifest_file.mime_type,
            content=text[:limit],
            truncated=len(text) > limit,
            lossy=lossy,
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


def decode_text(content: bytes) -> tuple[str, bool] | None:
    """(텍스트, 손실 여부)를 반환한다. 텍스트로 볼 수 없으면 None."""
    for encoding in TEXT_ENCODINGS:
        try:
            text = content.decode(encoding)
        except UnicodeDecodeError:
            continue
        return (text, False) if "\x00" not in text else None

    text = content.decode("utf-8", errors="replace")
    if "\x00" in text or is_mostly_replaced(text):
        return None
    return text, True


def is_mostly_replaced(text: str) -> bool:
    if not text:
        return False
    return text.count(REPLACEMENT_CHARACTER) / len(text) > MAX_REPLACEMENT_RATIO


def omit(manifest_file: ManifestFile, reason: OmissionReason) -> OmittedFile:
    return OmittedFile(file_id=manifest_file.file_id, path=manifest_file.path, reason=reason)


def normalize_content_type(value: str) -> str:
    return value.split(";", maxsplit=1)[0].strip().lower()


def file_processing_failed() -> AIServiceError:
    return AIServiceError(
        ErrorCode.AI_FILE_PROCESSING_FAILED,
        "AI 분석용 파일을 처리할 수 없습니다.",
    )
