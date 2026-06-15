# Agent 端原始指标 / 日志 / 拦截方法数据结构

> 调研对象:`D:\codespace\ideaProject\spring-watch` 及其子模块 `mock-test`
> 调研时间:2026-06-15
> 目的:梳理从目标 Java agent 侧拉取到的**未经 spring-watch 转化**的原始数据,以及 `@SpringWatch` 拦截方法产生的数据结构。

---

## 一、采集拓扑总览

```
┌──────────────────────────── 目标应用(mock-test) ────────────────────────────┐
│  JVM                                                                       │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │ -javaagent:opentelemetry-javaagent.jar                               │  │
│  │   -Dotel.metrics.exporter=prometheus                                 │  │
│  │   -Dotel.logs.exporter=none                                         │  │
│  │   -Dotel.traces.exporter=none                                       │  │
│  │   -Dotel.exporter.prometheus.port=9464                              │  │
│  │   -Dotel.instrumentation.{http,servlet,spring-web,jdbc,hikari,...}  │  │
│  │   -Dotel.instrumentation.logback-appender.enabled=true              │  │
│  │   -Dotel.instrumentation.code-function-metrics.enabled=true         │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│           │                                          │                    │
│           ▼                                          ▼                    │
│  ┌────────────────────┐                    ┌──────────────────────┐     │
│  │ Prometheus 端点    │                    │ Logback INMEM Appender│    │
│  │ :9464/metrics      │                    │ com.mock.test 包下    │    │
│  │ (文本格式)         │                    │ 1000 条环形缓冲       │    │
│  └────────────────────┘                    └──────────────────────┘     │
│           │                                          │                    │
└───────────┼──────────────────────────────────────────┼────────────────────┘
            │ GET /metrics                             │ GET /api/agent/logs?since=<iso>
            ▼                                          ▼
┌──────────────────────── spring-watch 监控端 ──────────────────────────────┐
│  AgentMetricsCollector                                                    │
│  AgentLogCollector                                                        │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 二、指标数据 — OTel Agent 暴露的 Prometheus 文本

### 2.1 拉取方式

`AgentMetricsCollector.collect()`(`AgentMetricsCollector.java:23-76`)对每个目标应用构造 URL:

```java
host + ":" + target.metricsPort() + "/metrics"
// 默认端口 9464(由 -Dotel.exporter.prometheus.port 配置)
```

通过 `HttpURLConnection` GET 拉取,逐行按 Prometheus 文本格式解析(`AgentMetricsCollector.java:89-128`)。每行拆成:
- `metricName`(指标名)
- `tags`(`{k="v",...}` 标签)
- `value`(末尾浮点数)

封装为 `MetricEvent` 后由 `KafkaProducerBridge.sendMetric()` 投递到 Kafka。

### 2.2 原始数据样例(完整 Prometheus 文本格式)

```
# HELP http_server_requests_seconds Duration of HTTP server request handling
# TYPE http_server_requests_seconds histogram
http_server_requests_seconds_bucket{le="0.005",method="POST",uri="/order",status="200",} 14.0
http_server_requests_seconds_bucket{le="0.01",method="POST",uri="/order",status="200",} 20.0
http_server_requests_seconds_bucket{le="0.025",method="POST",uri="/order",status="200",} 24.0
http_server_requests_seconds_bucket{le="0.05",method="POST",uri="/order",status="200",} 25.0
http_server_requests_seconds_bucket{le="+Inf",method="POST",uri="/order",status="200",} 25.0
http_server_requests_seconds_count{method="POST",uri="/order",status="200",} 25.0
http_server_requests_seconds_sum{method="POST",uri="/order",status="200",} 1.234
http_server_requests_seconds_bucket{le="0.005",method="GET",uri="/product/list",status="200",} 100.0
http_server_requests_seconds_count{method="GET",uri="/product/list",status="200",} 100.0
http_server_requests_seconds_sum{method="GET",uri="/product/list",status="200",} 0.5

