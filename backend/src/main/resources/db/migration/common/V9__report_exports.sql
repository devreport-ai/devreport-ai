CREATE TABLE report_exports (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES reports(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL,
    size_bytes BIGINT,
    failure_code VARCHAR(100),
    failure_message VARCHAR(500),
    expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_report_exports_report_created_at
    ON report_exports(report_id, created_at DESC, id DESC);
