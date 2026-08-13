from __future__ import annotations

from datetime import UTC, datetime
from enum import StrEnum
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException


class ErrorCode(StrEnum):
    """Backend-AI 내부 생성 계약에서 사용하는 오류 코드."""

    AI_UNAUTHORIZED = "AI_UNAUTHORIZED"
    AI_INVALID_REQUEST = "AI_INVALID_REQUEST"
    AI_FILE_PROCESSING_FAILED = "AI_FILE_PROCESSING_FAILED"
    AI_GENERATION_FAILED = "AI_GENERATION_FAILED"
    AI_INVALID_RESPONSE = "AI_INVALID_RESPONSE"
    AI_TIMEOUT = "AI_TIMEOUT"
    AI_UNAVAILABLE = "AI_UNAVAILABLE"


_STATUS_BY_CODE: dict[ErrorCode, int] = {
    ErrorCode.AI_UNAUTHORIZED: 401,
    ErrorCode.AI_INVALID_REQUEST: 400,
    ErrorCode.AI_FILE_PROCESSING_FAILED: 422,
    ErrorCode.AI_GENERATION_FAILED: 500,
    ErrorCode.AI_INVALID_RESPONSE: 502,
    ErrorCode.AI_TIMEOUT: 504,
    ErrorCode.AI_UNAVAILABLE: 503,
}


# Pydantic 오류 메시지는 종류에 따라 입력값을 그대로 담는다.
# 특히 사용자 정의 validator가 던지는 value_error/assertion_error는 메시지에
# 입력값을 끼워 넣기 쉬우므로, 외부 응답에는 오류 종류별 고정 문구만 내보낸다.
_VALIDATION_MESSAGES: dict[str, str] = {
    "missing": "필수 항목이 누락되었습니다.",
    "extra_forbidden": "허용되지 않은 항목입니다.",
    "json_invalid": "JSON 형식이 올바르지 않습니다.",
    "string_type": "문자열이어야 합니다.",
    "string_too_short": "길이가 너무 짧습니다.",
    "string_too_long": "허용된 길이를 초과했습니다.",
    "int_type": "정수여야 합니다.",
    "int_parsing": "정수로 변환할 수 없습니다.",
    "float_type": "실수여야 합니다.",
    "float_parsing": "실수로 변환할 수 없습니다.",
    "bool_type": "true 또는 false여야 합니다.",
    "list_type": "배열이어야 합니다.",
    "dict_type": "객체여야 합니다.",
    "model_type": "객체여야 합니다.",
    "enum": "허용되지 않은 값입니다.",
    "literal_error": "허용되지 않은 값입니다.",
    "too_short": "항목 수가 너무 적습니다.",
    "too_long": "항목 수가 너무 많습니다.",
    "greater_than": "허용 범위를 벗어났습니다.",
    "greater_than_equal": "허용 범위를 벗어났습니다.",
    "less_than": "허용 범위를 벗어났습니다.",
    "less_than_equal": "허용 범위를 벗어났습니다.",
}
_DEFAULT_VALIDATION_MESSAGE = "값이 올바르지 않습니다."


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
    code = ErrorCode.AI_INVALID_REQUEST if exc.status_code < 500 else ErrorCode.AI_GENERATION_FAILED
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

    Pydantic 오류에는 입력값 원문(input)과 값이 섞인 메시지(msg)가 들어 있어
    apiKey가 그대로 노출될 수 있다. docs 13번 원칙에 따라 위치와 오류 종류만
    남기고, 문구는 종류별 고정값으로 바꾼다.
    """
    violations = [
        {
            "field": ".".join(str(part) for part in error["loc"]),
            "type": error["type"],
            "message": _VALIDATION_MESSAGES.get(error["type"], _DEFAULT_VALIDATION_MESSAGE),
        }
        for error in exc.errors()
    ]
    return JSONResponse(
        status_code=422,
        content=build_error_payload(
            ErrorCode.AI_INVALID_REQUEST,
            "요청 형식이 올바르지 않습니다.",
            {"violations": violations},
        ),
    )


async def unhandled_error_handler(_request: Request, exc: Exception) -> JSONResponse:
    # 예외 원문에 API Key가 섞일 수 있으므로 그대로 노출하지 않는다.
    return JSONResponse(
        status_code=500,
        content=build_error_payload(
            ErrorCode.AI_GENERATION_FAILED,
            "AI Service 내부 오류가 발생했습니다.",
            {"exception": type(exc).__name__},
        ),
    )


def register_exception_handlers(app: FastAPI) -> None:
    app.add_exception_handler(AIServiceError, ai_service_error_handler)
    app.add_exception_handler(StarletteHTTPException, http_exception_handler)
    app.add_exception_handler(RequestValidationError, validation_exception_handler)
    app.add_exception_handler(Exception, unhandled_error_handler)
