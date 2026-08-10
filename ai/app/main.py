from fastapi import FastAPI

from app.api import health
from app.core.config import APP_VERSION, get_settings
from app.core.errors import register_exception_handlers
from app.core.logging import configure_logging


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
    app.include_router(health.router)
    return app


app = create_app()
