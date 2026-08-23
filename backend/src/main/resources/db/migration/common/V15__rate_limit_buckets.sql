CREATE TABLE rate_limit_buckets (
    bucket_key VARCHAR(128) PRIMARY KEY,
    window_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count >= 0)
);

CREATE INDEX idx_rate_limit_buckets_window_started_at
    ON rate_limit_buckets(window_started_at);
