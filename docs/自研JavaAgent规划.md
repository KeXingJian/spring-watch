# 自研 Java Agent 规划(spring-watch v2)

> **文档定位**:本文是白皮书 10「演进路线」v2 目标的**工程落地规划**,不是对白皮书的替代。
> 白皮书是定性文件(v2 = 自研 `spring-watch-agent.jar`,一步接入),但已过时,缺失三块:
> ① 日志拉取端点的**具体协议定义**;② 方法级监控的**字节码织入细节**;③ SQL 监控**完全没有方案**(README TODO 仅一行"JDBC 拦截")。
> 本文基于白皮书 5 大硬约束 + 对主仓库 / mock-test 的实际扫描,给出最小实现、参考框架与向前兼容策略。

---

## 0. 结论摘要(先看这个)

| 问题 | 结论 |
|---|---|
| **日志拉取怎么做** | Agent 内置 JDK `HttpServer` + 编程式注册 logback Appender + 有界环形缓冲,暴露 `GET /api/agent/logs?since=<ISO_INSTANT>`,**协议与 v1 客户补丁完全一致 → 平台侧 0 改动** |
| **方法级监控怎么做** | ByteBuddy 按**注解描述符字符串**匹配 `@WithSpan` / 自研 `@SwMon`,织入计数 + 耗时直方图,产 `sw_method_*` 指标进 `/metrics` → 平台侧 0 改动 |
| **SQL 监控怎么做** | P1 先织入 `org.springframework.jdbc.core.JdbcTemplate`(系统类加载器,无 bootstrap 复杂度)产 `sw_sql_*` 指标;**预留 java.sql 层 JDBC 拦截插槽**,P2 补全原生 JDBC 覆盖 |
| **最小实现** | 零客户侧依赖、零平台侧改动、零文件复制;Agent 内部仅依赖一个 maven-shade 打进 jar 的 ByteBuddy |
| **向前兼容** | 平台两个契约(`/metrics` 文本 + `/api/agent/logs?since=`)是稳定 API,只增强不破坏;`target_info` 携带版本 + 能力声明 |

**贯穿全文的一条主线**:v2 的胜利不是"新端点",而是"**同一份契约,更强的实现方**"。
平台只认两件事 —— `GET :metricsPort/metrics`(Prometheus 文本)与 `GET endpoint/api/agent/logs?since=`(JSON 数组)。
v2 Agent 只要能更好地服务这两个契约,方法级 / SQL / JVM 监控就全部免费接入,平台 collect/consumer/analysis/web/alerter **零改动**(白皮书 10.2 已承诺)。

---

## 1. 现状扫描(结论)

### 1.1 平台侧契约(必须保持,是向前兼容的锚点)

| 契约 | 现状实现 | v2 必须保持 |
|---|---|---|
| `GET :metricsPort/metrics` | `AgentMetricsCollector` + `OnlinePrometheusParser` 流式解析,`target_info` 被显式跳过(`AgentMetricsCollector.java:121`) | 文本格式;新增指标任意,不会破坏解析 |
| `GET endpoint/api/agent/logs?since=<ISO_INSTANT>` | `AgentLogCollector.buildUrl()` 用 `since.toString()`(ISO-8601);响应为 JSON 数组,字段与 `LogEvent` 对齐 | 字段集合兼容:`appid/level/logger/threadName/message/throwable/traceId/timestamp`(可选 `host/service/method/env`) |
| 日志游标 | 平台侧取 `max(event.timestamp)` 作为下次 `since` | 事件 timestamp 必须可作游标(见 3.1.3) |

### 1.2 v1.2 过渡方案的已知缺陷(Agent 要解决的)

| 缺陷 | 现状 | Agent 方案 |
|---|---|---|
| 日志端点靠客户内嵌补丁 | `InMemoryLogBufferAppender`(ConcurrentLinkedDeque,maxSize=1000,**溢出静默丢**)+ `AgentLogController` + logback 配置 | Agent hook logback,自带 ring buffer,有丢弃计数 |
| 方法级靠 OTel | `@WithSpan` + `code-function-metrics`,产 `code.*` 指标;注解 jar 依赖 <50KB | 字节码按描述符匹配,0 依赖 |
| SQL 监控 | **完全缺失**(README TODO 一行) | 本文 3.3 方案 |

