ALTER TABLE monitor_app DROP COLUMN IF EXISTS collect_mode;
ALTER TABLE monitor_app ADD COLUMN IF NOT EXISTS last_log_pull_time TIMESTAMPTZ;
