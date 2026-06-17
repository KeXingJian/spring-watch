-- kxj: P0 复合索引 - 修复"看板最近告警/agent 失活扫描"全表扫
-- alert_history 按 appid + 时间倒序拉取最新告警,monitor_app 按 status 扫超时不健康实例
-- 用 CONCURRENTLY 避免 DDL 期间长时间持锁
-- 独立成文件:V12.1 只放非事务性语句(CONCURRENTLY 不能在事务块中执行),V12.2 再做事务性收尾
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_alert_history_appid_created_desc
    ON alert_history (appid, created_at DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_monitor_app_status_heartbeat
    ON monitor_app (status, last_heartbeat);