### 1.3 验证标的

- `mock-test/`:Spring Boot **3.4.1** + Java **21**,`spring-boot-starter-jdbc` + H2,DAO 全用 `JdbcTemplate`(`OrderDao/UserDao/ProductDao`)—— **SQL 监控的最小验证标的**
- 主平台:Spring Boot **4.0.1** + Java **25**
- ⇒ Agent 字节码目标版本策略:class 版本按目标应用编译版本自适应(ByteBuddy `ClassFileVersion` 自动处理),Agent 自身用 Java 17+ 编译以覆盖 SB3(17+)与 SB4(25+)

---

## 2. 范围与阶段

### 2.1 MVP 边界

| 阶段 | 内容 | 工作量估 |
|---|---|---|
| **P0(最小可跑 Agent)** | premain + ByteBuddy AgentBuilder + 内置 HttpServer + `/metrics`(JVM + 方法级)+ `/api/agent/logs?since=`(logback hook + ring buffer)+ `target_info` 能力声明 | 2 周 |
| **P1** | SQL 监控(JdbcTemplate 织入,预留 java.sql 插槽) | 1 周 |
| **P2(迭代,不做也可)** | 磁盘 mmap 日志兜底、TLS + token 鉴权、log4j2 支持、SQL 慢查询采样、指标抽样降采样、`X-SW-Log-Cursor` 游标升级 | 按需 |

### 2.2 明确不在范围(遵守白皮书 0.8 反模式)

- ❌ 不 push(OTLP/gRPC/WebSocket)
- ❌ 不跑在 spring-watch 进程内(独立 jar,`-javaagent` 挂载)
- ❌ 不做多实例 / 集群 / 主从
- ❌ 不监控非 Spring Boot 应用
- ❌ 不要求客户手写埋点 / 引入任何 SDK

---

## 3. 模块设计

### 3.1 暴露日志拉取(重点之一)

#### 3.1.1 Hook 点:编程式注册 logback Appender

Spring Boot 默认 logback。Agent premain 阶段:

```
Instrumentation.appendToSystemClassLoaderSearch(agentJar)
  → 用 System ClassLoader 反射加载 ch.qos.logback.classic.LoggerContext
  → 在 Root Logger 上编程式 addAppender(LogCollectorAppender 实例)
  → appender.setContext + start()
```

- 关键点:Agent 的 Appender 类通过 `appendToSystemClassLoaderSearch` 追加到系统类加载器,与应用的 logback 处于同一加载域,**不需要编译期依赖 logback,不需要改客户的 logback-spring.xml**(对比 v1 客户要手动配 `<appender name="INMEM">`)。
- logback 缺失时(log4j2)静默降级,打 warning 日志,日志能力不可用但方法级/SQL 照常 —— 能力通过 `target_info` 声明。

#### 3.1.2 缓冲:有界环形缓冲,丢旧不丢新

- 数据结构:MVP 用 `ConcurrentLinkedDeque` + 容量上限(与 v1 相同思路但**加上丢弃计数**);P2 升级为固定数组环形缓冲(生产者 logback 线程多消费者 HttpServer 线程)。
- 容量默认 `50_000` 条,可 `-DSPRING_WATCH_LOG_BUFFER_SIZE` 调整。
- **与 v1 的本质差异**:丢弃不再静默 —— `sw_log_dropped_total`(level 维度)进 `/metrics`,平台可对其配告警(复用它现有的 log 告警能力)。

#### 3.1.3 协议(与平台 `AgentLogCollector` 严格兼容)

```http
GET /api/agent/logs?since=2026-08-13T03:00:00Z&limit=2000&levels=WARN,ERROR
```

响应(JSON 数组,字段与平台 `LogEvent.java` 对齐):

```json
[
  {
    "appid": 1234567890,
    "level": "WARN",
    "logger": "com.demo.OrderService",
    "threadName": "http-nio-8080-exec-1",
    "message": "order timeout, id=42",
    "throwable": null,
    "traceId": "abc...",
    "timestamp": "2026-08-13T03:00:01.123Z",
    "host": "10.0.0.5",
    "service": "order-app"
  }
]
```

