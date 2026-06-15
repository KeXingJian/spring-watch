-- kxj: 指标字典表 metric_dict,登记 native 与 non-native 两类指标
-- 背景: alert_rule.expression 里要写 metric 名称,目前没有统一的字典可查、容易写错
--       native  = 来自 OTel 自动 instrumentation(JVM/JDBC/HTTP/System/Tomcat/Hikari)
--       non-native = 来自 @SpringWatch 切面(method.duration -> method_duration_*)
-- 字段 metric_name 使用 LIKE 匹配模式:
--   精确名(如 jvm_memory_used_bytes)与前缀名(如 jvm_gc_pause%)并存
--   评估告警时,event.metricName LIKE dict.metric_name 命中即算匹配

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

COMMENT ON TABLE  metric_dict IS '指标字典:native = OTel 自动 instrumentation, non-native = @SpringWatch 业务自定义';
COMMENT ON COLUMN metric_dict.metric_name IS '指标匹配模式,支持 LIKE 语义(如 jvm_gc_pause% 前缀匹配)';
COMMENT ON COLUMN metric_dict.type       IS 'native / non-native';
COMMENT ON COLUMN metric_dict.category   IS 'JVM / JDBC / HTTP / SYSTEM / TOMCAT / CUSTOM';

-- ============================================================
-- 原生指标 (native)  -- 来自 OTel 自动 instrumentation
-- ============================================================

-- ---------------- JVM (runtime-jvm) ----------------
INSERT INTO metric_dict (metric_name, category, type, unit, source, description) VALUES
  ('jvm_memory_used_bytes',      'JVM', 'native', 'bytes', 'runtime-jvm',          'JVM 各内存池已用字节数'),
  ('jvm_memory_committed_bytes', 'JVM', 'native', 'bytes', 'runtime-jvm',          'JVM 各内存池已提交字节数'),
  ('jvm_memory_max_bytes',       'JVM', 'native', 'bytes', 'runtime-jvm',          'JVM 各内存池最大字节数'),
  ('jvm_memory_limit_bytes',     'JVM', 'native', 'bytes', 'runtime-jvm',          'JVM 各内存池上限字节数'),
  ('jvm_gc_pause%',              'JVM', 'native', 's',    'runtime-jvm',          'GC 暂停耗时直方图(_count / _sum / _bucket)'),
  ('jvm_gc_memory_allocated_bytes_total', 'JVM', 'native', 'bytes', 'runtime-jvm', 'GC 累计分配字节数'),
  ('jvm_gc_memory_promoted_bytes_total',  'JVM', 'native', 'bytes', 'runtime-jvm', 'GC 累计晋升到老年代的字节数'),
  ('jvm_threads_states%',        'JVM', 'native', 'threads', 'runtime-jvm',        '各状态线程数(new / runnable / blocked / waiting / timed_waiting / terminated)'),
  ('jvm_threads_live_threads',   'JVM', 'native', 'threads', 'runtime-jvm',        '当前活跃线程数'),
  ('jvm_threads_daemon_threads', 'JVM', 'native', 'threads', 'runtime-jvm',        '当前守护线程数'),
  ('jvm_threads_peak_threads',   'JVM', 'native', 'threads', 'runtime-jvm',        '峰值线程数'),
  ('jvm_classes%',               'JVM', 'native', 'classes', 'runtime-jvm',        '已加载 / 当前加载 / 已卸载类数'),
  ('jvm_cpu_recent_utilization', 'JVM', 'native', 'ratio', 'runtime-jvm',          'JVM 进程最近 CPU 利用率(0~1)'),
  ('jvm_info',                   'JVM', 'native', 'info',  'runtime-jvm',          'JVM 版本与厂商信息(gauge=1)');

-- ---------------- JDBC (jdbc instrumentation) ----------------
INSERT INTO metric_dict (metric_name, category, type, unit, source, description) VALUES
  ('jdbc_connections%',          'JDBC', 'native', 'connections', 'jdbc',          'JDBC 连接池状态(min / max / idle / pending / active)'),
  ('jdbc_connections_usage',     'JDBC', 'native', 'ratio',      'jdbc',           'JDBC 连接使用率(0~1)'),
  ('hikaricp%',                  'JDBC', 'native', 'connections', 'hikari',         'HikariCP 连接池指标(connections / usage / acquire / creation)'),
  ('hikaricp_connections_usage', 'JDBC', 'native', 'ratio',      'hikari',         'HikariCP 连接使用率(0~1)');

