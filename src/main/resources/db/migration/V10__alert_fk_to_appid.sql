-- kxj: alert_rule / alert_history 外键从 monitor_app.id 改为 monitor_app.appid
-- 背景: appid 才是业务上稳定的唯一标识(UNIQUE),直接用作外键避免 id/appid 混用

-- ==================== alert_rule ====================
ALTER TABLE alert_rule ADD COLUMN appid BIGINT;

UPDATE alert_rule r
SET appid = a.appid
FROM monitor_app a
WHERE r.app_id = a.id;

ALTER TABLE alert_rule ALTER COLUMN appid SET NOT NULL;

ALTER TABLE alert_rule DROP CONSTRAINT IF EXISTS fk_alert_rule_app;
ALTER TABLE alert_rule DROP COLUMN app_id;

ALTER TABLE alert_rule
    ADD CONSTRAINT fk_alert_rule_app FOREIGN KEY (appid) REFERENCES monitor_app(appid);

CREATE INDEX IF NOT EXISTS idx_alert_rule_appid ON alert_rule (appid);

-- ==================== alert_history ====================
ALTER TABLE alert_history ADD COLUMN appid BIGINT;

UPDATE alert_history h
SET appid = a.appid
FROM monitor_app a
WHERE h.app_id = a.id;

ALTER TABLE alert_history ALTER COLUMN appid SET NOT NULL;

ALTER TABLE alert_history DROP CONSTRAINT IF EXISTS fk_alert_history_app;
ALTER TABLE alert_history DROP COLUMN app_id;

ALTER TABLE alert_history
    ADD CONSTRAINT fk_alert_history_app FOREIGN KEY (appid) REFERENCES monitor_app(appid);

CREATE INDEX IF NOT EXISTS idx_alert_history_appid ON alert_history (appid);