# HELP jvm_memory_used_bytes Used memory of pools
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{pool="G1 Eden"} 4.2E7
jvm_memory_used_bytes{pool="G1 Old Gen"} 1.2E8
jvm_memory_used_bytes{pool="CodeCache"} 2.5E7

# HELP jvm_gc_pause_seconds Time spent in GC pause
# TYPE jvm_gc_pause_seconds histogram
jvm_gc_pause_seconds_count{cause="G1 Evacuation Pause",action="end of minor GC",} 3.0
jvm_gc_pause_seconds_sum{cause="G1 Evacuation Pause",action="end of minor GC",} 0.045

# HELP process_cpu_usage Recent cpu usage for the process
# TYPE process_cpu_usage gauge
process_cpu_usage 0.012

# HELP hikaricp_connections Active connections
# TYPE hikaricp_connections gauge
hikaricp_connections{pool="HikariPool-1"} 5.0
hikaricp_connections_usage{pool="HikariPool-1"} 0.5

# HELP target_info Target metadata
# TYPE target_info gauge
target_info{service_name="mock-test",service_namespace="spring-watch",deployment_environment="docker",} 1
```

每行结构(被 `AgentMetricsCollector.parsePrometheusLine` 解析):

```
<metric_name>{<label>=<value>,...} <numeric_value> [<timestamp>]
```

### 2.3 指标族 → 启用的 instrumentation 映射

来自 `entrypoint.sh:19-29`(`-Dotel.instrumentation.*.enabled=true`):

| Prometheus 指标族 | 类型 | 来源 instrumentation |
|---|---|---|
| `http_server_requests_*` | histogram | `http-server` / `servlet` / `spring-web` / `spring-webmvc` |
| `http_client_requests_*` | histogram | 同上客户端侧 |
| `tomcat_sessions_*`、`tomcat_threads_*` | gauge / up_down | `tomcat` |
| `jdbc_connections_*` | up_down_counter | `jdbc` |
| `hikaricp_*` | gauge | `hikari` |
| `jvm_memory_*`、`jvm_gc_*`、`jvm_threads_*`、`jvm_classes_*` | gauge / histogram | `runtime-jvm`(自动) |
| `process_cpu_*`、`process_uptime` | gauge / counter | `runtime-jvm`(自动) |
| `method_duration_*` | histogram | `@SpringWatch` 切面(业务自定义) |
| `target_info` | gauge | resource attributes(每条 metric 的全局标签) |

### 2.4 Resource Attributes(全局资源标签)

在 `entrypoint.sh:13` 中配置,会注入到所有指标和 `target_info`:

| 属性 | 值示例 | 来源 |
|---|---|---|
| `service.name` | `mock-test` / `appid` 数字 | `OTEL_SERVICE_NAME` |
| `service.namespace` | `spring-watch` | 硬编码 |
| `deployment.environment` | `docker` | 硬编码(生产可换) |

> 主仓 `OtelConfigGenerator.java:19-32` 中,`service.name` 直接用 `appid` 数字,这是为了与 `MonitorApp.appid` 对齐,便于按应用过滤指标。

---

## 三、日志数据 — `InMemoryLogBufferAppender` 内存缓冲

### 3.1 拉取方式

`AgentLogController`(`mock-test/.../AgentLogController.java:17-26`):

```
GET /api/agent/logs?since=<ISO-8601>
→ 返回 InMemoryLogBufferAppender 缓冲中 timestamp > since 的所有日志(JSON 数组)
```

被 `AgentLogCollector.collect()`(`AgentLogCollector.java:25-82`)反序列化为 `List<LogEvent>`,补全 `appid` / `host` 后投递到 Kafka。

### 3.2 数据结构定义

`mock-test/src/main/java/com/mock/test/logging/LogEvent.java:14-23`:

```java
@Builder
public class LogEvent {
    private String appName;     // 应用名(从 loggerContextVO 的 APP_NAME 属性读)
    private String level;       // 日志级别 INFO/WARN/ERROR/DEBUG/TRACE
    private String logger;      // logger 名(FQCN)
    private String threadName;  // 线程名
    private String message;     // 格式化后的消息文本
    private String throwable;   // 堆栈序列化字符串(ThrowableProxyUtil.asString)
    private String traceId;     // OTel trace_id(从 SLF4J MDC 读)
    private Instant timestamp;  // event.getTimeStamp()
}
```

### 3.3 原始 JSON 样例

```json
[
  {
    "appName": "mock-test",
    "level": "INFO",
    "logger": "com.mock.test.service.OrderService",
    "threadName": "http-nio-8081-exec-3",
    "message": "订单创建成功 orderId=10086",
    "throwable": null,
    "traceId": "a1b2c3d4e5f6g7h8",
    "timestamp": "2026-06-15T10:23:45.678Z"
  },
  {
    "appName": "mock-test",
    "level": "ERROR",
    "logger": "com.mock.test.dao.OrderDao",
    "threadName": "http-nio-8081-exec-5",
    "message": "订单持久化失败",
    "throwable": "java.lang.RuntimeException: 数据库连接超时\n\tat com.mock.test.dao.OrderDao.insert(OrderDao.java:42)\n\tat com.mock.test.service.OrderService.create(OrderService.java:31)\n\t...\n",
    "traceId": "x9y8z7w6v5u4t3s2",
    "timestamp": "2026-06-15T10:23:46.012Z"
  }
]
```

### 3.4 字段来源映射

来自 `InMemoryLogBufferAppender.append()`(`InMemoryLogBufferAppender.java:26-55`):

| 字段 | 来源 |
|---|---|
| `appName` | `event.getLoggerContextVO().getPropertyMap().get("APP_NAME")`,默认 `"mock-test"` |
| `level` | `event.getLevel().toString()` |
| `logger` | `event.getLoggerName()` |
| `threadName` | `event.getThreadName()` |
| `message` | `event.getFormattedMessage()` |
| `throwable` | `ThrowableProxyUtil.asString(tp)`,无异常时为 `null` |
| `traceId` | `MDC.get("trace_id")` |
| `timestamp` | `Instant.ofEpochMilli(event.getTimeStamp())` |

### 3.5 Logback 装配(`logback-spring.xml`)

```xml
<property name="APP_NAME" value="mock-test"/>

