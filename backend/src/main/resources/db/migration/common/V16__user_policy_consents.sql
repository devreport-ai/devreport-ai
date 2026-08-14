CREATE TABLE user_policy_consents (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    privacy_policy_version VARCHAR(50) NOT NULL,
    terms_of_service_version VARCHAR(50) NOT NULL,
    consented_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_user_policy_consents_user_id
    ON user_policy_consents(user_id, consented_at DESC);
