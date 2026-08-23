from collections.abc import Callable
from dataclasses import dataclass

import httpx
import pytest

from app.clients.gemini_client import (
    ANALYSIS_MAX_OUTPUT_TOKENS,
    GeminiClient,
    response_config,
    retry_delay_seconds,
    verify_gemini_api_key,
)
from app.core.deadline import Deadline
from app.core.errors import AIServiceError, ErrorCode


@dataclass
class Candidate:
    finish_reason: str


@dataclass
class Response:
    text: str | None
    candidates: list[Candidate] | None = None


def truncated_response() -> Response:
    return Response('{"partial":', [Candidate("MAX_TOKENS")])


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


class ApiKeyError(Exception):
    def __init__(self, code: int, message: str) -> None:
        super().__init__(message)
        self.code = code


def client(
    *results: Response | Exception,
    api_key: str | None = "secret",
    max_retries: int = 2,
    sleeper: Callable[[float], None] | None = None,
    deadline: Deadline | None = None,
) -> GeminiClient:
    return GeminiClient(
        api_key=api_key,
        model="gemini-test",
        timeout_seconds=10,
        max_retries=max_retries,
        client_factory=lambda _key, _timeout: FakeClient(list(results)),
        sleeper=sleeper or (lambda _seconds: None),
        deadline=deadline,
    )


def test_rejects_missing_api_key_without_creating_sdk_client():
    with pytest.raises(AIServiceError) as raised:
        client(Response("{}"), api_key=None).generate_json("prompt")

    assert raised.value.code == ErrorCode.AI_UNAVAILABLE


def test_returns_json_text_from_sdk_response():
    assert client(Response('{"result":true}')).generate_json("prompt") == '{"result":true}'


def test_uses_low_thinking_and_sufficient_output_limit_for_json_responses():
    config = response_config(ANALYSIS_MAX_OUTPUT_TOKENS)

    assert config.response_mime_type == "application/json"
    assert config.max_output_tokens == ANALYSIS_MAX_OUTPUT_TOKENS
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


def test_uses_one_retry_budget_for_transport_failures():
    sdk_client = FakeClient([HttpError(503), HttpError(503), HttpError(503)])
    gemini_client = GeminiClient(
        api_key="secret",
        model="gemini-test",
        timeout_seconds=10,
        max_retries=2,
        client_factory=lambda _key, _timeout: sdk_client,
        sleeper=lambda _seconds: None,
    )

    with pytest.raises(AIServiceError) as raised:
        gemini_client.generate_json("prompt")

    assert raised.value.code == ErrorCode.AI_UNAVAILABLE
    assert len(sdk_client.models.calls) == 3


def test_retries_invalid_validated_response_with_the_same_budget():
    sdk_client = FakeClient([Response('{"invalid":true}'), Response('{"result":true}')])
    gemini_client = GeminiClient(
        api_key="secret",
        model="gemini-test",
        timeout_seconds=10,
        max_retries=1,
        client_factory=lambda _key, _timeout: sdk_client,
        sleeper=lambda _seconds: None,
    )
    validations = 0

    def validate(text: str) -> str:
        nonlocal validations
        validations += 1
        if validations == 1:
            raise AIServiceError(ErrorCode.AI_INVALID_RESPONSE, "invalid schema")
        return text

    assert gemini_client.generate_json("prompt", response_validator=validate) == '{"result":true}'
    assert len(sdk_client.models.calls) == 2


def test_does_not_retry_non_retryable_sdk_error():
    with pytest.raises(AIServiceError) as raised:
        client(HttpError(404), Response("{}"), max_retries=1).generate_json("prompt")

    assert raised.value.code == ErrorCode.AI_UNAVAILABLE


@pytest.mark.parametrize(
    "error",
    [
        HttpError(401),
        HttpError(403),
        ApiKeyError(400, "API key not valid. Please pass a valid API key."),
    ],
)
def test_converts_rejected_api_key_to_credential_error(error: Exception):
    with pytest.raises(AIServiceError) as raised:
        client(error, Response("{}"), max_retries=1).generate_json("prompt")

    assert raised.value.code == ErrorCode.AI_CREDENTIAL_INVALID
    assert "API key not valid" not in raised.value.message


