CREATE TABLE usage_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN (
        'PROJECT_CREATED',
        'FILE_UPLOADED',
        'GENERATION_REQUESTED',
        'GENERATION_COMPLETED',
        'GENERATION_FAILED',
        'REPORT_EDITED',
        'PDF_EXPORTED'
    )),
    deduplication_key VARCHAR(200) NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
    file_id UUID REFERENCES uploaded_files(id) ON DELETE SET NULL,
    report_id UUID REFERENCES reports(id) ON DELETE SET NULL,
    job_id UUID REFERENCES generation_jobs(id) ON DELETE SET NULL,
    export_id UUID REFERENCES report_exports(id) ON DELETE SET NULL,
    metadata JSONB NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_usage_events_occurred_at ON usage_events(occurred_at);
CREATE INDEX idx_usage_events_project_occurred_at
    ON usage_events(project_id, occurred_at DESC, id DESC);
