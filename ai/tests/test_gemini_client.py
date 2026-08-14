from collections.abc import Callable
from dataclasses import dataclass

import httpx
import pytest

from app.clients.gemini_client import JSON_MAX_OUTPUT_TOKENS, GeminiClient, response_config
from app.core.errors import AIServiceError, ErrorCode


@dataclass
class Response:
    text: str | None


class FakeModels:
    def __init__(self, results: list[Response | Exception]) -> None:
        self.results = results
        self.calls: list[dict[str, object]] = []

    def generate_content(self, *, model: str, contents: object, config: object) -> Response:
        self.calls.append({"model": model, "contents": contents, "config": config})
        result = self.results.pop(0)
        if isinstance(result, Exception):
            raise result
        return result


class FakeClient:
    def __init__(self, results: list[Response | Exception]) -> None:
        self.models = FakeModels(results)


class HttpError(Exception):
    def __init__(self, code: int) -> None:
        self.code = code


def client(
    *results: Response | Exception,
    api_key: str | None = "secret",
    max_retries: int = 2,
    sleeper: Callable[[float], None] | None = None,
) -> GeminiClient:
    return GeminiClient(
        api_key=api_key,
        model="gemini-test",
        timeout_seconds=10,
        max_retries=max_retries,
        client_factory=lambda _key, _timeout: FakeClient(list(results)),
        sleeper=sleeper or (lambda _seconds: None),
    )


def test_rejects_missing_api_key_without_creating_sdk_client():
    with pytest.raises(AIServiceError) as raised:
        client(Response("{}"), api_key=None).generate_json("prompt")

    assert raised.value.code == ErrorCode.AI_UNAVAILABLE


def test_returns_json_text_from_sdk_response():
    assert client(Response('{"result":true}')).generate_json("prompt") == '{"result":true}'


def test_uses_low_thinking_and_sufficient_output_limit_for_json_responses():
    config = response_config()

    assert config.response_mime_type == "application/json"
    assert config.max_output_tokens == JSON_MAX_OUTPUT_TOKENS
    assert config.thinking_config.thinking_level.value.lower() == "low"


def test_converts_timeout_to_ai_timeout():
    with pytest.raises(AIServiceError) as raised:
        client(TimeoutError(), max_retries=0).generate_json("prompt")

    assert raised.value.code == ErrorCode.AI_TIMEOUT


def test_converts_sdk_failure_without_exposing_exception_message():
    with pytest.raises(AIServiceError) as raised:
        client(RuntimeError("secret SDK detail"), max_retries=0).generate_json("prompt")

    assert raised.value.code == ErrorCode.AI_UNAVAILABLE
    assert "secret SDK detail" not in raised.value.message


def test_rejects_empty_response_text():
    with pytest.raises(AIServiceError) as raised:
        client(Response(" "), max_retries=0).generate_json("prompt")

    assert raised.value.code == ErrorCode.AI_INVALID_RESPONSE


def test_rejects_non_json_response_text():
    with pytest.raises(AIServiceError) as raised:
        client(Response("not-json"), max_retries=0).generate_json("prompt")

    assert raised.value.code == ErrorCode.AI_INVALID_RESPONSE


def test_retries_malformed_json_response_before_failing():
    result = client(Response("not-json"), Response("{}"), max_retries=1).generate_json("prompt")

    assert result == "{}"


def test_converts_client_initialization_failure_to_ai_unavailable():
    def failing_factory(_api_key: str, _timeout_seconds: float) -> FakeClient:
        raise RuntimeError("secret SDK detail")

    gemini_client = GeminiClient(
        api_key="secret",
        model="gemini-test",
        timeout_seconds=10,
        max_retries=0,
        client_factory=failing_factory,
    )

    with pytest.raises(AIServiceError) as raised:
        gemini_client.generate_json("prompt")

    assert raised.value.code == ErrorCode.AI_UNAVAILABLE
    assert "secret SDK detail" not in raised.value.message


def test_retries_httpx_timeout_and_converts_it_to_ai_timeout():
    delays: list[float] = []

    with pytest.raises(AIServiceError) as raised:
        client(
            httpx.TimeoutException("timed out"),
            httpx.TimeoutException("timed out"),
            max_retries=1,
            sleeper=delays.append,
        ).generate_json("prompt")

    assert raised.value.code == ErrorCode.AI_TIMEOUT
    assert delays == [0.25]


def test_retries_transient_sdk_failure_before_returning_response():
    result = client(HttpError(503), Response("{}"), max_retries=1).generate_json("prompt")

    assert result == "{}"


def test_does_not_retry_non_retryable_sdk_error():
    with pytest.raises(AIServiceError) as raised:
        client(HttpError(401), Response("{}"), max_retries=1).generate_json("prompt")

    assert raised.value.code == ErrorCode.AI_UNAVAILABLE


def test_applies_exponential_backoff_between_retryable_failures():
    delays: list[float] = []

    gemini_client = client(HttpError(503), Response("{}"), max_retries=1, sleeper=delays.append)

    result = gemini_client.generate_json("prompt")

    assert result == "{}"
    assert delays == [0.25]
