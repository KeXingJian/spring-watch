# spring-watch 内存优化方案

## 2. 分层优化项（按 ROI 排序）

### P0 — 必须做（高 ROI、低风险，1–2 天可全部完成）

#### P0-1 关闭 JPA Open-In-View
- **现状**：`spring.jpa.open-in-view` 未设置，默认 `true`。每次 HTTP 请求都会持有 Hibernate PersistenceContext 直至响应结束，LAZY 关联可能在视图层被触发。
- **影响**：每个请求级 1st-level 缓存泄漏；LAZY 触发导致 N+1 风险。
- **改动**：`src/main/resources/application.yml`
  ```yaml
  spring:
    jpa:
      open-in-view: false
  ```
- **联动审计**：必须配套审视 `web/*Controller` 中所有返回实体的方法，确保不直接序列化 `AlertHistory` / `MonitorApp` 等含 LAZY 关联的实体（见 P0-4）。
- **验收**：所有 `web/*` 单元测试 / `mock-test` 通过；启动日志不再出现 "OpenEntityManagerInViewInterceptor"。

#### P0-2 Agent HTTP 响应体大小封顶
- **现状**：`AgentHttpClient` 用 `BodyHandlers.ofString()` 读取整段响应到 `String`，无 `Content-Length` 校验。
- **影响**：单个异常 Agent 可撑爆堆（多次 M 数 + 文本编码拷贝）。
- **改动**：`collector/AgentHttpClient.java:82`
  ```java
  HttpResponse<byte[]> resp = httpClient.send(req, BodyHandlers.ofByteArray());
  if (resp.body().length > MAX_BODY_BYTES) { // 4 * 1024 * 1024
      rejectedBodyCounter.increment();
      throw new BodyTooLargeException(...);
  }
  String body = new String(resp.body(), StandardCharsets.UTF_8);
  ```
  并新增自监控指标 `agent.http.body.rejected.total{appid}`。
- **验收**：mock 一个返回 10 MB 的 Agent，验证被拒收并打点；正常响应路径不变。

#### P0-3 `AsyncAlertExecutor` 改用虚拟线程按需执行器
- **现状**：`Executors.newFixedThreadPool(8, vtFactory)` —— 包裹一个**无界 `LinkedBlockingQueue`**。突发时 ~992 个 `MetricEvent` 在队列中等待。
- **影响**：突发期队列中持续持有 `MetricEvent`（含 Prometheus 标签 Map）。
- **改动**：`alerter/AsyncAlertExecutor.java:33`
  ```java
  this.executor = Executors.newVirtualThreadPerTaskExecutor();
  this.semaphore = new Semaphore(poolSize); // poolSize=8
  public void submit(MetricEvent e) {
      semaphore.acquire();
      try { executor.submit(() -> { try { run(e); } finally { semaphore.release(); } }); }
      catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
  }
  ```
  关闭钩子中 `executor.close()`。
- **验收**：批量 1000 events 注入不再出现队列堆积；JFR 中看不到 `LinkedBlockingQueue` 上的锁竞争。



### P1 — 强烈建议（中等 ROI，3–5 天）

#### P1-1 `KafkaFallbackQueue` 容量收紧 + 落盘溢出
- **现状**：50 000 条 × ~4 KB payload ≈ 200 MB 堆。
- **改动**：
  1. 默认容量 50 000 → 10 000（配置项）。
  2. 队列满时**拒绝新记录**并打印 `WARN` + 自监控计数 `kafka.fallback.rejected.total`（保留可观测性，简化路径）。
  3. 备选（评估后选择）：将溢出数据顺序写入 `tmp/fallback/{topic}-{ts}.log`，重启后回灌。**实现复杂度高，第一期可不做**。
  4. 截断 payload > 16 KB。
- **验收**：模拟 Kafka 不可用，平台持续写入 30 分钟，堆峰值 < 25 MB；`kafka.fallback.queue.size` 指标可观察。

