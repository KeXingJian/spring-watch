CREATE TABLE IF NOT EXISTS app_logs (
    id BIGSERIAL,
    app_name VARCHAR(128) NOT NULL,
    level VARCHAR(16) NOT NULL,
    logger VARCHAR(512),
    thread_name VARCHAR(256),
    message TEXT,
    throwable TEXT,
    trace_id VARCHAR(64),
    log_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id, log_time)
) PARTITION BY RANGE (log_time);

CREATE TABLE IF NOT EXISTS app_logs_default PARTITION OF app_logs DEFAULT;

CREATE INDEX IF NOT EXISTS idx_logs_app_time ON app_logs (app_name, log_time DESC);
CREATE INDEX IF NOT EXISTS idx_logs_level ON app_logs (app_name, level, log_time DESC);