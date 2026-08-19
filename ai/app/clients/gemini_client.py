from __future__ import annotations

import json
import logging
from collections.abc import Callable, Sequence
from time import sleep
from typing import Any, Protocol, TypeVar

import httpx

from app.core.deadline import Deadline
from app.core.errors import AIServiceError, ErrorCode
from app.schemas.analysis import ImageEvidence

ANALYSIS_MAX_OUTPUT_TOKENS = 8192
# thinking 토큰도 출력 한도에 포함되므로 최종 문서에는 넉넉한 한도가 필요하다.
REPORT_DOCUMENT_MAX_OUTPUT_TOKENS = 32_768
RATE_LIMIT_STATUS = 429
# 408 Request Timeout과 429 RESOURCE_EXHAUSTED는 무료 티어에서 가장 흔한 일시 오류다.
RETRYABLE_STATUS_CODES = frozenset({408, RATE_LIMIT_STATUS})
MAX_TOKENS_FINISH_REASON = "MAX_TOKENS"

log = logging.getLogger(__name__)
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
        deadline: Deadline | None = None,
    ) -> None:
        self._api_key = api_key
        self._model = model
        self._timeout_seconds = timeout_seconds
        self._max_retries = max_retries
        self._client_factory = client_factory or create_sdk_client
        self._sleeper = sleeper
        self._deadline = deadline

    def generate_json(
        self,
        prompt: str,
        images: Sequence[ImageEvidence] = (),
        *,
        max_output_tokens: int = ANALYSIS_MAX_OUTPUT_TOKENS,
        response_schema: Any | None = None,
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

        rejection: str | None = None
        for attempt in range(self._max_retries + 1):
            self._ensure_time_left()
            contents = content_parts(retry_prompt(prompt, rejection), images)
            try:
                response = self._generate_once(client, contents, max_output_tokens, response_schema)
            except Exception as exception:
                if not is_retryable(exception) or attempt == self._max_retries:
                    self._raise_generation_error(exception)
                log.warning(
                    "Gemini 호출 실패로 재시도한다 attempt=%d status=%s",
                    attempt + 1,
                    status_code(exception),
                )
                self._wait(retry_delay_seconds(attempt, is_rate_limited(exception)))
                continue

            if is_truncated(response):
                # 같은 프롬프트로 다시 물어도 같은 길이에서 잘리므로 재시도하지 않는다.
                raise AIServiceError(
                    ErrorCode.AI_INVALID_RESPONSE,
                    "Gemini 응답이 출력 토큰 한도에서 잘렸습니다.",
                )

            text = getattr(response, "text", None)
            if not is_json_object(text):
                rejection = "JSON 객체가 아닌 응답이었다."
            elif response_validator is None:
                return text
            else:
                try:
                    return response_validator(text)
                except AIServiceError as exception:
                    if exception.code != ErrorCode.AI_INVALID_RESPONSE:
                        raise
                    rejection = exception.message
                except ValueError as exception:
                    rejection = str(exception)

            log.warning("Gemini 응답 검증 실패 attempt=%d", attempt + 1)
            if attempt < self._max_retries:
                self._wait(retry_delay_seconds(attempt, rate_limited=False))

        raise AIServiceError(ErrorCode.AI_INVALID_RESPONSE, "Gemini 응답 형식이 올바르지 않습니다.")

    def _generate_once(
        self,
        client: GeminiSdkClient,
        contents: Any,
        max_output_tokens: int,
        response_schema: Any | None,
    ) -> Any:
        return client.models.generate_content(
            model=self._model,
            contents=contents,
            config=response_config(max_output_tokens, response_schema),
        )

    def _ensure_time_left(self) -> None:
        if self._deadline is not None:
            self._deadline.ensure_active()

    def _wait(self, seconds: float) -> None:
        # 남은 예산보다 오래 기다리면 어차피 만료되므로 그 전에 멈춘다.
        if self._deadline is not None:
            self._deadline.ensure_active()
            seconds = min(seconds, self._deadline.remaining())
        if seconds > 0:
            self._sleeper(seconds)

    @staticmethod
    def _raise_generation_error(exception: Exception) -> None:
        if is_timeout(exception):
            raise AIServiceError(
                ErrorCode.AI_TIMEOUT, "Gemini 응답 시간이 초과되었습니다."
            ) from exception
        raise AIServiceError(
            ErrorCode.AI_UNAVAILABLE, "Gemini 서비스를 사용할 수 없습니다."
        ) from exception


def retry_prompt(prompt: str, rejection: str | None) -> str:
    """직전 응답이 거절된 이유를 알려 같은 실패를 반복하지 않게 한다."""
    if not rejection:
        return prompt
    return (
        f"{prompt}\n\n"
        f"직전 응답은 다음 이유로 거절되었다: {rejection}\n"
        "반환 형식을 정확히 지켜 JSON 객체만 다시 작성한다."
    )


def status_code(exception: Exception) -> int | None:
    status = getattr(exception, "code", None)
    if not isinstance(status, int):
        status = getattr(exception, "status_code", None)
    return status if isinstance(status, int) else None


def is_rate_limited(exception: Exception) -> bool:
    return status_code(exception) == RATE_LIMIT_STATUS


def is_retryable(exception: Exception) -> bool:
    if is_timeout(exception):
        return True
    status = status_code(exception)
    if status is None:
        return False
    return status >= 500 or status in RETRYABLE_STATUS_CODES


def is_timeout(exception: Exception) -> bool:
    return isinstance(exception, (TimeoutError, httpx.TimeoutException))


def retry_delay_seconds(attempt: int, rate_limited: bool = False) -> float:
    # 무료 티어의 분당 한도는 짧은 백오프로는 풀리지 않으므로 더 오래 기다린다.
    if rate_limited:
        return min(5.0 * (2**attempt), 30.0)
    return min(0.25 * (2**attempt), 2.0)


def is_truncated(response: Any) -> bool:
    for candidate in getattr(response, "candidates", None) or ():
        reason = getattr(candidate, "finish_reason", None)
        if reason is None:
            continue
        if str(getattr(reason, "name", reason)).upper().endswith(MAX_TOKENS_FINISH_REASON):
            return True
    return False


def create_sdk_client(api_key: str, timeout_seconds: float) -> GeminiSdkClient:
    from google import genai
    from google.genai import types

    return genai.Client(
        api_key=api_key,
        http_options=types.HttpOptions(timeout=int(timeout_seconds * 1000)),
    )


def response_config(max_output_tokens: int, response_schema: Any | None = None) -> Any:
    from google.genai import types

    # Gemini 3.5 Flash는 기본적으로 medium 수준의 thinking을 사용한다.
    # 짧은 구조화 분석에는 low가 적절하며, 출력 토큰을 명시해 JSON이 중간에
    # 잘리는 일을 줄인다. response_schema를 주면 스키마 위반 재시도가 줄어든다.
    return types.GenerateContentConfig(
        response_mime_type="application/json",
        response_schema=response_schema,
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
