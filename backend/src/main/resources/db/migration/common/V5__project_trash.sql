ALTER TABLE projects ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_projects_owner_deleted_at
    ON projects(owner_id, deleted_at DESC, id DESC);

CREATE INDEX idx_projects_deleted_at ON projects(deleted_at);