<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
        <pattern>%d{HH:mm:ss.SSS} [%thread] [%5level] traceId=%mdc{trace_id:-} spanId=%mdc{span_id:-} %logger{36} - %msg%n</pattern>
    </encoder>
</appender>

<appender name="INMEM" class="com.mock.test.logging.InMemoryLogBufferAppender">
    <maxSize>1000</maxSize>
</appender>

<logger name="com.mock.test" level="INFO" additivity="false">
    <appender-ref ref="CONSOLE"/>
    <appender-ref ref="INMEM"/>
</logger>

<root level="INFO">
    <appender-ref ref="CONSOLE"/>
</root>
```

要点:
- `INMEM` appender 容量 1000 条,超出 `pollLast()`(即丢最老)
- 仅 `com.mock.test` 包下日志被采集,`root` 的通用日志不进缓冲
- `traceId` 由 OTel `logback-appender` instrumentation 注入 MDC(因为 `entrypoint.sh:15` 是 `otel.logs.exporter=none`,日志不通过 OTLP 上报,只借 instrumentation 给日志打 trace 关联)

### 3.6 监控端补全字段

`AgentLogCollector` 拿到原始 `LogEvent` 列表后(`AgentLogCollector.java:52-67`),会做以下补全:

| 补字段 | 补全逻辑 |
|---|---|
| `appid` | 监控端在调度时持有的目标 `appid` |
| `host` | 解析 endpoint URL 的 host(用 `URI.create(endpoint).getHost()`) |
| `timestamp` | 为空时填 `Instant.now()`(兜底) |

最终入库到 spring-watch 内部流转的 `com.springwatch.model.event.LogEvent`(`src/.../model/event/LogEvent.java:12-30`)字段为:
```
appid, level, logger, threadName, message, throwable, traceId, timestamp,
host, service, method, env, fingerprint, pattern
```
其中 `service/method/env` 来自 `MonitorApp` 配置,`fingerprint/pattern` 来自 `LogFingerprinter`(日志归一化指纹)。

---

## 四、`@SpringWatch` 拦截方法的数据

### 4.1 注解定义(`mock-test/.../SpringWatch.java`)

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SpringWatch {
    String value() default "";   // 可选别名,空时用 "类全名.方法名"
}
```

