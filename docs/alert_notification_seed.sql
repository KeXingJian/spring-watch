-- 1. 通知配置 (appid=650794751521025 的告警发给 2787901285@qq.com)
INSERT INTO alert_notification_config (appid, target, status, created_at, updated_at)
VALUES (650794751521025, '2787901285@qq.com', 'enabled', NOW(), NOW());

-- 2. 指标告警: JVM 堆内存 > 500MB 持续 60s
INSERT INTO alert_rule (appid, rule_name, rule_type, expression, duration_seconds, status, level, times, created_at)
VALUES (650794751521025, 'SEED_JVM_HEAP_OVER_500MB', 'metric', 'jvm_memory_used_bytes > 500000000', 60, 'enabled', 'warning', 1, NOW());

-- 3. 日志告警: 出现 ERROR 关键字触发
INSERT INTO alert_rule (appid, rule_name, rule_type, expression, duration_seconds, status, level, times, created_at)
VALUES (650794751521025, 'SEED_LOG_KEYWORD_ERROR', 'log_keyword', 'keyword="ERROR"', 60, 'enabled', 'warning', 1, NOW());
