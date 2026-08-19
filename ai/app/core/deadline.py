from __future__ import annotations

from collections.abc import Callable
from time import monotonic

from app.core.errors import AIServiceError, ErrorCode


class Deadline:
    """생성 요청 하나가 쓸 수 있는 전체 시간을 제한한다.

    호출당 타임아웃만 두면 단계 수(4 + 이미지 수)와 재시도 횟수가 곱해져 전체 소요
    시간이 Backend 응답 타임아웃을 크게 넘길 수 있다. Backend가 이미 포기한 요청을
    AI Service가 계속 처리하면 무료 티어 쿼터만 소모하므로 전체 시간을 함께 막는다.
    """

    def __init__(self, total_seconds: float, clock: Callable[[], float] = monotonic) -> None:
        self._clock = clock
        self._expires_at = clock() + total_seconds

    def remaining(self) -> float:
        return self._expires_at - self._clock()

    def expired(self) -> bool:
        return self.remaining() <= 0

    def ensure_active(self) -> None:
        if self.expired():
            raise AIServiceError(ErrorCode.AI_TIMEOUT, "AI 보고서 생성 시간이 초과되었습니다.")