-- ---------------- HTTP (http-server / servlet / spring-web) ----------------
INSERT INTO metric_dict (metric_name, category, type, unit, source, description) VALUES
  ('http_server_requests%',      'HTTP', 'native', 's',  'http-server',             '服务端 HTTP 请求耗时直方图(method / uri / status)'),
  ('http_server_active_requests','HTTP', 'native', 'requests', 'http-server',        '服务端正在处理的请求数(up_down_counter)'),
  ('http_client_requests%',      'HTTP', 'native', 's',  'http-client',             '客户端 HTTP 请求耗时直方图(method / url / status)');

-- ---------------- SYSTEM (进程 / 操作系统, runtime-jvm 自动) ----------------
INSERT INTO metric_dict (metric_name, category, type, unit, source, description) VALUES
  ('process_cpu_usage',          'SYSTEM', 'native', 'ratio', 'runtime-jvm',           '进程最近 CPU 使用率(0~1)'),
  ('process_cpu_time%',          'SYSTEM', 'native', 's',     'runtime-jvm',           '进程累计 CPU 时间'),
  ('process_uptime',             'SYSTEM', 'native', 's',     'runtime-jvm',           'JVM 进程已运行时长(秒)'),
  ('process_memory_usage',       'SYSTEM', 'native', 'bytes', 'runtime-jvm',           '进程物理内存使用量'),
  ('process_virtual_memory%',    'SYSTEM', 'native', 'bytes', 'runtime-jvm',           '进程虚拟内存使用量'),
  ('process_open_fds',           'SYSTEM', 'native', 'fds',   'runtime-jvm',           '进程已打开文件描述符数(Linux)'),
  ('process_max_fds',            'SYSTEM', 'native', 'fds',   'runtime-jvm',           '进程可打开文件描述符上限(Linux)'),
  ('system_cpu%',                'SYSTEM', 'native', 'ratio', 'runtime-jvm',           '整机 CPU 使用率 / 平均负载'),
  ('system_memory%',             'SYSTEM', 'native', 'bytes', 'runtime-jvm',           '整机内存总量 / 可用量 / 使用量');

-- ---------------- TOMCAT (tomcat instrumentation) ----------------
INSERT INTO metric_dict (metric_name, category, type, unit, source, description) VALUES
  ('tomcat_sessions%',           'TOMCAT', 'native', 'sessions', 'tomcat',             'Tomcat Session 数量(active / expired / rejected)'),
  ('tomcat_threads%',            'TOMCAT', 'native', 'threads',  'tomcat',             'Tomcat 连接线程数(config / current / busy)');

-- ---------------- 资源属性 ----------------
INSERT INTO metric_dict (metric_name, category, type, unit, source, description) VALUES
  ('target_info',                'SYSTEM', 'native', 'info',  'runtime-jvm',           '目标资源属性(service_name / namespace / environment),gauge=1');

-- ============================================================
-- 非原生指标 (non-native)  -- @SpringWatch 切面产生
-- 说明: method.duration 是 histogram,Prometheus exporter 输出为 method_duration_milliseconds_{bucket,sum,count}
--       method 名是用户在 @SpringWatch("xxx") 中自定义,运行时动态生成,所以字典只登记通用模式
-- ============================================================
INSERT INTO metric_dict (metric_name, category, type, unit, source, description, enabled) VALUES
  ('method_duration_milliseconds_bucket', 'CUSTOM', 'non-native', 'ms',  '@SpringWatch', '@SpringWatch 注解方法耗时分布(bucket)', FALSE),
  ('method_duration_milliseconds_sum',     'CUSTOM', 'non-native', 'ms',  '@SpringWatch', '@SpringWatch 注解方法耗时总和',       FALSE),
  ('method_duration_milliseconds_count',   'CUSTOM', 'non-native', 'count','@SpringWatch','@SpringWatch 注解方法调用次数',       FALSE);
