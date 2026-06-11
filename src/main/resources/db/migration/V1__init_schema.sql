CREATE TABLE IF NOT EXISTS monitor_app (
    id BIGSERIAL PRIMARY KEY,
    app_name VARCHAR(128) NOT NULL UNIQUE,
    endpoint VARCHAR(200),
    collect_mode VARCHAR(32) DEFAULT 'prometheus',
    metrics_port INTEGER DEFAULT 9464,
    app_type VARCHAR(32) DEFAULT 'springboot',
    scrape_interval INTEGER DEFAULT 15,
    labels TEXT,
    status VARCHAR(16) DEFAULT 'active',
    last_heartbeat TIMESTAMPTZ,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS alert_rule (
    id BIGSERIAL PRIMARY KEY,
    app_id BIGINT,
    rule_name VARCHAR(256),
    rule_type VARCHAR(32),
    expression VARCHAR(512),
    threshold_value DOUBLE PRECISION,
    duration_seconds INTEGER DEFAULT 60,
    notify_channels JSONB,
    status VARCHAR(16) DEFAULT 'enabled',
    created_at TIMESTAMPTZ,
    CONSTRAINT fk_alert_rule_app FOREIGN KEY (app_id) REFERENCES monitor_app(id)
);

CREATE TABLE IF NOT EXISTS alert_history (
    id BIGSERIAL PRIMARY KEY,
    rule_id BIGINT,
    app_id BIGINT,
    alert_level VARCHAR(16),
    alert_message TEXT,
    notify_result JSONB,
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ,
    CONSTRAINT fk_alert_history_rule FOREIGN KEY (rule_id) REFERENCES alert_rule(id),
    CONSTRAINT fk_alert_history_app FOREIGN KEY (app_id) REFERENCES monitor_app(id)
);

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

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_class WHERE relname = 'app_logs_default') THEN
        CREATE TABLE app_logs_default PARTITION OF app_logs DEFAULT;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_logs_app_time ON app_logs (app_name, log_time DESC);
CREATE INDEX IF NOT EXISTS idx_logs_level ON app_logs (app_name, level, log_time DESC);