| 参数 | 说明 | 兼容性 |
|---|---|---|
| `since` | ISO-8601(平台现状 `Instant.toString()`);**额外兼容纯数字 seq**(见下) | 平台现网参数原样可用 |
| `limit` / `levels` | 可选,平台没传则默认全量 | 新增参数,旧平台不传 = 无影响 |

**游标设计(向前兼容的关键细节)**:
- 平台侧用 `max(timestamp)` 当游标,但业务日志时间戳可能回拨(时钟漂移)。Agent 内部用**单调递增 seq**(ring buffer 写入位点)保证不重不漏。
- v1 兼容:响应事件仍带 ISO `timestamp`,平台旧逻辑 `max(timestamp)` 继续可用;P2 在响应头加 `X-SW-Log-Cursor`(平台新版本优先用头部游标,旧版本忽略未知头)。

#### 3.1.4 降级路径

- ring buffer 满 → 丢最旧 + `sw_log_dropped_total++`(可告警,不再静默)
- 拦截器自身异常 → 不抛给业务线程,仅计数
- 磁盘 mmap 兜底(P2):`-DSPRING_WATCH_LOG_MMAP_FILE=/var/log/sw-agent.log.mmap`,缓冲满后溢出到 mmap,进程崩溃可恢复部分日志

### 3.2 方法级监控

#### 3.2.1 织入方式(ByteBuddy,按描述符匹配)

```
AgentBuilder.type(isAnnotatedWith(named("io.opentelemetry.instrumentation.annotations.WithSpan"))
              .or(isAnnotatedWith(named("com.springwatch.annotation.SwMon"))))
```

- **不 import 注解类**,只按 `TypeDescription` 的描述符字符串匹配 ⇒ **客户现有 `@WithSpan` 业务代码零改动即被 v2 识别**(白皮书 10.2 兼容要求),同时支持自研 `@SwMon` 逐步迁移,0 annotation jar。
- 构造器 / 静态方法 / 异步方法 Mvp 不织入(避免增强黑魔法,后续按需)。
- 织入逻辑是 `Advice`(onMethodEnter / onMethodExit),方法体与栈帧零感知,异常在 advice 内自吞。

#### 3.2.2 指标(命名空间 `sw_method_*`)

| 指标 | 类型 | 标签 |
|---|---|---|
| `sw_method_calls_total` | Counter | `method=类全名#方法名`, `result=success\|error` |
| `sw_method_duration_seconds` | Histogram(桶 1ms~10s) | 同左 |
| `sw_method_errors_total` | Counter | `method`, `error_type=exception类名` |

- 指标名用 `sw_` 前缀而非 OTel `code.*`:**新命名空间不与 v1 遗留数据混淆,平台查询 API 按前缀区分版本**;`target_info` 里声明 `sw_metric_namespace="v2"` 便于平台未来识别。

#### 3.2.3 防基数爆炸

- 方法级标签基数 = 被注解方法数,客户可控(注解即声明),风险低;仍提供 `-DSPRING_WATCH_METHOD_CARDINALITY_LIMIT`(默认 5000,LRU 淘汰超限方法,弃用计数 `sw_method_evicted_total`)。

### 3.3 SQL 监控

#### 3.3.1 两条路线对比(决策)

| 路线 | 覆盖范围 | 复杂度 | 类加载器 |
|---|---|---|---|
| **A. java.sql 层 JDBC 拦截**(Statement/PreparedStatement execute 系列,OTel 同款) | 一切走 JDBC 的(JDBC/JPA/Hibernate/MyBatis) | 高:需 `appendToBootstrapClassLoaderSearch` + retransform java.sql 接口 | bootstrap |
| **B. Spring JDBC 层织入**(`org.springframework.jdbc.core.JdbcTemplate` 的 query/update/execute 系列) | Spring Boot 场景 90% 命中(mock-test 全中);原生 JDBC / 其他 ORM 不覆盖 | 低:系统类加载器,无 bootstrap 坑 | system |

