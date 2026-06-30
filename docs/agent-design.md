# 自研 `spring-watch-agent.jar` 规划(M0 立项)

> 立项文档。对应白皮书 10 演进路线的 v2 目标。
> **状态**:规划中,代码未开工。
> **仓库定位**:本仓库(`spring-watch`)仅放规划文档,Agent 代码在**独立仓库** `spring-watch-agent/`(白皮书 10.5 反模式)。

---

## 0. 与白皮书的关系

| 章节 | 内容 | 白皮书引用 |
|---|---|---|
| 1 | v2 目标态回顾 | 白皮书 10.3 |
| 2 | 仓库定位(单独仓) | 白皮书 10.5 |
| 3 | 平台侧 0 改动的硬契约 | 白皮书 3 数据流 / 5 边界 |
| 4 | 目录结构 | — |
| 5 | 技术选型 | 白皮书 10.2 v1 → v2 对比 |
| 6 | 里程碑 | 白皮书 10.1 版本定位 |
| 7 | 关键风险 | 白皮书 10.5 反模式 |
| 8 | 灰度切换 | 白皮书 10.4 迁移清单 |
| 9 | 反模式自检 | 白皮书 10.5 |
| 10 | API 契约附录 | 白皮书 6 接入清单 |

---

## 1. 目标态(v2)回顾

```bash
java -javaagent:spring-watch-agent.jar \
     -DSPRING_WATCH_APPID=1234567890 \
     -DSPRING_WATCH_METRICS_PORT=9464 \
     -jar my-app.jar
```

接入成本硬上限(v2):
- 1 个 JVM 参数 `-javaagent:`
- 0 个文件复制
- 0 个 Maven 依赖
- 0 行 `@SpringBootApplication` 改动
- 0 个 OTel / AOP / 注解 import
- 1 个业务方法加 `@WithSpan("xxx")`(自研 Agent 字节码识别,**无需 annotation jar**)

---

## 2. 仓库定位(白皮书 10.5)

- ✅ **独立仓库** `spring-watch-agent/`,**不在本仓库内**,避免主仓库被未成熟代码污染
- ✅ 本仓库 `docs/agent-design.md` 仅做规划占位(M0)
- ❌ v1 阶段**禁止**让客户从 OTel Agent 切换到自研 Agent(没到切换窗口)
- ❌ v1 阶段**禁止**让客户 import spring-watch SDK / starter(违反约束 5)

---

## 3. 平台侧 0 改动的硬契约

平台侧 `AgentMetricsCollector` / `AgentLogCollector` / Kafka 消费者 / InfluxDB writer / Web API **一行不动**。这要求自研 Agent **1:1 对齐 v1.2 OTel 实际产出**。

### 3.1 `/metrics` 契约

v1.2 走 OTel `code-function-metrics`,平台 `AgentMetricsCollector.parsePrometheusLine` 解析。自研 Agent 必须 1:1 对齐 OTel 实际输出(M2 开工时先 dump 真实输出做金标)。

关键 metric 命名:
- `code_function_count_count_total`(counter)
- `code_function_duration_seconds`(histogram,默认桶 0, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 10000 ms)
- `target_info`(平台 `isOtelInfoMetric` 主动过滤,Agent 也必须产)
- 标签:`code_function` / `code_namespace` / `otel_scope_name` / `process_*`

### 3.2 `/api/agent/logs` 契约

`LogEvent` 字段(对齐 `mock-test/src/main/java/com/mock/test/logging/LogEvent.java`):
```json
{
  "appName": "mock-test",
  "level": "INFO",
  "logger": "com.mock.test.service.OrderService",
  "threadName": "http-nio-8081-exec-1",
  "message": "订单创建成功",
  "throwable": null,
  "traceId": null,
  "timestamp": "2026-06-25T13:00:00.123Z"
}
```

### 3.3 URL 形态(⚠️ 隐性矛盾)

平台 `AgentLogCollector.buildUrl`:
```java
return normalizeBaseUrl(endpoint) + "/api/agent/logs?since=" + since;
// endpoint = 业务端口(8081),非 metrics 端口
```

但白皮书 v2 接入只有 `-DSPRING_WATCH_METRICS_PORT=9464`,**没有业务端口参数**。

| 候选 | 描述 | 代价 |
|---|---|---|
| **A(推荐)** | Agent 自动探测业务端口:读 `-Dserver.port` / `SERVER_PORT` 环境变量,或 Spring `WebServerApplicationContext` 启动后回调注册 | 平台 0 改动,Agent 启动顺序复杂 |
| **B** | 日志端点走 `metricsPort`,平台 `AgentLogCollector` 改用 `target.metricsPort()` | 平台改 1 行,白皮书 10.2 "0 改动"小幅破例 |
| **C** | Agent 同时监听 metricsPort + 业务端口 | 复杂度↑,业务端口探测仍需 A |

> 选 A;若探测失败,fallback 到 C,最后兜底 B。

---