#### P1-2 Agent 日志流式解析
- **现状**：`AgentLogCollector.java:45` `objectMapper.readValue(body, new TypeReference<List<LogEvent>>(){})` 把整段日志拉成 `List<LogEvent>`。
- **改动**：用 `SequenceReader` 逐条推送到 Kafka。
  ```java
  try (JsonParser p = objectMapper.getFactory().createParser(body)) {
      p.nextToken(); // START_ARRAY
      SequenceReader<LogEvent> r = objectMapper.readValues(p, LogEvent.class);
      while (r.hasNext()) kafkaProducerBridge.sendLog(r.next());
  }
  ```
- **验收**：mock 返回 10 000 行的日志响应，过程中 `jvm.memory.used{area="heap"}` 增长 < 5 MB（无 List 化）。

#### P1-3 JEXL `MapContext` 复用
- **现状**：`JexlExprEvaluator.java:27` `new MapContext()` 每次评估分配。
- **改动**：
  ```java
  private static final ThreadLocal<MapContext> CTX = ThreadLocal.withInitial(MapContext::new);
  public Object evaluate(String expr, Map<String,Object> vars) {
      MapContext ctx = CTX.get();
      ctx.clear();
      vars.forEach(ctx::set);
      return jexl.createExpression(expr).evaluate(ctx);
  }
  ```
- **验收**：压测 1 kHz 告警评估，JFR 中 `MapContext` 分配速率从 ~1k/s 降到接近 0。

#### P1-4 `LogFingerprinter.sha1Hex` 改 `ThreadLocal<MessageDigest>`
- **现状**：`LogFingerprinter.java:83` 每次 `MessageDigest.getInstance("SHA-1")`。
- **改动**：
  ```java
  private static final ThreadLocal<MessageDigest> SHA1 =
      ThreadLocal.withInitial(() -> {
          try { return MessageDigest.getInstance("SHA-1"); }
          catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
      });
  public String sha1Hex(String s) {
      MessageDigest d = SHA1.get();
      d.reset();
      byte[] hash = d.digest(s.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
  }
  ```
- **验收**：1 kHz 日志摄入，JFR 中 `MessageDigest` 实例分配速率明显下降。

#### P1-5 `HostThrottler` 释放
- **现状**：`collector/schedule/HostThrottler.java:17` `ConcurrentHashMap<String, Semaphore> hostSemaphores` 永不清理。
- **改动**：
  1. `HostThrottler` 新增 `release(String host)`。
  2. `service/MonitorAppService.delete(...)` 中调用 `hostThrottler.release(app.getHost())`。
  3. 可选：定期（每小时）扫描 `hostSemaphores`，清除所有 `availablePermits() == maxPermits` 且 1h 内未 acquire 的条目。
- **验收**：删除 50 个应用后再插入同名应用，map 不会出现重复 entry。

#### P1-6 `SelfMonitorCollector.captureMeters` 白名单 + 缩小 RING_SIZE
- **现状**：`monitor/SelfMonitorCollector.java:169-186` 复制**所有** meters 到 ring（360 条），~30 MB。
- **改动**：
  1. 引入前缀白名单：`http.server.requests`, `kafka.consumer.*`, `kafka.producer.*`, `jvm.*`, `process.*`, `system.cpu.*`, 自定义 `agent.*`, `kafka.fallback.*`。
  2. `RING_SIZE` 360 → 60。
  3. `captureMeters` 内部用 `ConcurrentHashMap` 增量更新，避免每次全量遍历。
- **验收**：ring 内存峰值 < 5 MB；`/api/self/timeseries?window=60` 响应 < 500 KB。

#### P1-7 `@Scheduled` 调度线程池
- **现状**：`spring.task.scheduling.pool.size` 未设，默认 1。6 个 `@Scheduled` 任务串行执行。
- **改动**：`application.yml`
  ```yaml
  spring:
    task:
      scheduling:
        pool:
          size: 4
  ```
- **验收**：JFR 中调度线程名带 `scheduling-`。

#### P1-8 InfluxDB 缓冲收紧
- **现状**：`InfluxDBConfig.java` `buffer-limit: 100_000` ≈ 20 MB。
- **改动**：
  ```yaml
  influxdb2:
    batch-size: 1000
    flush-interval: 2000      # 1s -> 2s
    buffer-limit: 20000       # 100k -> 20k
  ```
