
INSERT INTO alert_rule
(appid, rule_name, rule_type, expression, threshold_value, duration_seconds, status, level, created_at)
VALUES
    (650794751521025, 'TEST_LOG_KEYWORD_SPRINGWATCH', 'log_keyword',
     'keyword="SpringWatch"', NULL, 0, 'enabled', 'WARNING', NOW());

-- ============================================================
-- 2) 日志错误率 - LogAlertScheduler 定时(默认 60s)计算 60s 窗口错误率
--    threshold_value 单位是百分比,如 1.0 = 1% 错误率即触发
--    设 1.0 是为了方便测试:60s 窗口内只要有 1 条 ERROR + 99 条非 ERROR(或更少)就触发
-- ============================================================
INSERT INTO alert_rule
(appid, rule_name, rule_type, expression, threshold_value, duration_seconds, status, level, created_at)
VALUES
    (650794751521025, 'TEST_LOG_ERROR_RATE_HIGH', 'log_error_rate',
     NULL, 1.0, 0, 'enabled', 'CRITICAL', NOW());
