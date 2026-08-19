from __future__ import annotations

import json
from collections.abc import Callable, Sequence
from time import sleep
from typing import Any, Protocol, TypeVar

import httpx

from app.core.errors import AIServiceError, ErrorCode
from app.schemas.analysis import ImageEvidence

ANALYSIS_MAX_OUTPUT_TOKENS = 4096
REPORT_DOCUMENT_MAX_OUTPUT_TOKENS = 8192
ValidatedT = TypeVar("ValidatedT")


class GeminiModels(Protocol):
    def generate_content(self, *, model: str, contents: Any, config: Any) -> Any: ...


class GeminiSdkClient(Protocol):
    models: GeminiModels


class GeminiClient:
    """google-genai SDK를 AI 내부 오류 계약 뒤에 감싼 최소 호출 Client."""

    def __init__(
        self,
        api_key: str | None,
        model: str,
        timeout_seconds: float,
        max_retries: int,
        client_factory: Callable[[str, float], GeminiSdkClient] | None = None,
        sleeper: Callable[[float], None] = sleep,
    ) -> None:
        self._api_key = api_key
        self._model = model
        self._timeout_seconds = timeout_seconds
        self._max_retries = max_retries
        self._client_factory = client_factory or create_sdk_client
        self._sleeper = sleeper

    def generate_json(
        self,
        prompt: str,
        images: Sequence[ImageEvidence] = (),
        *,
        max_output_tokens: int = ANALYSIS_MAX_OUTPUT_TOKENS,
        response_validator: Callable[[str], ValidatedT] | None = None,
    ) -> str | ValidatedT:
        if not self._api_key:
            raise AIServiceError(ErrorCode.AI_UNAVAILABLE, "Gemini API Key가 설정되지 않았습니다.")

        try:
            client = self._client_factory(self._api_key, self._timeout_seconds)
        except Exception as exception:
            raise AIServiceError(
                ErrorCode.AI_UNAVAILABLE, "Gemini 서비스를 사용할 수 없습니다."
            ) from exception
        contents = content_parts(prompt, images)
        for attempt in range(self._max_retries + 1):
            try:
                response = self._generate_once(client, contents, max_output_tokens)
            except Exception as exception:
                if not is_retryable(exception) or attempt == self._max_retries:
                    self._raise_generation_error(exception)
                self._sleeper(retry_delay_seconds(attempt))
                continue

            text = getattr(response, "text", None)
            if is_json_object(text):
                if response_validator is None:
                    return text
                try:
                    return response_validator(text)
                except AIServiceError as exception:
                    if exception.code != ErrorCode.AI_INVALID_RESPONSE:
                        raise
                except (ValueError, json.JSONDecodeError):
                    pass
            if attempt < self._max_retries:
                self._sleeper(retry_delay_seconds(attempt))

        raise AIServiceError(ErrorCode.AI_INVALID_RESPONSE, "Gemini 응답 형식이 올바르지 않습니다.")

    def _generate_once(self, client: GeminiSdkClient, contents: Any, max_output_tokens: int) -> Any:
        return client.models.generate_content(
            model=self._model,
            contents=contents,
            config=response_config(max_output_tokens),
        )

    @staticmethod
    def _raise_generation_error(exception: Exception) -> None:
        if is_timeout(exception):
            raise AIServiceError(
                ErrorCode.AI_TIMEOUT, "Gemini 응답 시간이 초과되었습니다."
            ) from exception
        raise AIServiceError(
            ErrorCode.AI_UNAVAILABLE, "Gemini 서비스를 사용할 수 없습니다."
        ) from exception


def is_retryable(exception: Exception) -> bool:
    if is_timeout(exception):
        return True
    status = getattr(exception, "code", None) or getattr(exception, "status_code", None)
    return isinstance(status, int) and status >= 500


def is_timeout(exception: Exception) -> bool:
    return isinstance(exception, (TimeoutError, httpx.TimeoutException))


def retry_delay_seconds(attempt: int) -> float:
    return min(0.25 * (2**attempt), 2.0)


def create_sdk_client(api_key: str, timeout_seconds: float) -> GeminiSdkClient:
    from google import genai
    from google.genai import types

    return genai.Client(
        api_key=api_key,
        http_options=types.HttpOptions(timeout=int(timeout_seconds * 1000)),
    )


def response_config(max_output_tokens: int) -> Any:
    from google.genai import types

    # Gemini 3.5 Flash는 기본적으로 medium 수준의 thinking을 사용한다.
    # 짧은 구조화 분석에는 low가 적절하며, 출력 토큰을 명시해 JSON이 중간에
    # 잘리는 일을 줄인다.
    return types.GenerateContentConfig(
        response_mime_type="application/json",
        max_output_tokens=max_output_tokens,
        thinking_config=types.ThinkingConfig(thinking_level="low"),
    )


def is_json_object(value: Any) -> bool:
    if not isinstance(value, str) or not value.strip():
        return False
    try:
        return isinstance(json.loads(value), dict)
    except json.JSONDecodeError:
        return False


def content_parts(prompt: str, images: Sequence[ImageEvidence]) -> Any:
    """텍스트만 있으면 문자열을 유지하고, 이미지는 요청 수명 안의 bytes로만 전달한다."""
    if not images:
        return prompt

    from google.genai import types

    return [
        *(types.Part.from_bytes(data=image.content, mime_type=image.mime_type) for image in images),
        prompt,
    ]
