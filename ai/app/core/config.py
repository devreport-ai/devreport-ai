from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# ai/app/core/config.py -> parents[3] == 저장소 루트
REPO_ROOT = Path(__file__).resolve().parents[3]

APP_VERSION = "0.1.0"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=REPO_ROOT / "ai" / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    app_env: str = "local"
    log_level: str = "INFO"

    # contracts는 세 파트 공통 계약이라 저장소 루트 기준으로 참조한다.
    # 컨테이너 배포처럼 경로가 달라지면 CONTRACTS_DIR로 덮어쓴다.
    contracts_dir: Path = REPO_ROOT / "contracts"

    # 실제 Gemini 호출 대신 샘플 ReportDocument를 반환한다.
    # 파트 간 통신 확인 단계에서는 true로 둔다.
    mock_report: bool = True

    gemini_model: str = "gemini-2.5-pro"
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


@lru_cache
def get_settings() -> Settings:
    return Settings()
