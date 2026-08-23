from __future__ import annotations

import base64
import logging
from collections.abc import Callable, Sequence
from time import sleep
from typing import Any

from pydantic import BaseModel

from app.clients.gemini_client import is_json_object, retry_delay_seconds, retry_prompt
from app.clients.structured_client import ANALYSIS_MAX_OUTPUT_TOKENS
from app.core.deadline import Deadline
from app.core.errors import AIServiceError, ErrorCode
from app.schemas.analysis import ImageEvidence, PdfEvidence

log = logging.getLogger(__name__)

# Claude API는 thinking이 기본이며 Sonnet 5는 adaptive만 지원한다. 분석 단계는 구조화 출력이라
# 깊은 추론이 필요 없어 effort를 낮춰 비용·지연을 줄인다.
ANALYSIS_EFFORT = "low"
DOCUMENT_EFFORT = "medium"
REFUSAL_STOP_REASON = "refusal"
MAX_TOKENS_STOP_REASON = "max_tokens"


class ClaudeClient:
    """공식 anthropic SDK를 AI 내부 오류 계약 뒤에 감싼 최소 호출 Client."""

    def __init__(
        self,
        api_key: str | None,
        model: str,
        timeout_seconds: float,
        max_retries: int,
        client_factory: Callable[[str, float], Any] | None = None,
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
        pdfs: Sequence[PdfEvidence] = (),
        max_output_tokens: int = ANALYSIS_MAX_OUTPUT_TOKENS,
        response_model: type[BaseModel] | None = None,
        response_validator: Callable[[str], Any] | None = None,
    ) -> str | Any:
        if not self._api_key:
            raise AIServiceError(ErrorCode.AI_CREDENTIAL_INVALID, "Anthropic API Key가 없습니다.")

        try:
            client = self._client_factory(self._api_key, self._timeout_seconds)
        except Exception as exception:
            raise AIServiceError(
                ErrorCode.AI_UNAVAILABLE, "Claude 서비스를 사용할 수 없습니다."
            ) from exception

        rejection: str | None = None
        for attempt in range(self._max_retries + 1):
            call_timeout = self._call_timeout()
            messages = [
                {
                    "role": "user",
                    "content": content_blocks(retry_prompt(prompt, rejection), images, pdfs),
                }
            ]
            try:
                response = self._generate_once(
                    client, messages, max_output_tokens, response_model, call_timeout
                )
            except AIServiceError:
                raise
            except Exception as exception:
                if not is_retryable(exception) or attempt == self._max_retries:
                    raise_generation_error(exception)
                log.warning("Claude 호출 실패로 재시도한다 attempt=%d", attempt + 1)
                self._wait(retry_delay_seconds(attempt, is_rate_limited(exception)))
                continue

            stop_reason = getattr(response, "stop_reason", None)
            if stop_reason == MAX_TOKENS_STOP_REASON:
                # 같은 프롬프트로 다시 물어도 같은 길이에서 잘리므로 재시도하지 않는다.
                raise AIServiceError(
                    ErrorCode.AI_INVALID_RESPONSE, "Claude 응답이 출력 토큰 한도에서 잘렸습니다."
                )
            if stop_reason == REFUSAL_STOP_REASON:
                raise AIServiceError(
                    ErrorCode.AI_GENERATION_FAILED, "Claude가 이 자료에 대한 생성을 거부했습니다."
                )

            text = response_text(response)
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

            log.warning("Claude 응답 검증 실패 attempt=%d", attempt + 1)
            if attempt < self._max_retries:
                self._wait(retry_delay_seconds(attempt, rate_limited=False))

        raise AIServiceError(ErrorCode.AI_INVALID_RESPONSE, "Claude 응답 형식이 올바르지 않습니다.")

    def _generate_once(
        self,
        client: Any,
        messages: list[dict[str, Any]],
        max_output_tokens: int,
        response_model: type[BaseModel] | None,
        timeout_seconds: float,
    ) -> Any:
        if response_model is not None:
            # 구조화 출력: SDK가 Pydantic 모델을 JSON Schema로 바꿔 output_config.format에 싣는다.
            return client.messages.parse(
                model=self._model,
                max_tokens=max_output_tokens,
                messages=messages,
                output_format=response_model,
                output_config={"effort": ANALYSIS_EFFORT},
                timeout=timeout_seconds,
            )
        # 계약 스키마는 블록 oneOf를 쓰므로 structured output 대신 프롬프트로 형식을 고정한다.
        # 문서 단계는 출력이 길어 스트리밍으로 받아 HTTP 타임아웃을 피한다.
        with client.messages.stream(
            model=self._model,
            max_tokens=max_output_tokens,
            messages=messages,
            output_config={"effort": DOCUMENT_EFFORT},
            timeout=timeout_seconds,
        ) as stream:
            return stream.get_final_message()

    def _call_timeout(self) -> float:
        if self._deadline is None:
            return self._timeout_seconds
        self._deadline.ensure_active()
        return min(self._timeout_seconds, self._deadline.remaining())

    def _wait(self, seconds: float) -> None:
        if self._deadline is not None:
            self._deadline.ensure_active()
            seconds = min(seconds, self._deadline.remaining())
        if seconds > 0:
            self._sleeper(seconds)


