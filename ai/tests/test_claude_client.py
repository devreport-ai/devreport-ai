from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any
from uuid import UUID

import anthropic
import httpx
import pytest
from pydantic import BaseModel

from app.clients.claude_client import ClaudeClient, content_blocks, verify_anthropic_api_key
from app.core.deadline import Deadline
from app.core.errors import AIServiceError, ErrorCode
from app.schemas.analysis import ImageEvidence


@dataclass
class TextBlock:
    text: str
    type: str = "text"


@dataclass
class Response:
    content: list[TextBlock]
    stop_reason: str = "end_turn"


def response(text: str, stop_reason: str = "end_turn") -> Response:
    return Response([TextBlock(text)], stop_reason)


class FakeStream:
    def __init__(self, result: Response) -> None:
        self.result = result

    def __enter__(self) -> "FakeStream":
        return self

    def __exit__(self, *args: object) -> None:
        return None

    def get_final_message(self) -> Response:
        return self.result


@dataclass
class FakeMessages:
    results: list[Response | Exception]
    parse_calls: list[dict[str, Any]] = field(default_factory=list)
    stream_calls: list[dict[str, Any]] = field(default_factory=list)

    def _next(self) -> Response:
        result = self.results.pop(0)
        if isinstance(result, Exception):
            raise result
        return result

    def parse(self, **kwargs: Any) -> Response:
        self.parse_calls.append(kwargs)
        return self._next()

    def stream(self, **kwargs: Any) -> FakeStream:
        self.stream_calls.append(kwargs)
        return FakeStream(self._next())


class FakeClient:
    def __init__(self, results: list[Response | Exception]) -> None:
        self.messages = FakeMessages(list(results))


class Analysis(BaseModel):
    summary: str


def api_error(cls: type[anthropic.APIStatusError], status: int) -> anthropic.APIStatusError:
    request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
    http_response = httpx.Response(status, request=request)
    return cls("secret-detail sk-ant-abcdefghijklmnopqrstuvwxyz", response=http_response, body=None)


def client(
    *results: Response | Exception,
    api_key: str | None = "sk-ant-test",
    max_retries: int = 2,
    sleeper: Callable[[float], None] | None = None,
    deadline: Deadline | None = None,
) -> tuple[ClaudeClient, FakeClient]:
    fake = FakeClient(list(results))
    return (
        ClaudeClient(
            api_key=api_key,
            model="claude-sonnet-5",
            timeout_seconds=10,
            max_retries=max_retries,
            client_factory=lambda _key, _timeout: fake,
            sleeper=sleeper or (lambda _seconds: None),
            deadline=deadline,
        ),
        fake,
    )


def test_rejects_missing_api_key_as_credential_error():
    claude, fake = client(response("{}"), api_key=None)
    with pytest.raises(AIServiceError) as raised:
        claude.generate_json("prompt")

    assert raised.value.code == ErrorCode.AI_CREDENTIAL_INVALID
    assert fake.messages.parse_calls == []


def test_uses_structured_output_parse_for_pydantic_models():
    claude, fake = client(response('{"summary":"ok"}'))

    result = claude.generate_json(
        "prompt", response_model=Analysis, response_validator=Analysis.model_validate_json
    )

    assert result == Analysis(summary="ok")
    call = fake.messages.parse_calls[0]
    assert call["output_format"] is Analysis
    assert call["model"] == "claude-sonnet-5"
    assert call["messages"] == [{"role": "user", "content": "prompt"}]
    assert fake.messages.stream_calls == []


def test_streams_the_document_stage_without_structured_output():
    claude, fake = client(response('{"metadata":{}}'))

    result = claude.generate_json("document prompt", max_output_tokens=32_768)

    assert result == '{"metadata":{}}'
    assert fake.messages.parse_calls == []
    assert fake.messages.stream_calls[0]["max_tokens"] == 32_768


def test_sends_images_as_base64_blocks_before_the_prompt():
    image = ImageEvidence(
        file_id=UUID("00000000-0000-4000-8000-000000000001"),
        path="images/a.png",
        mime_type="image/png",
        content=b"\x89PNG",
    )
    blocks = content_blocks("prompt", (image,))

    assert blocks[0]["type"] == "image"
    assert blocks[0]["source"] == {"type": "base64", "media_type": "image/png", "data": "iVBORw=="}
    assert blocks[-1] == {"type": "text", "text": "prompt"}


def test_retries_invalid_json_and_tells_the_model_why():
    claude, fake = client(response("not json"), response('{"summary":"ok"}'))

    result = claude.generate_json("prompt", response_model=Analysis)

    assert result == '{"summary":"ok"}'
    assert "JSON 객체가 아닌 응답이었다" in fake.messages.parse_calls[1]["messages"][0]["content"]


