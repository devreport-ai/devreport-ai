from fastapi import FastAPI

from app.api import health, reports
from app.core.config import APP_VERSION, get_settings
from app.core.errors import register_exception_handlers
from app.core.logging import configure_logging
from app.core.request_body_limit import RequestBodyLimitMiddleware
from app.schemas.generation import MAX_BUNDLE_TOTAL_SIZE


def create_app() -> FastAPI:
    settings = get_settings()
    configure_logging(settings.log_level)

    app = FastAPI(
        title="DevReport AI Service",
        description=(
            "Backend 전용 내부 서비스. 외부에 직접 공개하지 않는다. "
            "문서/코드/이미지를 분석해 ReportDocument JSON을 반환한다."
        ),
        version=APP_VERSION,
    )

    register_exception_handlers(app)
    app.add_middleware(
        RequestBodyLimitMiddleware,
        max_body_size=MAX_BUNDLE_TOTAL_SIZE,
        path="/internal/ai/reports/generate",
    )
    app.include_router(health.router)
    app.include_router(reports.router)
    return app


app = create_app()
