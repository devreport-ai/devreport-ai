from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# ai/app/core/config.py -> parents[3] == 저장소 루트
REPO_ROOT = Path(__file__).resolve().parents[3]

APP_VERSION = "0.1.0"
FREE_TIER_GEMINI_MODEL = "gemini-3.5-flash-lite"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=REPO_ROOT / "ai" / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
        hide_input_in_errors=True,
    )

    app_env: str = "local"
    log_level: str = "INFO"

    # contracts는 세 파트 공통 계약이라 저장소 루트 기준으로 참조한다.
    # 컨테이너 배포처럼 경로가 달라지면 CONTRACTS_DIR로 덮어쓴다.
    contracts_dir: Path = REPO_ROOT / "contracts"

    # 실제 Gemini 호출 대신 샘플 ReportDocument를 반환한다.
    # 파트 간 통신 확인 단계에서는 true로 둔다.
    mock_report: bool = True

    # Backend와 AI Service 사이의 내부 요청 인증 Secret.
    ai_internal_token: str | None = None

    # 무료 티어 사용량 제한 안에서 운영할 Flash 모델만 허용한다.
    gemini_model: Literal["gemini-3.5-flash-lite"] = FREE_TIER_GEMINI_MODEL
    # AI Service 서버 키. 사용자별 키 전달은 MVP 범위가 아니다.
    gemini_api_key: str | None = None
    gemini_timeout_seconds: float = 300.0
    gemini_max_retries: int = 2

    @property
    def report_schema_path(self) -> Path:
        return self.contracts_dir / "report-document.schema.json"

    @property
    def sample_report_path(self) -> Path:
        return self.contracts_dir / "examples" / "sample-report.json"

    @model_validator(mode="after")
    def validate_production_settings(self) -> "Settings":
        if self.app_env.strip().lower() not in {"prod", "production"}:
            return self
        if self.mock_report:
            raise ValueError("MOCK_REPORT는 운영 환경에서 false여야 합니다.")
        if not self.ai_internal_token or not self.ai_internal_token.strip():
            raise ValueError("AI_INTERNAL_TOKEN이 운영 환경에 설정되지 않았습니다.")
        if not self.gemini_api_key or not self.gemini_api_key.strip():
            raise ValueError("GEMINI_API_KEY가 운영 환경에 설정되지 않았습니다.")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
