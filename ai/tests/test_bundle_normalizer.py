from io import BytesIO
from uuid import UUID

import pytest
from fastapi import UploadFile

from app.core.errors import AIServiceError, ErrorCode
from app.schemas.generation import GenerationManifest
from app.services.bundle_normalizer import (
    MAX_TEXT_CHARS_PER_FILE,
    BundleNormalizer,
)

FILE_ID = UUID("00000000-0000-4000-8000-000000000001")


def manifest(category: str, path: str, mime_type: str, size: int) -> GenerationManifest:
    return GenerationManifest.model_validate(
        {
            "version": 1,
            "files": [
                {
                    "fileId": str(FILE_ID),
                    "category": category,
                    "path": path,
                    "mimeType": mime_type,
                    "size": size,
                }
            ],
        }
    )


def upload(path: str, content: bytes, content_type: str) -> UploadFile:
    return UploadFile(filename=path, file=BytesIO(content), headers={"content-type": content_type})


@pytest.mark.anyio
async def test_normalizes_document_and_keeps_original_file_id():
    content = "과제 요구사항".encode()
    context = await BundleNormalizer().normalize(
        manifest("documents", f"documents/{FILE_ID}/assignment.md", "text/markdown", len(content)),
        [upload(f"documents/{FILE_ID}/assignment.md", content, "text/markdown")],
    )

    assert context.documents[0].file_id == FILE_ID
    assert context.documents[0].content == "과제 요구사항"
    assert context.source_files == ()


@pytest.mark.anyio
async def test_truncates_long_source_file_and_marks_it():
    content = b"a" * (MAX_TEXT_CHARS_PER_FILE + 1)
    context = await BundleNormalizer().normalize(
        manifest("source", f"source/{FILE_ID}/src/App.java", "text/plain", len(content)),
        [upload(f"source/{FILE_ID}/src/App.java", content, "text/plain")],
    )

    source = context.source_files[0]
    assert len(source.content) == MAX_TEXT_CHARS_PER_FILE
    assert source.truncated is True


@pytest.mark.anyio
async def test_rejects_non_utf8_text_file():
    content = b"\xff"

    with pytest.raises(AIServiceError) as raised:
        await BundleNormalizer().normalize(
            manifest("source", f"source/{FILE_ID}/src/App.java", "text/plain", len(content)),
            [upload(f"source/{FILE_ID}/src/App.java", content, "text/plain")],
        )

    assert raised.value.code == ErrorCode.AI_FILE_PROCESSING_FAILED


@pytest.mark.anyio
async def test_normalizes_png_as_ephemeral_image_evidence():
    content = b"png-bytes"
    context = await BundleNormalizer().normalize(
        manifest("images", f"images/{FILE_ID}/result.png", "image/png", len(content)),
        [upload(f"images/{FILE_ID}/result.png", content, "image/png")],
    )

    image = context.images[0]
    assert image.file_id == FILE_ID
    assert image.content == content


@pytest.mark.anyio
async def test_rejects_unsupported_image_mime_type():
    content = b"gif-bytes"

    with pytest.raises(AIServiceError) as raised:
        await BundleNormalizer().normalize(
            manifest("images", f"images/{FILE_ID}/result.gif", "image/gif", len(content)),
            [upload(f"images/{FILE_ID}/result.gif", content, "image/gif")],
        )

    assert raised.value.code == ErrorCode.AI_FILE_PROCESSING_FAILED