## 4. 目录结构(独立仓库 `spring-watch-agent/`)

```
spring-watch-agent/
├── pom.xml                          # premain + ByteBuddy
├── README.md
├── docs/
│   ├── architecture.md              # 本文档镜像
│   ├── api-contract.md              # /metrics + /api/agent/logs 字段定义
│   ├── migration-from-v1.2.md       # 灰度切换
│   └── benchmark.md
├── src/main/java/com/springwatch/agent/
│   ├── SpringWatchAgent.java        # premain(),ByteBuddy 安装
│   ├── config/AgentConfig.java      # 解析 -D 参数
│   ├── matchers/
│   │   ├── WithSpanMethodMatcher.java
│   │   ├── JdbcStatementMatcher.java
│   │   └── SpringDispatcherMatcher.java
│   ├── interceptor/
│   │   ├── MethodTimerAdvice.java   # 产 code.function.*
│   │   ├── JdbcTimerAdvice.java     # 产 db.client.requests
│   │   └── HttpTimerAdvice.java     # 产 http.server.requests
│   ├── log/
│   │   ├── LogbackAppender.java     # 自研 AppenderBase,写入 ringbuffer
│   │   └── RingLogBuffer.java       # ConcurrentLinkedDeque,容量可配
│   ├── meter/
│   │   ├── MeterRegistry.java       # 内部 metric 容器
│   │   ├── Counter.java / Histogram.java / Gauge.java
│   │   └── PrometheusFormatter.java # 手写文本格式
│   ├── server/
│   │   ├── MetricsHttpServer.java   # :metricsPort/metrics
│   │   └── LogsHttpServer.java      # 探测到的业务端口 /api/agent/logs
│   └── util/
│       ├── PortDetector.java        # -Dserver.port / Spring context
│       └── AppNameResolver.java     # -DSPRING_WATCH_APPID
├── src/main/resources/
│   ├── META-INF/MANIFEST.MF         # Premain-Class: SpringWatchAgent
│   └── agent-default.properties
└── src/test/...
```

---

## 5. 技术选型

| 关注点 | 选型 | 理由 |
|---|---|---|
| **字节码框架** | ByteBuddy 1.15+ | 主流 Java Agent 框架,与 OTel / Mockito 同款 |
| **HTTP Server** | JDK `com.sun.net.httpserver.HttpServer` | 零依赖,Java 18+ 配虚拟线程足够;不引 Netty |
| **JVM/OS metric** | `ManagementFactory` / `OperatingSystemMXBean` | 主仓库 `SelfMonitorCollector` 已验证 |
| **Prometheus 文本** | 手写 | 格式简单,避免 `micrometer-registry-prometheus` |
| **annotation 识别** | maven-shade 内嵌 `opentelemetry-instrumentation-annotations-2.12.0.jar` | Agent 内 classpath 优先,业务 pom 可保留或删 |
| **logback 拦截** | 自研 `AppenderBase<ILoggingEvent>` + `LoggerFactory.getILoggerFactory().getILoggerFactory()` 拿 `LoggerContext` | 与 v1.2 行为等价,代码量 < 100 行 |
| **JDK 25 模块化** | ByteBuddy `Advice` + `MethodHandles.privateLookupIn` | ByteBuddy 已处理 JDK 21+ `--add-opens` 边界 |

---

## 6. 里程碑

| 阶段 | 内容 | 验收 |
|---|---|---|
| **M0 立项** | 本文档;主仓库 `docs/agent-design.md` 占位 | — |
| **M1 骨架** | 新建 `spring-watch-agent/`;`premain()` + 简单 `:9464/metrics` 返回 `spring_watch_agent_up 1` | `curl :9464/metrics` 命中;mock-test 仍挂 OTel Agent,平台 0 改动 |
| **M2 方法级 metric** | ByteBuddy 扫 `@WithSpan`,`MethodTimerAdvice` 织入,产 `code.function.*`(格式与 OTel 等价) | mock-test **挂自研 Agent + 不挂 OTel**,平台能拉到 `code.function.count` / `code.function.duration` |
| **M3 日志端点** | Agent hook logback,`/api/agent/logs?since=ISO` 返回 `LogEvent` JSON 数组 | mock-test 删 `InMemoryLogBufferAppender` + `AgentLogController` + logback INMEM 配置,平台能拉到日志 |
| **M4 基础监控** | JVM/OS metric + `process_*`;HTTP 入口 metric;JDBC 拦截 metric(可分 M4a/M4b/M4c) | 平台 `springboot_metrics` 表的 `process_*` / `http_server_requests` / `db.client.requests` 等行继续有数据 |
| **M5 性能/测试/文档** | Agent 自身开销 benchmark;灰度切换文档;E2E | 冷启动 < 100ms;稳态 heap 增量 < 5MB;v1.2 回归测试通过 |

---

## 7. 关键风险