- **验收**：稳态 InfluxDB 写入吞吐不变；堆中 InfluxDB 缓冲 < 5 MB。

#### P1-9 Kafka producer buffer 调小
- **现状**：`KafkaConfig.java:68` `buffer-memory: 134217728` = 128 MB。
- **改动**：
  ```yaml
  spring:
    kafka:
      producer:
        properties:
          buffer.memory: 33554432   # 32 MB
          compression.type: lz4
  ```
  配合 P1-1（降级队列兜底）。
- **验收**：Kafka 短时不可用（10s）期间堆增加 < 40 MB。

#### P1-10 SMTP 独立线程池
- **现状**：`MailConfig` 使用 `JavaMailSenderImpl`（无连接池），`AsyncAlertExecutor` 内的邮件发送被 SMTP 慢响应阻塞。
- **改动**：
  1. 提取 `alerter/AsyncMailExecutor`（`Executors.newVirtualThreadPerTaskExecutor()` + `Semaphore(4)`）。
  2. `AlertNotifier` 改注入 `AsyncMailExecutor` 而非直接 `mailSender.send`。
  3. `mail.smtp.timeout` 5000 → 2000。
- **验收**：SMTP 故障时告警评估不被阻塞；`AsyncAlertExecutor` 工作线程不卡在 SMTP。

---

### P2 — 锦上添花（低 ROI / 长期，1 周+）

#### P2-1 Caffeine 替换 `AlertRuleCache`
- **现状**：`AlertRuleCache.java:28` `AtomicReference<Map<Long, List<AlertRule>>>`。
- **改动**：引入 `Caffeine`（已在 Spring Boot 默认依赖中或轻量引入）。
  ```java
  private final Cache<Long, List<AlertRule>> cache = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofSeconds(30))
      .maximumSize(10_000)
      .build();
  ```
- **验收**：规则刷新更平滑；`cache.stats()` 暴露命中率到自监控。

#### P2-2 Kafka 消费者批大小
- **现状**：`max-poll-records: 500` × `concurrency: 3` = 单批 500 × 3 = 1500 records in-flight。
- **改动**：`max.poll.records: 200`，相应调小 `concurrency` 或加 `max.partition.fetch.bytes: 5242880`。
- **验收**：单批峰值下降 60%。

#### P2-3 日志级别收口
- **现状**：`application.yml:161` `com.springwatch: debug`。
- **改动**：
  ```yaml
  logging:
    level:
      com.springwatch: info
      com.springwatch.alerter.AlertEngine: info
      com.springwatch.ingest.LogFingerprinter: warn
  ```
  并对 `log.trace/log.debug` 路径加 `isDebugEnabled()` 守卫。
- **验收**：INFO 级别下 JVM CPU 下降 5%–10%。

#### P2-4 `BatchLogConsumer` / `BatchMetricConsumer` 对象复用
- **现状**：每条记录 `new LogEvent()` / `new Point()`。
- **改动**：仅在 `AlertHistory` / `MetricEvent` 等需要保留的对象上谨慎评估；JIT 已经对短命对象做了标量替换与 EAC，**实际收益有限**。
- **验收**：JFR GC alloc 速率无明显改善时可放弃。

#### P2-5 GC 与 JVM 参数
- **G1**（吞吐优先）：`-Xms512m -Xmx1g -XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:+UseStringDeduplication`
- **ZGC**（超低延迟，Java 25 推荐）：`-XX:+UseZGC -XX:+ZGenerational -XX:MaxGCPauseMillis=10`
- **元空间**：`--XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=256m`
- **JFR 持续采样**：生产环境开启 `-XX:StartFlightRecording=...` 便于长期分析。
- **验收**：p99 GC 暂停 < 50 ms (G1) / < 10 ms (ZGC)。

---

## 3. 落地分期

