ALTER TABLE report_exports ADD COLUMN snapshot JSONB;
ALTER TABLE report_exports ADD COLUMN render_token_hash VARCHAR(64);
ALTER TABLE report_exports ADD COLUMN render_token_expires_at TIMESTAMP WITH TIME ZONE;
