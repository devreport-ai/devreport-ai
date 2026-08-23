import asyncio
import json
from collections import deque
from collections.abc import Awaitable, Callable
from typing import Any

from app.core.request_body_limit import RequestBodyLimitMiddleware


def test_rejects_oversized_content_length_before_calling_application():
    called = False

    async def downstream(
        _scope: dict[str, Any],
        _receive: Callable[[], Awaitable[dict[str, Any]]],
        _send: Callable[[dict[str, Any]], Awaitable[None]],
    ) -> None:
        nonlocal called
        called = True

    sent = run_asgi(
        RequestBodyLimitMiddleware(downstream, max_body_size=5, path="/generate"),
        path="/generate",
        headers=[(b"content-length", b"6")],
        messages=[],
    )

    assert called is False
    assert status_code(sent) == 413
    assert error_code(sent) == "AI_INVALID_REQUEST"


def test_rejects_chunked_body_when_received_bytes_exceed_limit():
    called = False

    async def downstream(
        _scope: dict[str, Any],
        receive: Callable[[], Awaitable[dict[str, Any]]],
        _send: Callable[[dict[str, Any]], Awaitable[None]],
    ) -> None:
        nonlocal called
        called = True
        while (await receive()).get("more_body", False):
            pass

    sent = run_asgi(
        RequestBodyLimitMiddleware(downstream, max_body_size=5, path="/generate"),
        path="/generate",
        headers=[],
        messages=[
            {"type": "http.request", "body": b"123", "more_body": True},
            {"type": "http.request", "body": b"456", "more_body": False},
        ],
    )

    assert called is True
    assert status_code(sent) == 413
    assert error_code(sent) == "AI_INVALID_REQUEST"


def test_forwards_request_within_limit_to_application():
    async def downstream(
        _scope: dict[str, Any],
        receive: Callable[[], Awaitable[dict[str, Any]]],
        send: Callable[[dict[str, Any]], Awaitable[None]],
    ) -> None:
        message = await receive()
        assert message["body"] == b"12345"
        await send({"type": "http.response.start", "status": 204, "headers": []})
        await send({"type": "http.response.body", "body": b""})

    sent = run_asgi(
        RequestBodyLimitMiddleware(downstream, max_body_size=5, path="/generate"),
        path="/generate",
        headers=[(b"content-length", b"5")],
        messages=[{"type": "http.request", "body": b"12345", "more_body": False}],
    )

    assert status_code(sent) == 204


def run_asgi(
    app: RequestBodyLimitMiddleware,
    path: str,
    headers: list[tuple[bytes, bytes]],
    messages: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    pending_messages = deque(messages)
    sent: list[dict[str, Any]] = []

    async def receive() -> dict[str, Any]:
        return pending_messages.popleft() if pending_messages else {"type": "http.disconnect"}

    async def send(message: dict[str, Any]) -> None:
        sent.append(message)

    asyncio.run(app({"type": "http", "path": path, "headers": headers}, receive, send))
    return sent


def status_code(messages: list[dict[str, Any]]) -> int:
    return next(
        message["status"] for message in messages if message["type"] == "http.response.start"
    )


def error_code(messages: list[dict[str, Any]]) -> str:
    body = next(message["body"] for message in messages if message["type"] == "http.response.body")
    return json.loads(body)["code"]
