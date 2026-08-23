CREATE TABLE generation_jobs (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    progress INTEGER NOT NULL CHECK (progress BETWEEN 0 AND 100),
    current_stage VARCHAR(20) NOT NULL CHECK (current_stage IN ('QUEUED', 'CALLING_AI', 'COMPLETED', 'FAILED')),
    request_document JSONB NOT NULL,
    result_document JSONB,
    failure_code VARCHAR(100),
    failure_message VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_generation_jobs_project_created_at
    ON generation_jobs(project_id, created_at DESC, id DESC);
