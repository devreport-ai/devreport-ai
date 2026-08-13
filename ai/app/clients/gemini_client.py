from __future__ import annotations

from collections.abc import Callable
from time import sleep
from typing import Any, Protocol

from app.core.errors import AIServiceError, ErrorCode


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

    def generate_json(self, prompt: str) -> str:
        if not self._api_key:
            raise AIServiceError(ErrorCode.AI_UNAVAILABLE, "Gemini API Key가 설정되지 않았습니다.")

        client = self._client_factory(self._api_key, self._timeout_seconds)
        response = self._generate_with_retries(client, prompt)

        text = getattr(response, "text", None)
        if not isinstance(text, str) or not text.strip():
            raise AIServiceError(
                ErrorCode.AI_INVALID_RESPONSE, "Gemini 응답 형식이 올바르지 않습니다."
            )
        return text

    def _generate_with_retries(self, client: GeminiSdkClient, prompt: str) -> Any:
        last_exception: Exception | None = None
        for attempt in range(self._max_retries + 1):
            try:
                return client.models.generate_content(
                    model=self._model,
                    contents=prompt,
                    config=response_config(),
                )
            except Exception as exception:
                last_exception = exception
                if not is_retryable(exception) or attempt == self._max_retries:
                    break
                self._sleeper(retry_delay_seconds(attempt))

        assert last_exception is not None
        if isinstance(last_exception, TimeoutError):
            raise AIServiceError(
                ErrorCode.AI_TIMEOUT, "Gemini 응답 시간이 초과되었습니다."
            ) from last_exception
        raise AIServiceError(
            ErrorCode.AI_UNAVAILABLE, "Gemini 서비스를 사용할 수 없습니다."
        ) from last_exception


def is_retryable(exception: Exception) -> bool:
    if isinstance(exception, TimeoutError):
        return True
    status = getattr(exception, "code", None) or getattr(exception, "status_code", None)
    return isinstance(status, int) and status >= 500


def retry_delay_seconds(attempt: int) -> float:
    return min(0.25 * (2**attempt), 2.0)


def create_sdk_client(api_key: str, timeout_seconds: float) -> GeminiSdkClient:
    from google import genai
    from google.genai import types

    return genai.Client(
        api_key=api_key,
        http_options=types.HttpOptions(timeout=int(timeout_seconds * 1000)),
    )


def response_config() -> Any:
    from google.genai import types

    return types.GenerateContentConfig(response_mime_type="application/json")
