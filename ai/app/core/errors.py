from __future__ import annotations

from datetime import UTC, datetime
from enum import StrEnum
from typing import Any

from fastapi import Request
from fastapi.responses import JSONResponse


class ErrorCode(StrEnum):
    """docs 19번 공통 에러 코드 중 AI Service가 반환할 수 있는 항목."""

    INVALID_REQUEST = "INVALID_REQUEST"
    INVALID_API_KEY = "INVALID_API_KEY"
    AI_REQUEST_TIMEOUT = "AI_REQUEST_TIMEOUT"
    AI_RESPONSE_INVALID = "AI_RESPONSE_INVALID"
    GENERATION_FAILED = "GENERATION_FAILED"


_STATUS_BY_CODE: dict[ErrorCode, int] = {
    ErrorCode.INVALID_REQUEST: 400,
    ErrorCode.INVALID_API_KEY: 401,
    ErrorCode.AI_REQUEST_TIMEOUT: 504,
    ErrorCode.AI_RESPONSE_INVALID: 502,
    ErrorCode.GENERATION_FAILED: 500,
}


class AIServiceError(Exception):
    def __init__(self, code: ErrorCode, message: str, details: Any | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.details = details

    @property
    def status_code(self) -> int:
        return _STATUS_BY_CODE.get(self.code, 500)

    def to_payload(self) -> dict[str, Any]:
        return {
            "code": str(self.code),
            "message": self.message,
            "details": self.details,
            "timestamp": datetime.now(UTC).astimezone().isoformat(timespec="seconds"),
        }


async def ai_service_error_handler(_request: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, AIServiceError)
    return JSONResponse(status_code=exc.status_code, content=exc.to_payload())


async def unhandled_error_handler(_request: Request, exc: Exception) -> JSONResponse:
    # 예외 원문에 API Key가 섞일 수 있으므로 그대로 노출하지 않는다.
    error = AIServiceError(
        ErrorCode.GENERATION_FAILED,
        "AI Service 내부 오류가 발생했습니다.",
        details={"exception": type(exc).__name__},
    )
    return JSONResponse(status_code=error.status_code, content=error.to_payload())
