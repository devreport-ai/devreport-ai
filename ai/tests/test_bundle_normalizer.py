from io import BytesIO
from uuid import UUID

import pytest
from fastapi import UploadFile

from app.core.errors import AIServiceError, ErrorCode
from app.schemas.generation import GenerationManifest
from app.services.bundle_normalizer import (
    MAX_IMAGE_BYTES,
    MAX_TEXT_CHARS_PER_FILE,
    MAX_TOTAL_TEXT_CHARS,
    BundleNormalizer,
)

FILE_ID = UUID("00000000-0000-4000-8000-000000000001")
SECOND_FILE_ID = UUID("00000000-0000-4000-8000-000000000002")


def manifest_file(
    file_id: UUID, category: str, path: str, mime_type: str, size: int
) -> dict[str, object]:
    return {
        "fileId": str(file_id),
        "category": category,
        "path": path,
        "mimeType": mime_type,
        "size": size,
    }


def manifest(category: str, path: str, mime_type: str, size: int) -> GenerationManifest:
    return GenerationManifest.model_validate(
        {"version": 1, "files": [manifest_file(FILE_ID, category, path, mime_type, size)]}
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
async def test_reads_cp949_source_file_with_a_fallback_encoding_and_marks_it_lossy():
    # Backend는 확장자로만 소스를 고르므로 CP949/EUC-KR 소스가 그대로 들어온다.
    content = "class App {} // 한글 주석".encode("cp949")
    context = await BundleNormalizer().normalize(
        manifest("source", f"source/{FILE_ID}/src/App.java", "text/plain", len(content)),
        [upload(f"source/{FILE_ID}/src/App.java", content, "text/plain")],
    )

    source = context.source_files[0]
    assert "한글 주석" in source.content
    assert source.lossy is False
    assert context.omitted == ()


@pytest.mark.anyio
async def test_skips_unreadable_file_but_keeps_generating_from_the_rest():
    readable = b"class App {}"
    binary = bytes(range(200, 256))
    files = GenerationManifest.model_validate(
        {
            "version": 1,
            "files": [
                manifest_file(
                    FILE_ID, "source", f"source/{FILE_ID}/App.java", "text/plain", len(readable)
                ),
                manifest_file(
                    SECOND_FILE_ID,
                    "source",
                    f"source/{SECOND_FILE_ID}/blob.txt",
                    "text/plain",
                    len(binary),
                ),
            ],
        }
    )

    context = await BundleNormalizer().normalize(
        files,
        [
            upload(f"source/{FILE_ID}/App.java", readable, "text/plain"),
            upload(f"source/{SECOND_FILE_ID}/blob.txt", binary, "text/plain"),
        ],
    )

    assert [item.file_id for item in context.source_files] == [FILE_ID]
    assert [(item.file_id, item.reason) for item in context.omitted] == [
        (SECOND_FILE_ID, "unreadable")
    ]


@pytest.mark.anyio
async def test_rejects_bundle_without_any_usable_evidence():
    # 근거가 하나도 없으면 지시문만으로 사실을 지어내게 된다.
    content = b"\xff"

    with pytest.raises(AIServiceError) as raised:
        await BundleNormalizer().normalize(
            manifest("source", f"source/{FILE_ID}/src/App.java", "text/plain", len(content)),
            [upload(f"source/{FILE_ID}/src/App.java", content, "text/plain")],
        )

    assert raised.value.code == ErrorCode.AI_FILE_PROCESSING_FAILED


@pytest.mark.anyio
async def test_omits_images_beyond_the_analysis_limit():
    # 이미지는 1장당 1회 Gemini 호출이라 무료 티어 분당 한도를 빠르게 소진한다.
    image_ids = [UUID(f"00000000-0000-4000-8000-00000000010{index}") for index in range(3)]
    files = GenerationManifest.model_validate(
        {
            "version": 1,
            "files": [
                manifest_file(
                    image_id, "images", f"images/{image_id}/shot.png", "image/png", len(b"png")
                )
                for image_id in image_ids
            ],
        }
    )

    context = await BundleNormalizer(max_analyzed_images=2).normalize(
        files,
        [upload(f"images/{image_id}/shot.png", b"png", "image/png") for image_id in image_ids],
    )

    assert [image.file_id for image in context.images] == image_ids[:2]
    assert [(item.file_id, item.reason) for item in context.omitted] == [
        (image_ids[2], "image-limit-exceeded")
    ]


@pytest.mark.anyio
async def test_omits_text_files_once_the_total_character_budget_is_used_up():
    file_ids = [
        UUID(f"00000000-0000-4000-8000-00000000020{index}")
        for index in range(MAX_TOTAL_TEXT_CHARS // MAX_TEXT_CHARS_PER_FILE + 1)
    ]
    content = b"a" * MAX_TEXT_CHARS_PER_FILE
    files = GenerationManifest.model_validate(
        {
            "version": 1,
            "files": [
                manifest_file(
                    file_id, "source", f"source/{file_id}/App.java", "text/plain", len(content)
                )
                for file_id in file_ids
            ],
        }
    )

    context = await BundleNormalizer().normalize(
        files,
        [upload(f"source/{file_id}/App.java", content, "text/plain") for file_id in file_ids],
    )

    assert len(context.source_files) == len(file_ids) - 1
    assert [(item.file_id, item.reason) for item in context.omitted] == [
        (file_ids[-1], "text-budget-exhausted")
    ]


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


@pytest.mark.anyio
async def test_omits_image_larger_than_the_inline_request_limit():
    content = b"p" * (MAX_IMAGE_BYTES + 1)
    files = GenerationManifest.model_validate(
        {
            "version": 1,
            "files": [
                manifest_file(
                    FILE_ID, "images", f"images/{FILE_ID}/shot.png", "image/png", len(content)
                ),
                manifest_file(
                    SECOND_FILE_ID,
                    "source",
                    f"source/{SECOND_FILE_ID}/App.java",
                    "text/plain",
                    12,
                ),
            ],
        }
    )

    context = await BundleNormalizer().normalize(
        files,
        [
            upload(f"images/{FILE_ID}/shot.png", content, "image/png"),
            upload(f"source/{SECOND_FILE_ID}/App.java", b"class App {}", "text/plain"),
        ],
    )

    assert context.images == ()
    assert [(item.file_id, item.reason) for item in context.omitted] == [
        (FILE_ID, "image-too-large")
    ]


@pytest.mark.anyio
async def test_rejects_bundle_that_only_contains_empty_text_files():
    # 빈 파일을 근거로 세면 내용이 없는 bundle이 Gemini 호출까지 진행된다.
    with pytest.raises(AIServiceError) as raised:
        await BundleNormalizer().normalize(
            manifest("source", f"source/{FILE_ID}/src/App.java", "text/plain", 0),
            [upload(f"source/{FILE_ID}/src/App.java", b"   \n", "text/plain")],
        )

    assert raised.value.code == ErrorCode.AI_FILE_PROCESSING_FAILED


@pytest.mark.anyio
async def test_omits_empty_text_file_but_keeps_the_rest():
    files = GenerationManifest.model_validate(
        {
            "version": 1,
            "files": [
                manifest_file(FILE_ID, "source", f"source/{FILE_ID}/Empty.java", "text/plain", 0),
                manifest_file(
                    SECOND_FILE_ID, "source", f"source/{SECOND_FILE_ID}/App.java", "text/plain", 12
                ),
            ],
        }
    )

    context = await BundleNormalizer().normalize(
        files,
        [
            upload(f"source/{FILE_ID}/Empty.java", b"", "text/plain"),
            upload(f"source/{SECOND_FILE_ID}/App.java", b"class App {}", "text/plain"),
        ],
    )

    assert [item.file_id for item in context.source_files] == [SECOND_FILE_ID]
    assert [(item.file_id, item.reason) for item in context.omitted] == [(FILE_ID, "empty")]
