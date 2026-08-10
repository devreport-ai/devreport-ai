import logging

from app.core.logging import SecretMaskingFilter, mask_secrets


def test_masks_gemini_api_key_literal():
    masked = mask_secrets("using key AIzaSyD-1234567890abcdefghij for request")

    assert "AIzaSyD-1234567890abcdefghij" not in masked
    assert "***REDACTED***" in masked


def test_masks_key_value_form():
    masked = mask_secrets('{"apiKey": "super-secret-value"}')

    assert "super-secret-value" not in masked


def test_keeps_normal_message_intact():
    assert mask_secrets("projectId=06a82db0 sourceFiles=12") == (
        "projectId=06a82db0 sourceFiles=12"
    )


def _record(message: str, args: tuple[object, ...]) -> logging.LogRecord:
    return logging.LogRecord("test", logging.INFO, __file__, 1, message, args, None)


def test_filter_preserves_numeric_formatting():
    # 인수를 개별 마스킹하면 %d에 str이 들어가 TypeError로 로그가 유실된다.
    record = _record("sourceFiles=%d ratio=%.2f", (12, 0.5))

    SecretMaskingFilter().filter(record)

    assert record.getMessage() == "sourceFiles=12 ratio=0.50"


def test_filter_masks_key_passed_as_argument():
    record = _record("calling gemini with %s", ("AIzaSyD-1234567890abcdefghij",))

    SecretMaskingFilter().filter(record)

    rendered = record.getMessage()
    assert "AIzaSyD-1234567890abcdefghij" not in rendered
    assert "***REDACTED***" in rendered
