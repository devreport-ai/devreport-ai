from __future__ import annotations

from dataclasses import dataclass
from io import BytesIO
from xml.etree import ElementTree
from zipfile import BadZipFile, ZipFile

MAX_DOCX_ENTRIES = 1_000
MAX_DOCX_UNCOMPRESSED_BYTES = 100 * 1024 * 1024
WORD_NAMESPACE = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


@dataclass(frozen=True)
class DocxImage:
    path: str
    mime_type: str
    content: bytes


@dataclass(frozen=True)
class DocxExtraction:
    text: str
    images: tuple[DocxImage, ...]
    unsupported_image_paths: tuple[str, ...]


def extract_docx(content: bytes) -> DocxExtraction:
    """DOCX 본문·표와 PNG/JPEG를 제한된 메모리 안에서 추출한다."""
    try:
        with ZipFile(BytesIO(content)) as archive:
            entries = archive.infolist()
            if (
                len(entries) > MAX_DOCX_ENTRIES
                or sum(entry.file_size for entry in entries) > MAX_DOCX_UNCOMPRESSED_BYTES
                or any(entry.flag_bits & 1 for entry in entries)
            ):
                raise ValueError("DOCX limits exceeded or encrypted")

            document_xml = archive.read("word/document.xml")
            text = document_text(document_xml)
            images: list[DocxImage] = []
            unsupported: list[str] = []
            for entry in sorted(entries, key=lambda item: item.filename):
                if not entry.filename.startswith("word/media/") or entry.is_dir():
                    continue
                image = archive.read(entry)
                mime_type = image_mime_type(image)
                if mime_type is None:
                    unsupported.append(entry.filename)
                else:
                    images.append(DocxImage(entry.filename, mime_type, image))
            return DocxExtraction(text, tuple(images), tuple(unsupported))
    except (BadZipFile, KeyError, ElementTree.ParseError, OSError) as exception:
        raise ValueError("invalid DOCX") from exception


def document_text(xml: bytes) -> str:
    if b"<!DOCTYPE" in xml.upper() or b"<!ENTITY" in xml.upper():
        raise ValueError("DOCX XML declarations are not allowed")
    root = ElementTree.fromstring(xml)
    body = root.find(f".//{WORD_NAMESPACE}body")
    if body is None:
        return ""

    blocks: list[str] = []
    for child in body:
        if child.tag == f"{WORD_NAMESPACE}p":
            text = paragraph_text(child)
            if text:
                blocks.append(text)
        elif child.tag == f"{WORD_NAMESPACE}tbl":
            rows = [
                [paragraph_text(cell).strip() for cell in row.findall(f"{WORD_NAMESPACE}tc")]
                for row in child.findall(f"{WORD_NAMESPACE}tr")
            ]
            blocks.extend("| " + " | ".join(row) + " |" for row in rows if any(row))
    return "\n\n".join(blocks)


def paragraph_text(element: ElementTree.Element) -> str:
    values: list[str] = []
    for node in element.iter():
        if node.tag == f"{WORD_NAMESPACE}t" and node.text:
            values.append(node.text)
        elif node.tag == f"{WORD_NAMESPACE}tab":
            values.append("\t")
        elif node.tag == f"{WORD_NAMESPACE}br":
            values.append("\n")
    return "".join(values).strip()


def image_mime_type(content: bytes) -> str | None:
    if content.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if content.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    return None
