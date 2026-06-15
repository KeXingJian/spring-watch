-- kxj: 通知配置表-按appid配置告警通知目标(邮箱)
CREATE TABLE IF NOT EXISTS alert_notification_config (
    id BIGSERIAL PRIMARY KEY,
    appid BIGINT NOT NULL,
    target VARCHAR(256) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'enabled',
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    CONSTRAINT uq_alert_notify_config UNIQUE (appid, target)
);

CREATE INDEX IF NOT EXISTS idx_alert_notify_config_appid
    ON alert_notification_config (appid);

CREATE INDEX IF NOT EXISTS idx_alert_notify_config_status
    ON alert_notification_config (status);
