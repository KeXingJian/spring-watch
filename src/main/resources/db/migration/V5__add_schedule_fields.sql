ALTER TABLE monitor_app
    ADD COLUMN IF NOT EXISTS schedule_type VARCHAR(16) NOT NULL DEFAULT 'INTERVAL';

ALTER TABLE monitor_app
    ADD COLUMN IF NOT EXISTS cron_expression VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_monitor_app_schedule_type
    ON monitor_app (schedule_type)
    WHERE status = 'active';