**决策**:P1 上 **B**(与 mock-test 验证标的完全匹配,最小实现);SQL 指标模型与 digest 口径**按路线 A 设计**,P2 以同一模型补齐 java.sql 层(两者产同一组 `sw_sql_*` 指标,平台无感知升级)。

#### 3.3.2 SQL 摘要(digest)口径

- 字面量归一化:数字 → `?`,字符串 → `?`;只保留操作类型 + 表名 + 骨架(如 `SELECT orders WHERE id=?`),**默认不存全量 SQL**(防高基数 + 防敏感信息泄漏)。
- 上限 `-DSPRING_WATCH_SQL_DIGEST_LIMIT`(默认 2000,LRU,超限 `sw_sql_digest_evicted_total`)。

#### 3.3.3 指标(命名空间 `sw_sql_*`)

| 指标 | 类型 | 标签 |
|---|---|---|
| `sw_sql_calls_total` | Counter | `sql_digest`, `result=success\|error` |
| `sw_sql_duration_seconds` | Histogram(桶 0.1ms~10s) | `sql_digest` |
| `sw_sql_errors_total` | Counter | `sql_digest`, `error_type` |
| `sw_sql_slow_total` | Counter | `sql_digest`(阈值 `-DSPRING_WATCH_SQL_SLOW_MS=500`) |

> 慢 SQL 与错误率直接可接平台现有告警引擎(JEXL 规则引擎已支持任意指标名,无需平台改动)。

### 3.4 JVM / 系统指标(顺手,成本≈0)

- 用 JDK 内置 `ManagementFactory`(MemoryMXBean / GarbageCollectorMXBean / ThreadMXBean / OperatingSystemMXBean)产 `sw_jvm_heap_bytes`、`sw_jvm_gc_*`、`sw_jvm_thread_*` 等,**不引入 Micrometer/oshi**,与白皮书"轻量"定位一致。
- 与 v1 的差异:OTel 也产 jvm 指标,但 v2 用 `sw_jvm_*` 前缀,避免与 OTel 的 `jvm.*` 命名重复歧义。

### 3.5 内置 HTTP 服务器

- **`com.sun.net.httpserver.HttpServer`**(JDK 内置,零依赖),端口默认 9464(`-DSPRING_WATCH_METRICS_PORT`,与 v1 `metricsPort` 概念一致),有界线程池(默认 4)。
- 路由:

| 路径 | 说明 | 平台使用 |
|---|---|---|
| `GET /metrics` | Prometheus 文本(OpenMetrics 子集:Counter/Histogram/Gauge) | `AgentMetricsCollector`(现有) |
| `GET /api/agent/logs?since=` | JSON 数组(3.1.3) | `AgentLogCollector`(现有) |
| `GET /api/agent/capabilities` | JSON 能力声明(version/capabilities/digest 口径/缓冲大小) | 预留,平台未来协商用 |
| `GET /health` | 存活 | 预留 |

- 安全:默认绑定 `0.0.0.0`(平台是远程拉取);`-DSPRING_WATCH_TOKEN` 开启后,`/api/agent/logs` 要求 `Authorization: Bearer <token>`(白皮书 Q0.5 已允许 v1 加鉴权,v2 原生支持);P2 支持 TLS。

---

## 4. 向前兼容策略(重点之二)

### 4.1 平台侧 = 稳定 API,只增强不破坏

| 平台契约 | v2 行为 | 兼容依据 |
|---|---|---|
| `/metrics` Prometheus 文本 | 格式不变,新增 `sw_*` 系列;`target_info` 增加 `sw_agent_version` / `capabilities` 标签 | `OnlinePrometheusParser` 已跳过 `target_info`(代码第 121 行),对未知指标名天然容忍 |
| `/api/agent/logs?since=<ISO>` | 参数与字段完全兼容(3.1.3);`since` 语义保持"返回该时刻之后的事件" | `AgentLogCollector` 现网代码 0 改动 |
| 查询 / 告警 | SQL 慢查询、日志丢弃、方法错误率 = 新指标名,直接进 InfluxDB + JEXL 告警规则 | 平台不做任何特殊分支 |