def test_converts_exhausted_rate_limit_retries_to_provider_rate_limited():
    with pytest.raises(AIServiceError) as raised:
        client(HttpError(429), HttpError(429), max_retries=1).generate_json("prompt")

    assert raised.value.code == ErrorCode.AI_PROVIDER_RATE_LIMITED


class FakeModelLister:
    def __init__(self, error: Exception | None = None) -> None:
        self.error = error
        self.calls: list[dict[str, object]] = []

    def list(self, *, config: dict[str, object]) -> list[object]:
        self.calls.append(config)
        if self.error is not None:
            raise self.error
        return [object()]


class FakeVerifyClient:
    def __init__(self, error: Exception | None = None) -> None:
        self.models = FakeModelLister(error)


def test_verify_api_key_lists_one_model_page_with_the_given_key():
    captured: dict[str, object] = {}

    def factory(key: str, timeout: float) -> FakeVerifyClient:
        captured["key"] = key
        captured["timeout"] = timeout
        return FakeVerifyClient()

    verify_gemini_api_key("user-key", 7.5, client_factory=factory)

    assert captured == {"key": "user-key", "timeout": 7.5}


@pytest.mark.parametrize(
    ("error", "code"),
    [
        (HttpError(401), ErrorCode.AI_CREDENTIAL_INVALID),
        (ApiKeyError(400, "API_KEY_INVALID"), ErrorCode.AI_CREDENTIAL_INVALID),
        (HttpError(429), ErrorCode.AI_PROVIDER_RATE_LIMITED),
        (httpx.ReadTimeout("slow"), ErrorCode.AI_TIMEOUT),
        (HttpError(500), ErrorCode.AI_UNAVAILABLE),
    ],
)
def test_verify_api_key_converts_provider_failures(error: Exception, code: ErrorCode):
    with pytest.raises(AIServiceError) as raised:
        verify_gemini_api_key("user-key", 5, client_factory=lambda _k, _t: FakeVerifyClient(error))

    assert raised.value.code == code


def test_applies_exponential_backoff_between_retryable_failures():
    delays: list[float] = []

    gemini_client = client(HttpError(503), Response("{}"), max_retries=1, sleeper=delays.append)

    result = gemini_client.generate_json("prompt")

    assert result == "{}"
    assert delays == [0.25]


def test_retries_rate_limited_call_with_a_longer_backoff():
    # 무료 티어의 분당 한도는 짧은 백오프로 풀리지 않는다.
    delays: list[float] = []

    result = client(
        HttpError(429), Response("{}"), max_retries=1, sleeper=delays.append
    ).generate_json("prompt")

    assert result == "{}"
    assert delays == [retry_delay_seconds(0, rate_limited=True)]
    assert delays[0] > retry_delay_seconds(0)


def test_retries_request_timeout_status():
    assert client(HttpError(408), Response("{}"), max_retries=1).generate_json("prompt") == "{}"


def test_fails_fast_when_the_response_hits_the_output_token_limit():
    # 같은 프롬프트로 다시 물어도 같은 지점에서 잘리므로 재시도가 의미 없다.
    sdk_client = FakeClient([truncated_response(), Response("{}")])
    gemini_client = GeminiClient(
        api_key="secret",
        model="gemini-test",
        timeout_seconds=10,
        max_retries=1,
        client_factory=lambda _key, _timeout: sdk_client,
        sleeper=lambda _seconds: None,
    )

    with pytest.raises(AIServiceError) as raised:
        gemini_client.generate_json("prompt")

    assert raised.value.code == ErrorCode.AI_INVALID_RESPONSE
    assert len(sdk_client.models.calls) == 1


def test_stops_before_calling_gemini_when_the_generation_deadline_expired():
    sdk_client = FakeClient([Response("{}")])
    gemini_client = GeminiClient(
        api_key="secret",
        model="gemini-test",
        timeout_seconds=10,
        max_retries=1,
        client_factory=lambda _key, _timeout: sdk_client,
        sleeper=lambda _seconds: None,
        deadline=Deadline(0.0),
    )

    with pytest.raises(AIServiceError) as raised:
        gemini_client.generate_json("prompt")

    assert raised.value.code == ErrorCode.AI_TIMEOUT
    assert sdk_client.models.calls == []


