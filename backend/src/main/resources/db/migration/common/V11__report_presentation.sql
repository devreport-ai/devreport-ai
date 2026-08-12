ALTER TABLE reports ADD COLUMN template_id VARCHAR(64);
ALTER TABLE reports ADD COLUMN template_version INTEGER;
ALTER TABLE reports ADD COLUMN presentation_settings JSONB NOT NULL DEFAULT '{}';

ALTER TABLE reports ADD CONSTRAINT ck_reports_template_pair
    CHECK ((template_id IS NULL AND template_version IS NULL)
        OR (template_id IS NOT NULL AND template_version IS NOT NULL AND template_version >= 1));
