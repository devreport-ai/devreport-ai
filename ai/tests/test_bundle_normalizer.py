from io import BytesIO
from uuid import UUID
from zipfile import ZipFile

import pytest
from fastapi import UploadFile

from app.core.errors import AIServiceError, ErrorCode
from app.schemas.generation import GenerationManifest
from app.services.bundle_normalizer import (
    MAX_IMAGE_BYTES,
    MAX_PDF_PAGES,
    MAX_TEXT_CHARS_PER_FILE,
    MAX_TOTAL_TEXT_CHARS,
    BundleNormalizer,
)

FILE_ID = UUID("00000000-0000-4000-8000-000000000001")
SECOND_FILE_ID = UUID("00000000-0000-4000-8000-000000000002")
PDF_ID = UUID("00000000-0000-4000-8000-000000000003")
DOCX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"


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


def manifest(
    category: str, path: str, mime_type: str, size: int, file_id: UUID = FILE_ID
) -> GenerationManifest:
    return GenerationManifest.model_validate(
        {"version": 1, "files": [manifest_file(file_id, category, path, mime_type, size)]}
    )


def upload(path: str, content: bytes, content_type: str) -> UploadFile:
    return UploadFile(filename=path, file=BytesIO(content), headers={"content-type": content_type})


def pdf_content(pages: int = 1, encrypted: bool = False) -> bytes:
    encryption = b"/Encrypt 3 0 R" if encrypted else b""
    page_objects = b"".join(b"<< /Type /Page >>\n" for _ in range(pages))
    prefix = b"%PDF-1.4\n/Type /Catalog\n" + page_objects + encryption
    xref_offset = len(prefix)
    return prefix + b"xref\nstartxref\n" + str(xref_offset).encode() + b"\n%%EOF\n"


def docx(document_xml: str, images: dict[str, bytes] | None = None) -> bytes:
    output = BytesIO()
    with ZipFile(output, "w") as archive:
        archive.writestr("[Content_Types].xml", "<Types/>")
        archive.writestr("word/document.xml", document_xml)
        for path, content in (images or {}).items():
            archive.writestr(path, content)
    return output.getvalue()


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
async def test_keeps_pdf_as_native_byte_evidence():
    content = pdf_content()

    context = await BundleNormalizer().normalize(
        manifest(
            "documents",
            f"documents/{PDF_ID}/assignment.pdf",
            "application/pdf",
            len(content),
            PDF_ID,
        ),
        [upload(f"documents/{PDF_ID}/assignment.pdf", content, "application/pdf")],
    )

    assert context.documents == ()
    assert context.pdfs[0].file_id == PDF_ID
    assert context.pdfs[0].content == content


@pytest.mark.anyio
async def test_rejects_encrypted_pdf():
    content = pdf_content(encrypted=True)

    with pytest.raises(AIServiceError) as raised:
        await BundleNormalizer().normalize(
            manifest(
                "documents",
                f"documents/{PDF_ID}/assignment.pdf",
                "application/pdf",
                len(content),
                PDF_ID,
            ),
            [upload(f"documents/{PDF_ID}/assignment.pdf", content, "application/pdf")],
        )

    assert raised.value.code == ErrorCode.AI_FILE_PROCESSING_FAILED


@pytest.mark.anyio
async def test_rejects_pdf_over_the_page_limit():
    content = pdf_content(pages=MAX_PDF_PAGES + 1)

    with pytest.raises(AIServiceError) as raised:
        await BundleNormalizer().normalize(
            manifest(
                "documents",
                f"documents/{PDF_ID}/assignment.pdf",
                "application/pdf",
                len(content),
                PDF_ID,
            ),
            [upload(f"documents/{PDF_ID}/assignment.pdf", content, "application/pdf")],
        )

    assert raised.value.code == ErrorCode.AI_FILE_PROCESSING_FAILED


@pytest.mark.anyio
async def test_extracts_docx_paragraphs_tables_and_embedded_images():
    document_xml = """<?xml version="1.0" encoding="UTF-8"?>
    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
      <w:body>
        <w:p><w:r><w:t>과제 요구사항</w:t></w:r></w:p>
        <w:tbl><w:tr><w:tc><w:p><w:r><w:t>항목</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>결과</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
      </w:body>
    </w:document>"""
    content = docx(
        document_xml,
        {
            "word/media/result.png": b"\x89PNG\r\n\x1a\nimage",
            "word/media/ignored.gif": b"GIF89a",
        },
    )
    path = f"documents/{FILE_ID}/assignment.docx"

    context = await BundleNormalizer().normalize(
        manifest("documents", path, DOCX_MIME_TYPE, len(content)),
        [upload(path, content, DOCX_MIME_TYPE)],
    )

    assert "과제 요구사항" in context.documents[0].content
    assert "| 항목 | 결과 |" in context.documents[0].content
    assert context.document_images[0].content.startswith(b"\x89PNG")
    assert context.document_images[0].file_id == FILE_ID
    assert [(item.reason, item.path) for item in context.omitted] == [
        ("unsupported-embedded-image", f"{path}#word/media/ignored.gif")
    ]


@pytest.mark.anyio
async def test_omits_docx_with_unsafe_xml_but_keeps_other_evidence():
    unsafe = docx('<!DOCTYPE x [<!ENTITY a "boom">]><document>&a;</document>')
    docx_path = f"documents/{FILE_ID}/unsafe.docx"
    source_path = f"source/{SECOND_FILE_ID}/App.java"
    files = GenerationManifest.model_validate(
        {
            "version": 1,
            "files": [
                manifest_file(FILE_ID, "documents", docx_path, DOCX_MIME_TYPE, len(unsafe)),
                manifest_file(SECOND_FILE_ID, "source", source_path, "text/plain", 12),
            ],
        }
    )

    context = await BundleNormalizer().normalize(
        files,
        [
            upload(docx_path, unsafe, DOCX_MIME_TYPE),
            upload(source_path, b"class App {}", "text/plain"),
        ],
    )

    assert context.documents == ()
    assert context.source_files[0].file_id == SECOND_FILE_ID
    assert [(item.file_id, item.reason) for item in context.omitted] == [(FILE_ID, "unreadable")]


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