def test_tells_the_model_why_the_previous_response_was_rejected():
    sdk_client = FakeClient([Response("not-json"), Response("{}")])
    gemini_client = GeminiClient(
        api_key="secret",
        model="gemini-test",
        timeout_seconds=10,
        max_retries=1,
        client_factory=lambda _key, _timeout: sdk_client,
        sleeper=lambda _seconds: None,
    )

    assert gemini_client.generate_json("prompt") == "{}"

    retried_prompt = sdk_client.models.calls[1]["contents"]
    assert "prompt" in retried_prompt
    assert "직전 응답은 다음 이유로 거절되었다" in retried_prompt


def test_passes_response_schema_to_the_sdk_config():
    sdk_client = FakeClient([Response("{}")])
    gemini_client = GeminiClient(
        api_key="secret",
        model="gemini-test",
        timeout_seconds=10,
        max_retries=0,
        client_factory=lambda _key, _timeout: sdk_client,
    )
    schema = {"type": "object", "properties": {"summary": {"type": "string"}}}

    gemini_client.generate_json("prompt", response_schema=schema)

    assert sdk_client.models.calls[0]["config"].response_schema == schema


def test_uses_a_larger_output_limit_for_the_final_document_than_for_analysis():
    from app.clients.gemini_client import REPORT_DOCUMENT_MAX_OUTPUT_TOKENS

    assert REPORT_DOCUMENT_MAX_OUTPUT_TOKENS > ANALYSIS_MAX_OUTPUT_TOKENS


def test_never_lets_a_single_call_outlive_the_remaining_generation_budget():
    sdk_client = FakeClient([Response("{}")])
    gemini_client = GeminiClient(
        api_key="secret",
        model="gemini-test",
        timeout_seconds=120,
        max_retries=0,
        client_factory=lambda _key, _timeout: sdk_client,
        deadline=Deadline(5.0),
    )

    gemini_client.generate_json("prompt")

    # SDK timeout 단위는 밀리초다.
    timeout_ms = sdk_client.models.calls[0]["config"].http_options.timeout
    assert 0 < timeout_ms <= 5_000


def test_uses_the_call_timeout_when_no_deadline_is_set():
    sdk_client = FakeClient([Response("{}")])
    gemini_client = GeminiClient(
        api_key="secret",
        model="gemini-test",
        timeout_seconds=90,
        max_retries=0,
        client_factory=lambda _key, _timeout: sdk_client,
    )

    gemini_client.generate_json("prompt")

    assert sdk_client.models.calls[0]["config"].http_options.timeout == 90_000


def test_raises_timeout_when_the_budget_runs_out_between_retries():
    # 재시도 대기 중에 예산이 끝나면 AI_UNAVAILABLE이 아니라 AI_TIMEOUT이어야 한다.
    now = [0.0]
    deadline = Deadline(10.0, clock=lambda: now[0])
    sdk_client = FakeClient([HttpError(503), Response("{}")])

    def advance_past_deadline(_seconds: float) -> None:
        now[0] = 20.0

    gemini_client = GeminiClient(
        api_key="secret",
        model="gemini-test",
        timeout_seconds=5,
        max_retries=1,
        client_factory=lambda _key, _timeout: sdk_client,
        sleeper=advance_past_deadline,
        deadline=deadline,
    )

    with pytest.raises(AIServiceError) as raised:
        gemini_client.generate_json("prompt")

    assert raised.value.code == ErrorCode.AI_TIMEOUT
    # 예산이 끝난 뒤에는 SDK를 다시 부르지 않는다.
    assert len(sdk_client.models.calls) == 1


def test_never_sends_a_zero_timeout_to_the_sdk():
    now = [0.0]
    sdk_client = FakeClient([Response("{}")])
    gemini_client = GeminiClient(
        api_key="secret",
        model="gemini-test",
        timeout_seconds=90,
        max_retries=0,
        client_factory=lambda _key, _timeout: sdk_client,
        deadline=Deadline(3.0, clock=lambda: now[0]),
    )

    gemini_client.generate_json("prompt")

    assert sdk_client.models.calls[0]["config"].http_options.timeout == 3_000
