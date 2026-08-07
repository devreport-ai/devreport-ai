CREATE TABLE reports (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    document JSONB NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_reports_project_updated_at ON reports(project_id, updated_at DESC, id DESC);

ALTER TABLE generation_jobs ADD COLUMN report_id UUID REFERENCES reports(id) ON DELETE SET NULL;