def create_sdk_client(api_key: str, timeout_seconds: float) -> Any:
    import anthropic

    # 재시도는 이 모듈이 예산 안에서 직접 제어한다.
    return anthropic.Anthropic(api_key=api_key, timeout=timeout_seconds, max_retries=0)


def content_blocks(
    prompt: str, images: Sequence[ImageEvidence], pdfs: Sequence[PdfEvidence] = ()
) -> str | list[dict[str, Any]]:
    """텍스트만 있으면 문자열을 유지하고, 파일은 요청 수명 안의 base64로만 전달한다.

    PDF는 document block(base64 application/pdf)으로 넣어 Claude가 직접 읽게 한다.
    """
    if not images and not pdfs:
        return prompt
    return [
        *(
            {
                "type": "document",
                "source": {
                    "type": "base64",
                    "media_type": pdf.mime_type,
                    "data": base64.standard_b64encode(pdf.content).decode("ascii"),
                },
            }
            for pdf in pdfs
        ),
        *(
            {
                "type": "image",
                "source": {
                    "type": "base64",
                    "media_type": image.mime_type,
                    "data": base64.standard_b64encode(image.content).decode("ascii"),
                },
            }
            for image in images
        ),
        {"type": "text", "text": prompt},
    ]


def response_text(response: Any) -> str | None:
    for block in getattr(response, "content", None) or ():
        if getattr(block, "type", None) == "text":
            return getattr(block, "text", None)
    return None


def status_code(exception: Exception) -> int | None:
    status = getattr(exception, "status_code", None)
    return status if isinstance(status, int) else None


def is_timeout(exception: Exception) -> bool:
    import anthropic

    return isinstance(exception, (TimeoutError, anthropic.APITimeoutError))


def is_rate_limited(exception: Exception) -> bool:
    import anthropic

    return isinstance(exception, anthropic.RateLimitError) or status_code(exception) == 429


def is_credential_error(exception: Exception) -> bool:
    import anthropic

    return isinstance(exception, (anthropic.AuthenticationError, anthropic.PermissionDeniedError))


def is_retryable(exception: Exception) -> bool:
    import anthropic

    if is_timeout(exception) or is_rate_limited(exception):
        return True
    if isinstance(exception, anthropic.APIConnectionError):
        return True
    status = status_code(exception)
    return status is not None and (status >= 500 or status == 408)


def raise_generation_error(exception: Exception) -> None:
    # 예외 메시지는 provider 내부 정보·키 조각을 담을 수 있으므로 밖으로 내보내지 않는다.
    if is_timeout(exception):
        raise AIServiceError(
            ErrorCode.AI_TIMEOUT, "Claude 응답 시간이 초과되었습니다."
        ) from exception
    if is_credential_error(exception):
        raise AIServiceError(
            ErrorCode.AI_CREDENTIAL_INVALID, "Anthropic API Key가 거부되었습니다."
        ) from exception
    if is_rate_limited(exception):
        raise AIServiceError(
            ErrorCode.AI_PROVIDER_RATE_LIMITED, "Claude 사용량 한도를 초과했습니다."
        ) from exception
    raise AIServiceError(
        ErrorCode.AI_UNAVAILABLE, "Claude 서비스를 사용할 수 없습니다."
    ) from exception


def verify_anthropic_api_key(
    api_key: str,
    timeout_seconds: float,
    client_factory: Callable[[str, float], Any] | None = None,
) -> None:
    """모델 목록 한 페이지를 조회해 키가 유효한지 확인한다. 실패는 AI 오류 계약으로 변환한다."""
    factory = client_factory or create_sdk_client
    try:
        client = factory(api_key, timeout_seconds)
        client.models.list(limit=1)
    except AIServiceError:
        raise
    except Exception as exception:
        if is_timeout(exception):
            raise AIServiceError(
                ErrorCode.AI_TIMEOUT, "Claude 응답 시간이 초과되었습니다."
            ) from exception
        if is_credential_error(exception):
            raise AIServiceError(
                ErrorCode.AI_CREDENTIAL_INVALID, "Anthropic API Key가 올바르지 않습니다."
            ) from exception
        if is_rate_limited(exception):
            raise AIServiceError(
                ErrorCode.AI_PROVIDER_RATE_LIMITED, "Claude 사용량 한도를 초과했습니다."
            ) from exception
        raise AIServiceError(
            ErrorCode.AI_UNAVAILABLE, "Claude 서비스를 사용할 수 없습니다."
        ) from exception
