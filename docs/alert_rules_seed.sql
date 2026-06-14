-- spring-watch 告警规则测试 SQL 脚本
-- 使用方式: psql -U root -d spring_collector -f alert_rules_seed.sql
-- 或在 pgAdmin / Navicat 里直接执行

-- ============================================================
-- 0. 准备工作: 确认 monitor_app 里至少有一条记录
-- ============================================================
-- 列出所有 app
SELECT id, appid, app_name, endpoint, metrics_port, status
FROM monitor_app
ORDER BY id;

-- 如果没有任何 app,执行下面这条插入(指向 mock-test 8081 端口)
-- INSERT INTO monitor_app (app_name, endpoint, metrics_port, app_type, scrape_interval, status, appid, created_at, updated_at)
-- VALUES ('mock-test', 'http://localhost:8081', 9464, 'springboot', 15, 'active',
--         (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT - 1622476800000) << 12 | (1 << 8) | 1,
--         NOW(), NOW());

-- ============================================================
-- 1. 清理旧的测试规则 (可选, 重复执行脚本时方便)
-- ============================================================
DELETE FROM alert_rule WHERE rule_name LIKE 'TEST_%';
DELETE FROM alert_history WHERE rule_id NOT IN (SELECT id FROM alert_rule);

-- ============================================================
-- 2. 测试规则集 (假设 monitor_app.id = 1)
--    把所有 (1) 改成你的实际 app_id
-- ============================================================

-- ---- 2.1 JVM 堆内存使用量 > 800MB (持续 30 秒触发) ----
INSERT INTO alert_rule
    (app_id, rule_name, rule_type, expression, threshold_value, duration_seconds, notify_channels, status, created_at)
VALUES
    (1, 'TEST_JVM_HEAP_HIGH', 'metric', 'jvm_memory_used_bytes > 800000000',
     800000000.0, 30, '{"email":"ops@example.com"}', 'enabled', NOW());

-- ---- 2.2 JVM 堆内存使用率 > 90% (用计算后的 ratio, 持续 60 秒) ----
-- 注: 这种需要预先算出 use_ratio,简单起见用绝对值更直观
INSERT INTO alert_rule
    (app_id, rule_name, rule_type, expression, threshold_value, duration_seconds, notify_channels, status, created_at)
VALUES
    (1, 'TEST_JVM_HEAP_CRITICAL', 'metric', 'jvm_memory_used_bytes > 1500000000',
     1500000000.0, 30, '{"email":"ops@example.com"}', 'enabled', NOW());

-- ---- 2.3 GC 暂停时间 > 1 秒 ----
INSERT INTO alert_rule
    (app_id, rule_name, rule_type, expression, threshold_value, duration_seconds, notify_channels, status, created_at)
VALUES
    (1, 'TEST_GC_PAUSE_LONG', 'metric', 'jvm_gc_pause_seconds_max > 1.0',
     1.0, 30, '{"email":"ops@example.com"}', 'enabled', NOW());

-- ---- 2.4 可运行线程数 > 200 ----
INSERT INTO alert_rule
    (app_id, rule_name, rule_type, expression, threshold_value, duration_seconds, notify_channels, status, created_at)
VALUES
    (1, 'TEST_THREADS_HIGH', 'metric', 'jvm_threads_states_threads > 200',
     200.0, 60, '{"email":"ops@example.com"}', 'enabled', NOW());

-- ---- 2.5 进程 CPU 使用率 > 80% ----
INSERT INTO alert_rule
    (app_id, rule_name, rule_type, expression, threshold_value, duration_seconds, notify_channels, status, created_at)
VALUES
    (1, 'TEST_CPU_HIGH', 'metric', 'process_cpu_usage > 0.8',
     0.8, 60, '{"email":"ops@example.com"}', 'enabled', NOW());

-- ---- 2.6 单笔订单金额 > 10000 元 (业务级) ----
INSERT INTO alert_rule
    (app_id, rule_name, rule_type, expression, threshold_value, duration_seconds, notify_channels, status, created_at)
VALUES
    (1, 'TEST_ORDER_AMOUNT_HIGH', 'metric', 'business_order_amount > 10000',
     10000.0, 0, '{"email":"ops@example.com"}', 'enabled', NOW());

-- ---- 2.7 测试用: expression 永真 (用于联调告警链路是否通) ----
-- 任何 metric 进来都会触发
INSERT INTO alert_rule
    (app_id, rule_name, rule_type, expression, threshold_value, duration_seconds, notify_channels, status, created_at)
VALUES
    (1, 'TEST_ALWAYS_FIRE', 'metric', 'jvm_memory_used_bytes > 0',
     0.0, 0, '{"email":"ops@example.com"}', 'enabled', NOW());

-- ---- 2.8 关闭的规则 (status=disabled, 不会评估) ----
INSERT INTO alert_rule
    (app_id, rule_name, rule_type, expression, threshold_value, duration_seconds, notify_channels, status, created_at)
VALUES
    (1, 'TEST_DISABLED', 'metric', 'jvm_memory_used_bytes > 0',
     0.0, 0, '{"email":"ops@example.com"}', 'disabled', NOW());

-- ============================================================
-- 3. 验证
-- ============================================================
SELECT id, app_id, rule_name, expression, threshold_value, duration_seconds, status
FROM alert_rule
WHERE rule_name LIKE 'TEST_%'
ORDER BY id;

-- 查看 alert_history 表确认触发记录
-- SELECT * FROM alert_history ORDER BY created_at DESC LIMIT 20;

-- 查看 Redis 状态 (需要连 Redis 6379)
-- KEYS alert:state:*
-- HGETALL alert:state:1:1    (ruleId=1, appid=1)
