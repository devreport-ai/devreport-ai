from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any

from app.core.errors import ErrorCode, build_error_payload

ASGIApp = Callable[
    [
        dict[str, Any],
        Callable[[], Awaitable[dict[str, Any]]],
        Callable[[dict[str, Any]], Awaitable[None]],
    ],
    Awaitable[None],
]


class RequestBodyLimitMiddleware:
    """multipart 파싱 전 생성 요청의 전체 바이트를 제한하는 ASGI 미들웨어."""

    def __init__(self, app: ASGIApp, max_body_size: int, path: str) -> None:
        self.app = app
        self.max_body_size = max_body_size
        self.path = path

    async def __call__(
        self,
        scope: dict[str, Any],
        receive: Callable[[], Awaitable[dict[str, Any]]],
        send: Callable[[dict[str, Any]], Awaitable[None]],
    ) -> None:
        if scope["type"] != "http" or scope.get("path") != self.path:
            await self.app(scope, receive, send)
            return

        content_length = header_value(scope, b"content-length")
        if content_length is not None and content_length > self.max_body_size:
            await send_too_large(send)
            return

        received_size = 0

        async def limited_receive() -> dict[str, Any]:
            nonlocal received_size
            message = await receive()
            if message["type"] == "http.request":
                received_size += len(message.get("body", b""))
                if received_size > self.max_body_size:
                    raise RequestBodyTooLarge
            return message

        try:
            await self.app(scope, limited_receive, send)
        except RequestBodyTooLarge:
            await send_too_large(send)


class RequestBodyTooLarge(Exception):
    """chunked request가 최대 본문 크기를 초과했음을 나타낸다."""


def header_value(scope: dict[str, Any], name: bytes) -> int | None:
    for key, value in scope.get("headers", []):
        if key.lower() == name:
            try:
                return int(value)
            except ValueError:
                return None
    return None


async def send_too_large(send: Callable[[dict[str, Any]], Awaitable[None]]) -> None:
    body = json_bytes(
        build_error_payload(
            ErrorCode.AI_INVALID_REQUEST,
            "AI 생성 요청 크기가 허용 범위를 초과했습니다.",
        )
    )
    await send(
        {
            "type": "http.response.start",
            "status": 413,
            "headers": [
                (b"content-type", b"application/json"),
                (b"content-length", str(len(body)).encode()),
            ],
        }
    )
    await send({"type": "http.response.body", "body": body})


def json_bytes(value: dict[str, Any]) -> bytes:
    import json

    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
