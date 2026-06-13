ALTER TABLE monitor_app ADD COLUMN appid BIGINT;

UPDATE monitor_app SET appid = (
    (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT - 1622476800000
) << 12 | (1 << 8) | 1
WHERE appid IS NULL;

ALTER TABLE monitor_app ALTER COLUMN appid SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_monitor_app_appid ON monitor_app (appid);
