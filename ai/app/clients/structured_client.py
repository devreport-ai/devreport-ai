from __future__ import annotations

from collections.abc import Callable, Sequence
from typing import Any, Protocol, TypeVar

from pydantic import BaseModel

from app.schemas.analysis import ImageEvidence

ANALYSIS_MAX_OUTPUT_TOKENS = 8192
# thinking 토큰도 출력 한도에 포함되므로 최종 문서에는 넉넉한 한도가 필요하다.
REPORT_DOCUMENT_MAX_OUTPUT_TOKENS = 32_768

ValidatedT = TypeVar("ValidatedT")


class StructuredGenerationClient(Protocol):
    """pipeline이 provider에 요구하는 최소 계약.

    프롬프트(+이미지)를 보내 JSON 객체 문자열을 받고, 주어진 검증기를 통과할 때까지
    provider 오류 계약 안에서 재시도한다. response_model이 있으면 provider의 구조화
    출력 기능으로 스키마를 강제한다.
    """

    def generate_json(
        self,
        prompt: str,
        images: Sequence[ImageEvidence] = (),
        *,
        max_output_tokens: int = ANALYSIS_MAX_OUTPUT_TOKENS,
        response_model: type[BaseModel] | None = None,
        response_validator: Callable[[str], ValidatedT] | None = None,
    ) -> str | Any: ...
