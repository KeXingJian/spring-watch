-- kxj: P0 复合索引 - 修复"看板最近告警/agent 失活扫描"全表扫
-- alert_history 按 appid + 时间倒序拉取最新告警,monitor_app 按 status 扫超时不健康实例
--
-- 历史(M-WriteApiSplit 期间事故 2026-06-29):
--   原版用 [non-transactional] + CREATE INDEX CONCURRENTLY,在 PG 上把 HikariCP connection
--   占了 4 分多钟,导致 Flyway 后续 V12.2 / V13 拿不到连接卡死,ApplicationReadyEvent 不触发,
--   InfluxDB bucket 不建。事故见白皮书 0.5 时序问题。
--
-- 现改成普通事务 + 普通 CREATE INDEX:
--   - alert_history / monitor_app 是小表(几行~几千行),锁表几毫秒可接受
--   - 走事务模式,Flyway 跑完后立刻能 commit + 释放 connection
--   - 不再阻塞后续 V12.2 / V13
--
-- 如果未来表大到需要 CONCURRENTLY,改用外部脚本 + baseLine-on-migrate 方案,
-- 不要在 Flyway 文件里用 [non-transactional] + CREATE INDEX CONCURRENTLY(已踩坑)。
CREATE INDEX IF NOT EXISTS idx_alert_history_appid_created_desc
    ON alert_history (appid, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_monitor_app_status_heartbeat
    ON monitor_app (status, last_heartbeat);
