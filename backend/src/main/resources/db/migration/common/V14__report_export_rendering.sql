ALTER TABLE report_exports ADD COLUMN snapshot JSONB;
ALTER TABLE report_exports ADD COLUMN render_token_hash VARCHAR(64);
ALTER TABLE report_exports ADD COLUMN render_token_expires_at TIMESTAMP WITH TIME ZONE;

UPDATE report_exports
SET status = 'FAILED',
    failure_code = 'EXPORT_SNAPSHOT_MISSING',
    failure_message = 'PDF 생성에 필요한 보고서 스냅샷이 없습니다.',
    completed_at = CURRENT_TIMESTAMP
WHERE status = 'PENDING' AND snapshot IS NULL;

ALTER TABLE report_exports
    ADD CONSTRAINT report_exports_pending_snapshot_check
    CHECK (status <> 'PENDING' OR snapshot IS NOT NULL);
