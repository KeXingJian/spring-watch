-- kxj: P1 告警收敛 - alert_history 增加收敛组字段
-- 背景: 告警风暴期大量相似告警刷屏,需要按相似度聚类抑制重复通知
-- 字段说明:
--   agg_group_id     收敛组ID(雪花ID),同一组=相似告警
--   agg_role         leader=首报(已通知) / member=被抑制(静默)
--   agg_suppressed   是否被收敛抑制(未发送通知)
--   agg_group_count  该收敛组累计告警次数(含首报)
--   agg_suppressed_count 该收敛组被抑制的重复次数

ALTER TABLE alert_history
    ADD COLUMN IF NOT EXISTS agg_group_id     VARCHAR(32),
    ADD COLUMN IF NOT EXISTS agg_role         VARCHAR(16) NOT NULL DEFAULT 'leader',
    ADD COLUMN IF NOT EXISTS agg_suppressed   BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS agg_group_count  INTEGER     NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS agg_suppressed_count INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_alert_history_agg_group
    ON alert_history (agg_group_id, created_at);

COMMENT ON COLUMN alert_history.agg_group_id           IS '告警收敛组ID - 相似告警共享同一组,用于风暴期聚类';
COMMENT ON COLUMN alert_history.agg_role               IS '收敛组角色 - leader=首报已通知 / member=被抑制静默';
COMMENT ON COLUMN alert_history.agg_suppressed         IS '该条是否被收敛抑制(未发通知)';
COMMENT ON COLUMN alert_history.agg_group_count        IS '收敛组累计告警次数(含首报)';
COMMENT ON COLUMN alert_history.agg_suppressed_count   IS '收敛组被抑制的重复次数';