def test_fails_fast_when_output_is_truncated():
    claude, _ = client(response('{"summary":', "max_tokens"), response("{}"))
    with pytest.raises(AIServiceError) as raised:
        claude.generate_json("prompt")

    assert raised.value.code == ErrorCode.AI_INVALID_RESPONSE


def test_converts_refusal_to_generation_failed():
    claude, _ = client(response("", "refusal"))
    with pytest.raises(AIServiceError) as raised:
        claude.generate_json("prompt")

    assert raised.value.code == ErrorCode.AI_GENERATION_FAILED


@pytest.mark.parametrize(
    ("error", "code"),
    [
        (api_error(anthropic.AuthenticationError, 401), ErrorCode.AI_CREDENTIAL_INVALID),
        (api_error(anthropic.PermissionDeniedError, 403), ErrorCode.AI_CREDENTIAL_INVALID),
        (api_error(anthropic.BadRequestError, 400), ErrorCode.AI_UNAVAILABLE),
    ],
)
def test_converts_non_retryable_sdk_errors_without_echoing_details(
    error: Exception, code: ErrorCode
):
    claude, fake = client(error, response("{}"))
    with pytest.raises(AIServiceError) as raised:
        claude.generate_json("prompt")

    assert raised.value.code == code
    assert "sk-ant-" not in raised.value.message
    assert len(fake.messages.stream_calls) == 1


def test_retries_rate_limit_then_reports_provider_rate_limited():
    delays: list[float] = []
    claude, _ = client(
        api_error(anthropic.RateLimitError, 429),
        api_error(anthropic.RateLimitError, 429),
        max_retries=1,
        sleeper=delays.append,
    )
    with pytest.raises(AIServiceError) as raised:
        claude.generate_json("prompt")

    assert raised.value.code == ErrorCode.AI_PROVIDER_RATE_LIMITED
    assert len(delays) == 1


def test_retries_server_errors_and_timeouts():
    request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
    claude, _ = client(
        api_error(anthropic.InternalServerError, 500),
        anthropic.APITimeoutError(request=request),
        response("{}"),
        max_retries=2,
    )

    assert claude.generate_json("prompt") == "{}"


def test_converts_exhausted_timeouts_to_ai_timeout():
    request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
    claude, _ = client(
        anthropic.APITimeoutError(request=request),
        anthropic.APITimeoutError(request=request),
        max_retries=1,
    )
    with pytest.raises(AIServiceError) as raised:
        claude.generate_json("prompt")

    assert raised.value.code == ErrorCode.AI_TIMEOUT


def test_stops_before_calling_claude_when_the_deadline_expired():
    deadline = Deadline(0.0)
    claude, fake = client(response("{}"), deadline=deadline)
    with pytest.raises(AIServiceError) as raised:
        claude.generate_json("prompt")

    assert raised.value.code == ErrorCode.AI_TIMEOUT
    assert fake.messages.stream_calls == []


class FakeModels:
    def __init__(self, error: Exception | None = None) -> None:
        self.error = error
        self.calls: list[dict[str, object]] = []

    def list(self, **kwargs: object) -> list[object]:
        self.calls.append(kwargs)
        if self.error is not None:
            raise self.error
        return [object()]


class FakeVerifyClient:
    def __init__(self, error: Exception | None = None) -> None:
        self.models = FakeModels(error)


def test_verify_api_key_lists_models_with_the_given_key():
    captured: dict[str, object] = {}

    def factory(key: str, timeout: float) -> FakeVerifyClient:
        captured["key"] = key
        captured["timeout"] = timeout
        return FakeVerifyClient()

    verify_anthropic_api_key("sk-ant-user", 7.5, client_factory=factory)

    assert captured == {"key": "sk-ant-user", "timeout": 7.5}


@pytest.mark.parametrize(
    ("error", "code"),
    [
        (api_error(anthropic.AuthenticationError, 401), ErrorCode.AI_CREDENTIAL_INVALID),
        (api_error(anthropic.RateLimitError, 429), ErrorCode.AI_PROVIDER_RATE_LIMITED),
        (api_error(anthropic.InternalServerError, 500), ErrorCode.AI_UNAVAILABLE),
    ],
)
def test_verify_api_key_converts_provider_failures(error: Exception, code: ErrorCode):
    with pytest.raises(AIServiceError) as raised:
        verify_anthropic_api_key(
            "sk-ant-user", 5, client_factory=lambda _k, _t: FakeVerifyClient(error)
        )

    assert raised.value.code == code