| 阶段 | 内容 | 验收指标 | 周期 |
|---|---|---|---|
| **M1（止血）** | P0 全部 + P1-1, P1-2, P1-5, P1-7 | 堆峰值 < 200 MB、24h 跑批无 OOM、`alert_history` 不再无界增长 | 3 天 |
| **M2（瘦身）** | P1-3, P1-4, P1-6, P1-8, P1-9, P1-10 | 堆稳态 < 150 MB、GC 暂停 p99 < 50 ms、自监控 ring < 5 MB | 5 天 |
| **M3（精细化）** | P2 全部 + GC 调优 + 压测 + 监控埋点 | 接入 100 应用 7×24h 压测，堆稳态 < 120 MB、p99 < 10 ms (ZGC) | 7 天 |

---

## 4. 验证方法

### 4.1 静态分析（每 PR）
- `mvn -DskipTests package` 通过。
- `grep -rE 'findAll\(\)' src/main` 只能出现在 `Pageable` 已封装的方法内。
- 搜索 `BodyHandlers.ofString` 仅出现在已封顶的调用点。

### 4.2 单元/集成测试
- 沿用 `mock-test/` 现有测试，确保不回归。
- 新增：
  - `AgentHttpClientTest`：mock 一个返回 8 MB 响应的 Agent，断言抛 `BodyTooLargeException`。
  - `AlertHistoryRetentionTest`：插入 100 天前数据，调 `purge()` 断言被清。
  - `HostThrottlerReleaseTest`：插入/删除若干 host，断言 map size。

### 4.3 压测（手工/脚本）
- `mock-test/` 增强为 N=100 应用 × 15s 拉取 × 1000 logs/s。
- 持续 1 h 观察：
  - `jvm.memory.used{area="heap"}` 稳态 / 峰值
  - `jvm.gc.pause` p50 / p99
  - `kafka.fallback.queue.size`（自监控埋点）
  - `agent.http.body.rejected.total`（新增）
  - `alert.history.total_rows`（新增）

### 4.4 持续 soak
- CI 中跑 1 h soak：堆增长 < 5 MB/h 即视为无泄漏。

---

## 5. 风险与回滚

| 风险 | 影响面 | 缓解 / 回滚 |
|---|---|---|
| 关闭 OSIV 后 `web/*` 中 LAZY 访问报 `LazyInitializationException` | 中 | 配套 P0-4 引入 DTO；保留 `EntityGraph` 或 `@Transactional` 包装 |
| HTTP 响应体限流误杀 | 低 | 限值走配置（默认 4 MB），可按 app 覆盖；自监控计数便于排查 |
| `AsyncAlertExecutor` 改无界虚拟线程造成 CPU 100% | 中 | `Semaphore(8)` 仍限制并发；通过 JFR 观察 |
| Kafka 降级队列容量下降导致丢消息 | 中 | 配合自监控 `kafka.fallback.rejected.total` + 告警；后续可补落盘 |
| 告警历史 90 天保留期与运营策略冲突 | 低 | 配置项 `spring-watch.alert.history.retention-days` 可调 |
| `captureMeters` 白名单漏掉新指标 | 低 | 启动时打印被过滤的指标名；提供 `/api/self/meters` 列表便于补白 |

回滚策略：所有 P0/P1 改动均为**独立 commit**，可按 commit 逐项回滚。配置项改动通过 Git revert 即可，不涉及 schema 变更。

---

## 6. 监控埋点清单（新增）

```
agent.http.body.rejected.total{appid}        # P0-2
alert.history.total_rows                     # P0-5
alert.history.purged.total                   # P0-5
kafka.fallback.queue.size                    # P1-1
kafka.fallback.rejected.total                # P1-1
self.monitor.ring.size                       # P1-6
self.monitor.capture.filtered.total          # P1-6
jexl.context.reused.total                    # P1-3
log.fingerprint.digest.reused.total          # P1-4
host.throttler.entries                       # P1-5
```

通过 `monitor/SelfMonitorCollector` 统一采集到 `/api/self/timeseries` 和 `/actuator/metrics`。

---

## 7. 变更点速查表