### 4.2 切面实现(`mock-test/.../SpringWatchAspect.java:26-46`)

```java
@Around("@annotation(annotation)")
public Object around(ProceedingJoinPoint pjp, SpringWatch annotation) throws Throwable {
    String name = pjp.getSignature().getDeclaringTypeName() + "."
                + pjp.getSignature().getName();
    if (!annotation.value().isEmpty()) {
        name = annotation.value();
    }
    DoubleHistogram hist = HISTS.computeIfAbsent(name, n ->
        METER.histogramBuilder("method.duration")
            .setDescription("方法耗时")
            .setUnit("ms")
            .build());
    long start = System.nanoTime();
    try {
        return pjp.proceed();
    } finally {
        long costMs = (System.nanoTime() - start) / 1_000_000;
        hist.record(costMs, Attributes.of(METHOD_KEY, name));
    }
}
```

### 4.3 关键事实

- `METER` = `GlobalOpenTelemetry.get().getMeter("spring-watch")`(注入名 `"spring-watch"`,即 instrumentation scope name)
- 指标名固定为 `method.duration`,单位 `ms`,类型 `Histogram`
- 唯一 attribute: `method=<FQN 或注解别名>`,类型 `String`
- `HISTS` 是 `ConcurrentMap<String, DoubleHistogram>`,**每个方法名一个独立 Histogram**(避免 attribute 拼接导致基数爆炸)
- 切面只统计耗时,**不采集参数 / 返回值 / 异常**;异常路径走 `finally`,同样 record 耗时(包含异常处理时间)

### 4.4 在 `:9464/metrics` 暴露的原始文本

跟其他 OTel 指标走同一条 Prometheus exporter,所以最终呈现:

```
# HELP method_duration Method execution duration
# TYPE method_duration histogram
method_duration_unit{service_name="mock-test",method="com.mock.test.service.OrderService.create",} ms
method_duration_bucket{le="5.0",service_name="mock-test",method="com.mock.test.service.OrderService.create",} 12.0
method_duration_bucket{le="10.0",service_name="mock-test",method="com.mock.test.service.OrderService.create",} 18.0
method_duration_bucket{le="25.0",service_name="mock-test",method="com.mock.test.service.OrderService.create",} 20.0
method_duration_bucket{le="50.0",service_name="mock-test",method="com.mock.test.service.OrderService.create",} 20.0
method_duration_bucket{le="75.0",service_name="mock-test",method="com.mock.test.service.OrderService.create",} 20.0
method_duration_bucket{le="100.0",service_name="mock-test",method="com.mock.test.service.OrderService.create",} 20.0
method_duration_bucket{le="250.0",service_name="mock-test",method="com.mock.test.service.OrderService.create",} 20.0
method_duration_bucket{le="500.0",service_name="mock-test",method="com.mock.test.service.OrderService.create",} 20.0
method_duration_bucket{le="750.0",service_name="mock-test",method="com.mock.test.service.OrderService.create",} 20.0
method_duration_bucket{le="1000.0",service_name="mock-test",method="com.mock.test.service.OrderService.create",} 20.0
method_duration_bucket{le="2500.0",service_name="mock-test",method="com.mock.test.service.OrderService.create",} 20.0
method_duration_bucket{le="5000.0",service_name="mock-test",method="com.mock.test.service.OrderService.create",} 20.0
method_duration_bucket{le="7500.0",service_name="mock-test",method="com.mock.test.service.OrderService.create",} 20.0
method_duration_bucket{le="10000.0",service_name="mock-test",method="com.mock.test.service.OrderService.create",} 20.0
method_duration_bucket{le="+Inf",service_name="mock-test",method="com.mock.test.service.OrderService.create",} 20.0
method_duration_count{service_name="mock-test",method="com.mock.test.service.OrderService.create",} 20.0
method_duration_sum{service_name="mock-test",method="com.mock.test.service.OrderService.create",} 234.5
```

