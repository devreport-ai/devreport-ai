from app.core.logging import mask_secrets


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
