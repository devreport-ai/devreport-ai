from __future__ import annotations

from datetime import UTC, datetime
from enum import StrEnum
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException


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


def build_error_payload(
    code: ErrorCode, message: str, details: Any | None = None
) -> dict[str, Any]:
    """docs 19번 공통 에러 응답 규격."""
    return {
        "code": str(code),
        "message": message,
        "details": details,
        "timestamp": datetime.now(UTC).astimezone().isoformat(timespec="seconds"),
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
        return build_error_payload(self.code, self.message, self.details)


async def ai_service_error_handler(_request: Request, exc: AIServiceError) -> JSONResponse:
    return JSONResponse(status_code=exc.status_code, content=exc.to_payload())


async def http_exception_handler(_request: Request, exc: StarletteHTTPException) -> JSONResponse:
    """404, 405 등 Starlette 기본 예외도 공통 규격으로 변환한다.

    변환하지 않으면 {"detail": "Not Found"} 형태가 그대로 나가서
    Backend가 응답 파싱을 두 갈래로 처리해야 한다.
    """
    code = ErrorCode.INVALID_REQUEST if exc.status_code < 500 else ErrorCode.GENERATION_FAILED
    return JSONResponse(
        status_code=exc.status_code,
        content=build_error_payload(code, str(exc.detail)),
        # 405 응답의 Allow 헤더 등 기본 동작을 유지한다.
        headers=getattr(exc, "headers", None),
    )


async def validation_exception_handler(
    _request: Request, exc: RequestValidationError
) -> JSONResponse:
    """요청 본문 검증 실패를 공통 규격으로 변환한다.

    Pydantic 기본 오류에는 입력값 원문(input)이 들어 있어 apiKey가 그대로
    노출될 수 있다. docs 13번 원칙에 따라 위치와 사유만 남기고 값은 버린다.
    """
    violations = [
        {
            "field": ".".join(str(part) for part in error["loc"]),
            "type": error["type"],
            "message": error["msg"],
        }
        for error in exc.errors()
    ]
    return JSONResponse(
        status_code=422,
        content=build_error_payload(
            ErrorCode.INVALID_REQUEST,
            "요청 형식이 올바르지 않습니다.",
            {"violations": violations},
        ),
    )


async def unhandled_error_handler(_request: Request, exc: Exception) -> JSONResponse:
    # 예외 원문에 API Key가 섞일 수 있으므로 그대로 노출하지 않는다.
    return JSONResponse(
        status_code=500,
        content=build_error_payload(
            ErrorCode.GENERATION_FAILED,
            "AI Service 내부 오류가 발생했습니다.",
            {"exception": type(exc).__name__},
        ),
    )


def register_exception_handlers(app: FastAPI) -> None:
    app.add_exception_handler(AIServiceError, ai_service_error_handler)
    app.add_exception_handler(StarletteHTTPException, http_exception_handler)
    app.add_exception_handler(RequestValidationError, validation_exception_handler)
    app.add_exception_handler(Exception, unhandled_error_handler)