### 4.2 客户侧 = 平滑迁移(白皮书 10.4 补充实施)

| 迁移项 | v1.2 | v2 | 动作 |
|---|---|---|---|
| 业务注解 | `@WithSpan` | `@WithSpan` 或 `@SwMon` | **保留原注解即可**,描述符匹配兼容 |
| 日志补丁 | `InMemoryLogBufferAppender` + `AgentLogController` + logback 配置 | Agent 内置 | 删除 2 个类 + 1 段配置 |
| `-javaagent` | OTel agent + 3 个 `-D` | `spring-watch-agent.jar` + 2 个 `-D` | 替换 |
| annotation jar | `opentelemetry-instrumentation-annotations` | 无 | 删依赖 |

### 4.3 双轨共存期

- 老客户继续挂 OTel Agent,新客户挂 v2 Agent,**平台无感知**(同一契约)。白皮书 10.4 迁移清单逐项验证后,平台 `OtelConfigGenerator` 输出切到 v2 参数。
- 指标命名差异(`code.*` vs `sw_*`)在迁移期通过平台查询 API 的"指标前缀"兼容层解决(前端指标选择器支持两套前缀)。

### 4.4 失败安全(所有拦截器通用)

- 被拦截方法抛异常 → 业务异常照常抛出,advice 只在 exit 阶段记录 `result=error`;advice 自身异常 → 吞掉并计数 `sw_instrumentation_failures_total`。
- 任何能力开关(`-DSPRING_WATCH_DISABLE=logs,sql,method`)不影响其他能力。

---

## 5. 参考框架(借鉴清单)

| 框架 | 借鉴点 | 本项目用法 |
|---|---|---|
| **OpenTelemetry Java Agent** | byte-buddy 织入模式;`code-function-metrics` 语义(方法 → 计数/耗时);JDBC 拦截(bootstrap + advice);Prometheus exporter 文本输出 | P0 织入骨架、指标语义、P2 JDBC 插槽直接对标其 `java.sql` instrumentation |
| **SkyWalking Java Agent** | 插件化 instrumentation 定义、classloader 兼容策略 | 织入点抽象为 InstrumentDefinition,避免堆在一个类里 |
| **P6Spy / log4jdbc** | JDBC 代理式 SQL 监控的字段口径(digest、慢 SQL 判定) | 3.3 的 digest 口径参考 |
| **Glowroot** | 纯拉取、自包含、嵌入式 UI 的 Java APM —— 与本项目拉取模型一致的先例 | 佐证"Agent 内嵌 HttpServer + 拉取"可行 |
| **ByteBuddy 官方文档** | AgentBuilder、`isAnnotatedWith`、bootstrap 织入、`appendToBootstrapClassLoaderSearch` | P0/P1 直接依据 |
| **HertzBeat** | 平台侧参考(本项目 `OnlinePrometheusParser` 已借鉴其 OnlineParser) | 指标命名与解析兼容思路 |

---

## 6. 工程落地

### 6.1 仓库与产物

- 独立仓库 `spring-watch-agent`(白皮书 Q0 已允许:独立仓库,不动主仓库接入流程)。
- 产物:`spring-watch-agent.jar` —— maven-shade 打入 `byte-buddy` + `byte-buddy-agent` + `asm`(Agent 内部依赖,客户侧 0 依赖),MANIFEST:

```
Premain-Class: com.springwatch.agent.Agent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
```

- 目录:

```
spring-watch-agent/
├── agent-api/          # @SwMon 注解 + 能力常量(仅编译期,不发布运行时依赖)
├── agent-core/         # premain、AgentBuilder 装配、HttpServer、metric 注册表
├── agent-instrument/   # method / sql / logback 三组 InstrumentDefinition
└── agent-shade/        # 打 fat jar
```

### 6.2 测试策略

- 以 `mock-test` 为验收标的:起真实 Spring Boot 应用 + `-javaagent`,验证三能力链路。
- 三类集成测试:① logback hook 后 `GET /api/agent/logs?since=` 增量正确、缓冲满不崩;② `@WithSpan` 方法调用后 `/metrics` 出现 `sw_method_*`;③ JdbcTemplate 查询后 `/metrics` 出现 `sw_sql_*`(digest 归一化正确)。
- 平台侧回归:`AgentMetricsCollector` / `AgentLogCollector` 对 v2 Agent 端点零改动跑通。

