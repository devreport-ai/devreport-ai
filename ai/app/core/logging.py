from __future__ import annotations

import logging
import re

# docs 13번의 "로그 출력 금지" 원칙을 AI Service에도 적용한다.
# Gemini 키 형태(AIza...)와 흔한 key=value 표기를 가린다.
_GEMINI_KEY = re.compile(r"AIza[0-9A-Za-z_\-]{10,}")
_KEY_VALUE = re.compile(r"(?i)(api[_-]?key\"?\s*[:=]\s*\"?)([^\s\",}]+)")


def mask_secrets(text: str) -> str:
    return _KEY_VALUE.sub(r"\1***REDACTED***", _GEMINI_KEY.sub("***REDACTED***", text))


class SecretMaskingFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        # 인수를 개별적으로 마스킹하면 모두 str이 되어 %d, %f 포매팅이 TypeError로 깨진다.
        # 먼저 getMessage()로 메시지를 완성한 뒤 마스킹하고 args를 비운다.
        record.msg = mask_secrets(record.getMessage())
        record.args = ()
        return True


def configure_logging(level: str) -> None:
    logging.basicConfig(
        level=level.upper(),
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )
    masking = SecretMaskingFilter()
    for handler in logging.getLogger().handlers:
        handler.addFilter(masking)