| 风险 | 缓解 |
|---|---|
| **R1 `@WithSpan` 类加载冲突** | Agent 用 maven-shade 内嵌 annotation jar,业务 classpath 内的同名类被 Agent 优先加载,功能等价 |
| **R2 Prometheus 格式细节** | M2 第一步 dump OTel `code-function-metrics` 真实输出做金标,逐字段对齐 |
| **R3 业务端口探测** | 见 3.3;若探测失败,平台侧 `AgentLogCollector` 加 metricsPort 选项(白皮书 10.2 0 改动小幅破例) |
| **R4 logback context 时机** | Agent premain 早于 logback 初始化 → `LoggerFactory.getILoggerFactory().getILoggerFactory()` 配 `if (lc instanceof LoggerContext)` 守卫,或注册 `LoggerContextListener` |
| **R5 ringbuffer 内存** | 默认 1000 条;P1 做 mmap 兜底(白皮书 Q0.5) |
| **R6 Agent 自身崩溃** | 所有 hook try-catch Throwable 兜底,绝不让 Agent 把客户业务搞挂 |

---

## 8. 灰度切换路径(白皮书 10.4)

```
v1.2(当前)                    v2(目标)
─────────────────             ─────────────────
mock-test:                     mock-test:
  -javaagent:otel.jar          -javaagent:spring-watch-agent.jar
  InMemoryLogBufferAppender    (无日志 appender)
  AgentLogController           (无 controller)
  OTel 暴露 :9464/metrics      自研 Agent 暴露 :9464/metrics
                               自研 Agent 暴露 :业务端口/api/agent/logs

平台侧(0 改动):
  AgentMetricsCollector 拉 :9464/metrics
  AgentLogCollector     拉 :业务端口/api/agent/logs
```

切换步骤:
1. **双挂阶段**:mock-test 同时挂 OTel + 自研 Agent,通过 `-DSPRING_WATCH_AGENT_METHOD=true` / `-DSPRING_WATCH_AGENT_LOG=true` 控制接管范围
2. **回归测试**:v1.2 流程仍可用(OTel 还在)
3. **逐步切换**:先日志端点(M3),再方法级 metric(M2),最后基础监控(M4)
4. **完全卸载**:mock-test 卸载 OTel Agent,卸载 `InMemoryLogBufferAppender` / `AgentLogController` / annotation 依赖
5. **README 更新**:替换为白皮书 10.3 形态的 v2 接入文档

---

## 9. 反模式自检(白皮书 10.5)

- [x] 不让客户 import spring-watch SDK / starter
- [x] 不放弃 OTel / 改 AOP jar
- [x] 日志端点保持 GET 拉,不上 OTLP/WebSocket/gRPC stream
- [x] 不让客户在方法体里手写 `Timer.record(...)`
- [x] 不让客户用 actuator 的 `/actuator/prometheus`
- [x] 方法级监控底层仍是 metric,不上 trace
- [x] 单独仓库,不污染主仓库代码
- [x] v1 阶段不动接入流程,只立项

---

## 10. 附录:API 契约细节

### 10.1 Agent `-D` 参数

| 参数 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `SPRING_WATCH_APPID` | 是 | — | 53 bit 雪花,与平台 `monitor_app.appid` 对齐 |
| `SPRING_WATCH_METRICS_PORT` | 否 | 9464 | `/metrics` 端点监听端口 |
| `SPRING_WATCH_AGENT_METHOD` | 否 | true | 是否接管方法级 metric |
| `SPRING_WATCH_AGENT_LOG` | 否 | true | 是否接管日志端点 |
| `SPRING_WATCH_AGENT_BASE` | 否 | true | 是否接管 JVM/OS 基础 metric |
| `SPRING_WATCH_AGENT_JDBC` | 否 | true | 是否接管 JDBC metric |
| `SPRING_WATCH_AGENT_HTTP` | 否 | true | 是否接管 HTTP 入口 metric |
| `SPRING_WATCH_LOG_BUFFER_SIZE` | 否 | 1000 | 日志 ringbuffer 容量 |

### 10.2 端点 URL

| 端点 | URL | 端口 |
|---|---|---|
| 指标 | `http://0.0.0.0:{SPRING_WATCH_METRICS_PORT}/metrics` | `SPRING_WATCH_METRICS_PORT` |
| 日志 | `http://0.0.0.0:{探测到的业务端口}/api/agent/logs?since=ISO_INSTANT` | 业务端口(探测) |
| 健康检查 | `http://0.0.0.0:{SPRING_WATCH_METRICS_PORT}/health` | `SPRING_WATCH_METRICS_PORT`(平台不使用,运维/排障用) |

### 10.3 日志端点返回格式

与 `LogEvent.java` 1:1 对齐,`timestamp` 用 `Instant.toString()`(ISO-8601,UTC)。

---

## 11. 版本

- v0.1(本版本,M0):立项 + 规划,无代码
- v0.2(M1 后):M1 骨架完成后更新
- v1.0(M5 后):M5 完成后冻结,作为 v2 接入文档的依据
