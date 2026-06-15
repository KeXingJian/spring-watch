-- spring-watch 指标字典种子 SQL
-- 用途: 把原生指标(jvm / jdbc / http / system / tomcat)插入 metric_dict 字典表
-- 执行前需先确保 V11__metric_dict.sql 中的建表语句已执行(或手动建表)
--
-- 字典语义:
--   native      = OTel 自动 instrumentation 产出的指标
--   non-native  = @SpringWatch 切面产出的指标(method.duration)
-- metric_name 字段为 LIKE 匹配模式,告警评估时用 event.metricName LIKE dict.metric_name 命中
-- 非原生(method_duration_*)默认 enabled=FALSE,因为 method 名由用户在注解中自定义,
-- 是否启用监控在告警规则里按需开启。

-- ============================================================
-- 0. 准备表(若用 flyway V11 已建表可跳过,这里兜底)
-- ============================================================
CREATE TABLE IF NOT EXISTS metric_dict (
    id           BIGSERIAL PRIMARY KEY,
    metric_name  VARCHAR(256) NOT NULL UNIQUE,
    category     VARCHAR(32)  NOT NULL,
    type         VARCHAR(16)  NOT NULL,
    unit         VARCHAR(32),
    source       VARCHAR(64),
    description  VARCHAR(512),
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_metric_dict_category ON metric_dict (category);
CREATE INDEX IF NOT EXISTS idx_metric_dict_type     ON metric_dict (type);

-- ============================================================
-- 1. 清理旧种子,保证可重复执行
-- ============================================================
DELETE FROM metric_dict WHERE type = 'native';
DELETE FROM metric_dict WHERE type = 'non-native' AND metric_name LIKE 'method_duration_%';

-- ============================================================
-- 2. 原生指标种子
-- ============================================================
INSERT INTO metric_dict (metric_name, category, type, unit, source, description) VALUES
  -- ---------------- JVM ----------------
  ('jvm_memory_used_bytes',                 'JVM', 'native', 'bytes',   'runtime-jvm', 'JVM 各内存池已用字节数'),
  ('jvm_memory_committed_bytes',            'JVM', 'native', 'bytes',   'runtime-jvm', 'JVM 各内存池已提交字节数'),
  ('jvm_memory_max_bytes',                  'JVM', 'native', 'bytes',   'runtime-jvm', 'JVM 各内存池最大字节数'),
  ('jvm_memory_limit_bytes',                'JVM', 'native', 'bytes',   'runtime-jvm', 'JVM 各内存池上限字节数'),
  ('jvm_gc_pause%',                         'JVM', 'native', 's',       'runtime-jvm', 'GC 暂停耗时直方图'),
  ('jvm_gc_memory_allocated_bytes_total',   'JVM', 'native', 'bytes',   'runtime-jvm', 'GC 累计分配字节数'),
  ('jvm_gc_memory_promoted_bytes_total',    'JVM', 'native', 'bytes',   'runtime-jvm', 'GC 累计晋升到老年代字节数'),
  ('jvm_threads_states%',                   'JVM', 'native', 'threads', 'runtime-jvm', '各状态线程数'),
  ('jvm_threads_live_threads',              'JVM', 'native', 'threads', 'runtime-jvm', '当前活跃线程数'),
  ('jvm_threads_daemon_threads',            'JVM', 'native', 'threads', 'runtime-jvm', '当前守护线程数'),
  ('jvm_threads_peak_threads',              'JVM', 'native', 'threads', 'runtime-jvm', '峰值线程数'),
  ('jvm_classes%',                          'JVM', 'native', 'classes', 'runtime-jvm', '已加载 / 当前 / 已卸载类数'),
  ('jvm_cpu_recent_utilization',            'JVM', 'native', 'ratio',   'runtime-jvm', 'JVM 进程最近 CPU 利用率(0~1)'),
  ('jvm_info',                              'JVM', 'native', 'info',    'runtime-jvm', 'JVM 版本与厂商信息'),

  -- ---------------- JDBC ----------------
  ('jdbc_connections%',                     'JDBC', 'native', 'connections', 'jdbc',   'JDBC 连接池状态(min/max/idle/pending/active)'),
  ('jdbc_connections_usage',                'JDBC', 'native', 'ratio',      'jdbc',   'JDBC 连接使用率(0~1)'),
  ('hikaricp%',                             'JDBC', 'native', 'connections', 'hikari', 'HikariCP 连接池指标'),
  ('hikaricp_connections_usage',            'JDBC', 'native', 'ratio',      'hikari', 'HikariCP 连接使用率(0~1)'),

  -- ---------------- HTTP ----------------
  ('http_server_requests%',                 'HTTP', 'native', 's',        'http-server', '服务端 HTTP 请求耗时直方图'),
  ('http_server_active_requests',           'HTTP', 'native', 'requests', 'http-server', '服务端正在处理的请求数'),
  ('http_client_requests%',                 'HTTP', 'native', 's',        'http-client', '客户端 HTTP 请求耗时直方图'),

  -- ---------------- SYSTEM / 进程 ----------------
  ('process_cpu_usage',                     'SYSTEM', 'native', 'ratio', 'runtime-jvm', '进程最近 CPU 使用率(0~1)'),
  ('process_cpu_time%',                     'SYSTEM', 'native', 's',     'runtime-jvm', '进程累计 CPU 时间'),
  ('process_uptime',                        'SYSTEM', 'native', 's',     'runtime-jvm', 'JVM 进程已运行时长'),
  ('process_memory_usage',                  'SYSTEM', 'native', 'bytes', 'runtime-jvm', '进程物理内存使用量'),
  ('process_virtual_memory%',               'SYSTEM', 'native', 'bytes', 'runtime-jvm', '进程虚拟内存使用量'),
  ('process_open_fds',                      'SYSTEM', 'native', 'fds',   'runtime-jvm', '进程已打开文件描述符数(Linux)'),
  ('process_max_fds',                       'SYSTEM', 'native', 'fds',   'runtime-jvm', '进程可打开文件描述符上限(Linux)'),
  ('system_cpu%',                           'SYSTEM', 'native', 'ratio', 'runtime-jvm', '整机 CPU 使用率 / 平均负载'),
  ('system_memory%',                        'SYSTEM', 'native', 'bytes', 'runtime-jvm', '整机内存总量 / 可用量 / 使用量'),
  ('target_info',                           'SYSTEM', 'native', 'info',  'runtime-jvm', '目标资源属性(service_name/namespace/env)'),

  -- ---------------- TOMCAT ----------------
  ('tomcat_sessions%',                      'TOMCAT', 'native', 'sessions', 'tomcat', 'Tomcat Session 数量(active/expired/rejected)'),
  ('tomcat_threads%',                       'TOMCAT', 'native', 'threads',  'tomcat', 'Tomcat 连接线程数(config/current/busy)');

-- ============================================================
-- 3. 非原生指标(@SpringWatch 切面)
-- ============================================================
INSERT INTO metric_dict (metric_name, category, type, unit, source, description, enabled) VALUES
  ('method_duration_milliseconds_bucket', 'CUSTOM', 'non-native', 'ms',    '@SpringWatch', '@SpringWatch 方法耗时分布(bucket)', FALSE),
  ('method_duration_milliseconds_sum',     'CUSTOM', 'non-native', 'ms',    '@SpringWatch', '@SpringWatch 方法耗时总和',       FALSE),
  ('method_duration_milliseconds_count',   'CUSTOM', 'non-native', 'count', '@SpringWatch', '@SpringWatch 方法调用次数',       FALSE);

-- ============================================================
-- 4. 验证
-- ============================================================
SELECT category, type, COUNT(*) AS cnt
FROM metric_dict
GROUP BY category, type
ORDER BY type, category;