### 6.3 风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| Java 25 / SB4 字节码兼容 | 织入失败 | ByteBuddy 最新版(1.15+);class 版本自适应;mock-test(SB3/Java21)与主平台(SB4/Java25)双标的 |
| bootstrap 织入 java.sql(P2) | NoClassDefFoundError | 仅在 P2 做;先 `appendToBootstrapClassLoaderSearch`,再 retransform;P1 走 JdbcTemplate 无此风险 |
| logback 版本差异(1.4/1.5) | Appender API 不兼容 | 反射调用 + 最小 API 面(`append`/`setContext`/`start`),两版本均稳定 |
| SQL 指标基数爆炸 | InfluxDB 序列爆炸 | digest 归一化 + `SQL_DIGEST_LIMIT` LRU + `sw_sql_digest_evicted_total` 告警 |
| 日志缓冲溢出丢日志 | 数据缺失 | 不再静默:`sw_log_dropped_total` 可观测 + P2 mmap 兜底 |
| 内置 HttpServer 成为热点 | 拉取线程池被打满 | 有界线程池(4) + `/metrics` 快照线程模型(写时复制快照,读零锁) |

### 6.4 里程碑

| 里程碑 | 交付 | 验收 |
|---|---|---|
| M1(P0) | 可挂载 Agent:JVM + 方法级指标、日志拉取端点、`target_info` | mock-test 挂载后平台 `AgentMetricsCollector`/`AgentLogCollector` 双链路跑通,平台侧 diff = 0 |
| M2(P1) | SQL 监控(JdbcTemplate) | mock-test 三 DAO 查询产生 `sw_sql_*`,平台告警规则可命中慢 SQL |
| M3(P2) | java.sql 层补全、mmap、鉴权、capabilities 协商 | 原生 JDBC 场景覆盖;老客户平滑迁移清单走完 |

---

## 7. 对白皮书的更新建议(供评审后回写)

1. **日志端点协议落定**:白皮书说"URL/格式 Agent 自定" —— 本规划明确为**沿用 v1 契约**(`/api/agent/logs?since=<ISO>` + LogEvent 字段集),平台 0 改动,仅实现方换成 Agent。
2. **SQL 监控补入白皮书**:作为 v2 第三大能力(方法级 / 日志 / SQL),补充 3.3 的指标口径与 digest 方案。
3. **双轨期指标命名**:白皮书未涉及 `code.*` 与 `sw_*` 共存期的兼容层,建议在 10.4 迁移清单补充"指标前缀兼容层"。
4. **`target_info` 升级**:白皮书未定义 v2 的能力声明机制,建议将 `target_info{sw_agent_version,capabilities}` 写入 v2 约束 4 的允许项。

---

## 附录:与 v1 能力对比

| 能力 | v1.2(OTel + 客户补丁) | v2(自研 Agent) |
|---|---|---|
| 接入步骤 | 4 步(1 参数 + 1 依赖 + 1 注解 + 1 注册) | 1 步(`-javaagent` + 注册) |
| 日志缓冲 | 客户 ringbuffer 1000 条,溢出静默丢 | Agent 50K 条,丢弃可观测,P2 mmap 兜底 |
| 方法级指标 | `code.*`(OTel 命名) | `sw_method_*` |
| SQL 监控 | ❌ 无 | ✅ `sw_sql_*`(P1 JdbcTemplate → P2 java.sql) |
| JVM 指标 | OTel `jvm.*` | `sw_jvm_*`(JDK 内置 API,零依赖) |
| 鉴权 | 客户自担 | 原生 token 鉴权(P0 支持,P2 TLS) |
| 平台侧改动 | 0 | **0**(两契约不变,新增指标天然兼容) |

> 任务完成标志:以 `mock-test` 为标的,M1 跑通时平台主仓库 `collector/consumer/analysis` 无一行改动。
