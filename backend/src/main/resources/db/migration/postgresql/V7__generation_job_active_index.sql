CREATE UNIQUE INDEX uq_generation_jobs_active_project
    ON generation_jobs(project_id)
    WHERE status IN ('PENDING', 'PROCESSING');