| ID | 文件 | 行 | 类别 | 摘要 |
|---|---|---|---|---|
| P0-1 | `application.yml` | — | 配置 | `spring.jpa.open-in-view: false` |
| P0-2 | `AgentHttpClient.java` | 82 | 代码 | `ofString` → `ofByteArray` + 4 MB 限流 |
| P0-3 | `AsyncAlertExecutor.java` | 33 | 代码 | 改 `newVirtualThreadPerTaskExecutor` + `Semaphore` |
| P0-4 | `AlertController.java`, `AlertRuleService.java`, `MonitorAppService.java`, `NotificationConfigService.java`, `AlertHistoryRepository.java`, 新 DTO | 多 | 代码 | `findAll` → `Pageable` + DTO |
| P0-5 | `AlertHistoryRepository.java`（新增方法）, `AlertHistoryRetention.java`（新建） | 多 | 代码 | 90 天清理 + `@Scheduled` |
| P1-1 | `KafkaFallbackQueue.java` | 50 | 配置+代码 | 容量 10 000 + payload 16 KB 截断 + 拒绝计数 |
| P1-2 | `AgentLogCollector.java` | 45 | 代码 | `readValue(List)` → `readValues(SequenceReader)` |
| P1-3 | `JexlExprEvaluator.java` | 27 | 代码 | `ThreadLocal<MapContext>` |
| P1-4 | `LogFingerprinter.java` | 83 | 代码 | `ThreadLocal<MessageDigest>` |
| P1-5 | `HostThrottler.java`, `MonitorAppService.java` | 17, delete | 代码 | `release(host)` + 删除时调用 |
| P1-6 | `SelfMonitorCollector.java` | 51, 169 | 代码 | 白名单 + RING 60 + 增量更新 |
| P1-7 | `application.yml` | — | 配置 | `spring.task.scheduling.pool.size: 4` |
| P1-8 | `application.yml` | — | 配置 | `influxdb2.buffer-limit: 20000, flush-interval: 2000` |
| P1-9 | `application.yml` | — | 配置 | `spring.kafka.producer.properties.buffer.memory: 32 MB` |
| P1-10 | `MailConfig.java`, `AsyncMailExecutor.java`(新建), `AlertNotifier.java` | 多 | 代码 | 独立 SMTP 线程池 |
| P2-1 | `AlertRuleCache.java` | 28 | 代码 | Caffeine `expireAfterWrite(30s).maximumSize(10_000)` |
| P2-2 | `application.yml` | — | 配置 | `max.poll.records: 200` |
| P2-3 | `application.yml` | 161 | 配置 | `com.springwatch: info` + `isDebugEnabled` 守卫 |
| P2-4 | `BatchLogConsumer.java`, `BatchMetricConsumer.java` | 多 | 代码 | 评估对象复用（先评估后做） |
| P2-5 | 部署侧 | — | 配置 | GC 参数 + JFR |


## 10. 部署侧 JVM 参数（已实施参考）

### 推荐 G1（吞吐优先 · 起步选择）

```bash
java \
  -Xms512m -Xmx1g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=50 \
  -XX:+UseStringDeduplication \
  -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=256m \
  -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./.log/heapdump.hprof \
  -XX:+ExitOnOutOfMemoryError \
  -jar target/spring-watch-1.0.0.jar
```

### 推荐 ZGC（Java 25 · 超低延迟）

```bash
java \
  -Xms512m -Xmx1g \
  -XX:+UseZGC -XX:+ZGenerational \
  -XX:MaxGCPauseMillis=10 \
  -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=256m \
  -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./.log/heapdump.hprof \
  -jar target/spring-watch-1.0.0.jar
```

### 持续 JFR 采样（生产环境）

```bash
java \
  -XX:StartFlightRecording=disk=true,filename=./.log/spring-watch.jfr,maxsize=500M,duration=24h \
  -XX:FlightRecorderOptions=stackdepth=128 \
  ...
```

JFR 文件可用 JDK Mission Control 或 `jfr summary` 分析：

```bash
jfr summary ./.log/spring-watch.jfr
jfr print --events jdk.GCPhasePause,jdk.CPULoad,jdk.ObjectAllocationInNewTLAB ./.log/spring-watch.jfr
```

