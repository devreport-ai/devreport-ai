-- 사용자별 provider API Key (암호문만 저장) 와 생성 작업의 provider·model snapshot

CREATE TABLE user_ai_credentials (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    provider VARCHAR(20) NOT NULL CHECK (provider IN ('GEMINI', 'ANTHROPIC')),
    ciphertext VARCHAR(1024) NOT NULL,
    nonce VARCHAR(32) NOT NULL,
    key_version INTEGER NOT NULL,
    key_hint VARCHAR(8) NOT NULL,
    verified_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_user_ai_credentials_user_provider UNIQUE (user_id, provider)
);

ALTER TABLE generation_jobs ADD COLUMN provider VARCHAR(20);
ALTER TABLE generation_jobs ADD COLUMN model VARCHAR(100);
ALTER TABLE generation_jobs ADD COLUMN key_source VARCHAR(10);

-- 기존 작업은 모두 서버 기본 Gemini 키로 실행되었다.
UPDATE generation_jobs
SET provider = 'GEMINI', model = 'gemini-3.5-flash-lite', key_source = 'SERVER'
WHERE provider IS NULL;

ALTER TABLE generation_jobs ALTER COLUMN provider SET NOT NULL;
ALTER TABLE generation_jobs ALTER COLUMN model SET NOT NULL;
ALTER TABLE generation_jobs ALTER COLUMN key_source SET NOT NULL;
ALTER TABLE generation_jobs ADD CONSTRAINT ck_generation_jobs_provider
    CHECK (provider IN ('GEMINI', 'ANTHROPIC'));
ALTER TABLE generation_jobs ADD CONSTRAINT ck_generation_jobs_key_source
    CHECK (key_source IN ('SERVER', 'USER'));
