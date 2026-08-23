from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# ai/app/core/config.py -> parents[3] == 저장소 루트
REPO_ROOT = Path(__file__).resolve().parents[3]

APP_VERSION = "0.1.0"
FREE_TIER_GEMINI_MODEL = "gemini-3.5-flash-lite"
# 사용자 API Key로 선택할 수 있는 Gemini 모델. Backend의 ai.models.allowlist와 같은 값을 유지한다.
GEMINI_MODEL_ALLOWLIST: tuple[str, ...] = (
    FREE_TIER_GEMINI_MODEL,
    "gemini-3.5-flash",
    "gemini-3.5-pro",
)
GEMINI_PROVIDER = "GEMINI"
AppEnvironment = Literal["local", "test", "prod", "production"]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=REPO_ROOT / "ai" / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
        hide_input_in_errors=True,
    )

    app_env: AppEnvironment = "local"
    log_level: str = "INFO"

    # contracts는 세 파트 공통 계약이라 저장소 루트 기준으로 참조한다.
    # 컨테이너 배포처럼 경로가 달라지면 CONTRACTS_DIR로 덮어쓴다.
    contracts_dir: Path = REPO_ROOT / "contracts"

    # 실제 Gemini 호출 대신 샘플 ReportDocument를 반환한다.
    # 파트 간 통신 확인 단계에서는 true로 둔다.
    mock_report: bool = True

    # Backend와 AI Service 사이의 내부 요청 인증 Secret.
    ai_internal_token: str | None = None

    # 서버 키로 실행하는 기본 모델. 무료 티어 사용량 제한 안에서 운영할 Flash 모델만 허용한다.
    gemini_model: Literal["gemini-3.5-flash-lite"] = FREE_TIER_GEMINI_MODEL
    # 사용자 API Key(X-Provider-Api-Key)로 선택할 수 있는 모델 allowlist. 임의 모델 ID는 거부한다.
    gemini_allowed_models: tuple[str, ...] = GEMINI_MODEL_ALLOWLIST
    # AI Service 서버 키. 사용자 키가 없는 요청은 이 키와 gemini_model로 실행한다.
    gemini_api_key: str | None = None
    # API Key 검증 호출(모델 목록 조회)의 타임아웃.
    credential_verify_timeout_seconds: float = Field(default=10.0, gt=0)
    # Gemini 호출 1건의 타임아웃. 생성 1회는 (4 + 이미지 수)번 호출하므로
    # 호출당 값을 크게 잡으면 Backend 응답 타임아웃을 쉽게 넘긴다.
    gemini_timeout_seconds: float = Field(default=90.0, gt=0)
    gemini_max_retries: int = Field(default=2, ge=0, le=5)
    # 생성 요청 하나가 Gemini 호출에 쓸 수 있는 전체 시간.
    # Backend의 AI_SERVICE_RESPONSE_TIMEOUT보다 짧게 유지한다.
    generation_deadline_seconds: float = Field(default=240.0, gt=0)
    # 이미지는 1장당 1회 호출이라 무료 티어 분당 요청 한도를 빠르게 소진한다.
    max_analyzed_images: int = Field(default=10, ge=1, le=50)

    @property
    def report_schema_path(self) -> Path:
        return self.contracts_dir / "report-document.schema.json"

    @property
    def sample_report_path(self) -> Path:
        return self.contracts_dir / "examples" / "sample-report.json"

    @model_validator(mode="after")
    def validate_production_settings(self) -> "Settings":
        if self.app_env not in {"prod", "production"}:
            return self
        if self.mock_report:
            raise ValueError("MOCK_REPORT는 운영 환경에서 false여야 합니다.")
        if not self.ai_internal_token or not self.ai_internal_token.strip():
            raise ValueError("AI_INTERNAL_TOKEN이 운영 환경에 설정되지 않았습니다.")
        if not self.gemini_api_key or not self.gemini_api_key.strip():
            raise ValueError("GEMINI_API_KEY가 운영 환경에 설정되지 않았습니다.")
        return self

    @model_validator(mode="after")
    def validate_model_allowlist(self) -> "Settings":
        if not self.gemini_allowed_models:
            raise ValueError("GEMINI_ALLOWED_MODELS는 비어 있을 수 없습니다.")
        if self.gemini_model not in self.gemini_allowed_models:
            raise ValueError("GEMINI_MODEL은 GEMINI_ALLOWED_MODELS에 포함되어야 합니다.")
        return self

    @model_validator(mode="after")
    def validate_generation_budget(self) -> "Settings":
        # 전체 예산이 호출 1건보다 짧으면 첫 호출조차 끝내지 못하고 만료된다.
        if self.generation_deadline_seconds < self.gemini_timeout_seconds:
            raise ValueError(
                "GENERATION_DEADLINE_SECONDS는 GEMINI_TIMEOUT_SECONDS 이상이어야 합니다."
            )
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