> 注意 OTel Prometheus exporter 会自动给所有 metric 加上 `service_name` / `service_namespace` / `deployment_environment` 三个 resource attribute 标签(也写一份到 `target_info`)。

### 4.5 主仓情况

`src/main/java/com/springwatch/annotation/SpringWatch.java` 与 `SpringWatchAspect.java` 整文件被注释,只有 `mock-test` 里的实现是激活的。若要在生产应用启用,需要把这两个类复制到目标应用的源码,并保证引入 OTel SDK 依赖 + `GlobalOpenTelemetry` 已被初始化(通常靠 OTel Java agent 自动初始化)。

---

## 五、三类数据汇总对照表

| 数据类别 | 来源端点 | 数据格式 | 字段 | 监控端对应类 | 投递通道 |
|---|---|---|---|---|---|
| 通用指标 | `GET :9464/metrics` | Prometheus 文本(逐行 `name{tags} value`) | metricName、tags、value | `MetricEvent` | Kafka |
| 业务方法耗时 | `GET :9464/metrics` | Prometheus 文本(同上行) | `method_duration_*` with `method` label | `MetricEvent` | Kafka |
| 应用日志 | `GET /api/agent/logs?since=<iso>` | JSON 数组(自定义 LogEvent) | appName/level/logger/threadName/message/throwable/traceId/timestamp | `LogEvent` | Kafka |
| 心跳 | `POST /api/agent/heartbeat`(目标主动) | JSON | appid/ip/agentVersion/timestamp | `HeartbeatEvent` | Kafka |

---

## 六、关键文件索引

| 关注点 | 文件路径 |
|---|---|
| 指标拉取 | `src/main/java/com/springwatch/collector/AgentMetricsCollector.java` |
| 日志拉取 | `src/main/java/com/springwatch/collector/AgentLogCollector.java` |
| Kafka 投递 | `src/main/java/com/springwatch/collector/KafkaProducerBridge.java` |
| 监控端 `MetricEvent` | `src/main/java/com/springwatch/model/event/MetricEvent.java` |
| 监控端 `LogEvent` | `src/main/java/com/springwatch/model/event/LogEvent.java` |
| Agent 启动命令 | `mock-test/entrypoint.sh` |
| Logback 配置 | `mock-test/src/main/resources/logback-spring.xml` |
| 内存日志 Appender | `mock-test/src/main/java/com/mock/test/logging/InMemoryLogBufferAppender.java` |
| Agent 端 LogEvent | `mock-test/src/main/java/com/mock/test/logging/LogEvent.java` |
| Agent 日志拉取 Controller | `mock-test/src/main/java/com/mock/test/controller/AgentLogController.java` |
| `@SpringWatch` 注解 | `mock-test/src/main/java/com/springwatch/SpringWatch.java` |
| `@SpringWatch` 切面 | `mock-test/src/main/java/com/springwatch/SpringWatchAspect.java` |
| OTel 配置生成 | `src/main/java/com/springwatch/collector/OtelConfigGenerator.java` |
