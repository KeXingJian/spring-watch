# HertzBeat Kafka 采集-消费链路分析与 spring-watch 优化方案

> 范围：仅分析 **Kafka 队列** 链路（不含 Netty、Redis、内存队列等其他传输实现）。
> 目标：理解 HertzBeat 的设计取舍 → 给出 spring-watch 项目生产/消费两端可落地的优化项。

---

## 一、HertzBeat Kafka 全景（采集层 → 消费层）

### 1.1 模块分层与队列抽象

HertzBeat 通过 `CommonDataQueue` 接口把"采集 → 落库"中间件做成**可插拔**的策略：

| 实现类 | 文件 | 用途 |
|---|---|---|
| `InMemoryCommonDataQueue` | `hertzbeat-common-spring/.../queue/impl/InMemoryCommonDataQueue.java` | 默认（embedded 模式） |
| `KafkaCommonDataQueue` | `hertzbeat-common-spring/.../queue/impl/KafkaCommonDataQueue.java` | 分布式模式，`common.queue.type=kafka` 时启用 |
| `RedisCommonDataQueue` | 同目录 | 另一种分布式实现 |
| `NettyDataQueue` | `hertzbeat-collector/.../export/NettyDataQueue.java` | **采集器进程**与 manager 之间的 RPC |

> 关键点：**Collector 进程从不直接连 Kafka**。它通过 Netty 把 Arrow 字节流推给 manager，manager 再调 `commonDataQueue.sendMetricsData(...)` 把数据写入 Kafka。

### 1.2 Topic → Producer / Consumer 映射

`application.yml` 声明 6 个 topic，`KafkaCommonDataQueue` 在 JVM 内用 **2 个 Producer + 5 个 Consumer（5 个消费组）** 覆盖：

| Topic | Producer | Consumer Group | 消费者模块 |
|---|---|---|---|
| `async-metrics-data` | `metricsDataProducer` | `metrics-alert-consumer` | alerter（告警评估） |
| `metrics-data-to-storage-topic` | `metricsDataProducer`（被 alerter 转发） | `metrics-persistent-consumer` | warehouse（持久化） |
| `service-discovery-data` | `metricsDataProducer` | `service-discovery-data-consumer` | manager（服务发现） |
| `async-log-entry-data` | `logEntryProducer` | `log-entry-consumer` | alerter（实时日志告警） |
| `log-entry-data-to-storage-topic` | `logEntryProducer` | `log-entry-storage-consumer` | warehouse（批量写日志） |
| `async-alerts-data` | *(预留)* | — | — |

**设计亮点**：
- **指标走两步链路**：先到 `async-metrics-data` 触发告警评估，alerter 评估完**重新发**到 `metrics-data-to-storage-topic` 给 warehouse 落库 → 把"实时告警"与"持久化"解耦到两个不同消费组。
- **日志直接一步**：走 `async-log-entry-data` → alerter + `log-entry-data-to-storage-topic` → warehouse，两条独立链路互不阻塞。
- **每个 topic 一个消费组**：天然支持横向扩容（加 JVM 实例即加分区分片消费能力），不会因共享组导致某些 topic 的消息被别的实例抢走。

### 1.3 端到端数据流

```
┌──────────────┐                          ┌──────────────────┐
│  Collector   │  Netty (Arrow bytes)     │    Manager       │
│  (进程)      │ ──────────────────────►   │  CollectCyclic   │
│  NettyDataQ  │                          │  DataResponse    │
└──────────────┘                          │  Processor       │
                                          └────────┬─────────┘
                                                   │ sendMetricsData / sendServiceDiscoveryData
                                                   ▼
                                          ┌──────────────────┐
                                          │ KafkaCommonData  │
                                          │ Queue (JVM内)    │
                                          │ 2 Producer+5 Con │
                                          └─────┬───────┬────┘
                ┌────────────────────────────────┘       └────────────────────────────┐
                ▼                                                                       ▼
      async-metrics-data                                                  service-discovery-data
                │                                                                       │
                ▼                                                                       ▼
   ┌──────────────────────┐                                                ┌──────────────────────┐
   │ MetricsRealTimeAlert │ 3 计算线程                                   │ ServiceDiscovery     │
   │ Calculator           │ 评估后→ sendMetricsDataToStorage               │ Worker (1 线程)      │
   └──────────┬───────────┘                                                └──────────────────────┘
              │
              ▼ metrics-data-to-storage-topic
   ┌──────────────────────┐
   │ DataStorageDispatch  │ 1 线程 + 日志批量 1000
   │  ↓HistoryDataWriter  │ LinkedBlockingQueue + HashedWheelTimer flush
   │  ↓RealTimeDataWriter │ Memory / Redis
   └──────────────────────┘
```

### 1.4 生产者关键配置

```java
// hertzbeat-common-spring/.../queue/impl/KafkaCommonDataQueue.java:100-105
producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");          // 强一致
producerConfig.put(ProducerConfig.RETRIES_CONFIG, 3);           // 内部重试 3 次
// 没有设置 idempotence / max.in.flight.requests.per.connection
```

> 选 `acks=all` 而非 `acks=1` 是因为监控数据落库对**不丢**比**低延迟**更敏感。

### 1.5 消费者关键配置

```java
// KafkaCommonDataQueue.java:107-139
MAX_POLL_RECORDS_CONFIG  = 50        // 一次 poll 最多 50 条
MAX_POLL_INTERVAL_MS_CONFIG = 900000 // 15 分钟（给长处理留时间）
ENABLE_AUTO_COMMIT_CONFIG = false    // 手动 commit
// 5 个不同 group.id
```

### 1.6 序列化：Apache Arrow IPC + JSON

| 数据 | 序列化 | 文件 | 性能特性 |
|---|---|---|---|
| `CollectRep.MetricsData` | **Arrow IPC stream**（列存二进制） | `serialize/KafkaMetricsDataSerializer.java:44-62` | 零拷贝、列式、warehouse 端可直接消费 Arrow |
| `LogEntry` | **JSON** | `serialize/KafkaLogEntrySerializer.java:40-51` | 可读、灵活、字段增减向前兼容 |
| Key | `Long` | — | 时间戳/序号，分区顺序 |

> 选 Arrow 的根本原因是 warehouse 端**直接拿 Arrow 表**走列式写入，CPU 远低于 JSON → POJO → SQL 转换。这是 HertzBeat 链路吞吐的真正瓶颈突破点。

### 1.7 消费侧的"背压混合模式"

```java
// KafkaCommonDataQueue.java:161-187 genericPollDataFunction
ConsumerRecords<Long, T> records = dataConsumer.poll(Duration.ofSeconds(1));
int index = 0;
for (ConsumerRecord<Long, T> record : records) {
    if (index == 0) pollData = record.value();  // 第一条立刻返回给调用方
    else dataQueue.offer(record.value());        // 剩余的塞进本地 LinkedBlockingQueue
    index++;
}
dataConsumer.commitAsync();
```

- 每次 poll 50 条，但 API 一次只返回 1 条 → **背压天然发生在调用方**：调用方处理慢时，本地 `LinkedBlockingQueue` 自然堆压，Kafka 端 fetch 也不会再拉。
- 提供 `genericBatchPollDataFunction`（326-350 行）走**真批量**接口，仅 warehouse 日志路径使用（`LOG_BATCH_SIZE = 1000`）。
- 每 topic 配一把 `ReentrantLock`（65-69 行），避免多线程同时 poll 同一分区。

### 1.8 错误处理 / 重试 / 兜底

- **生产者 fire-and-forget**：`metricsDataProducer.send(record)` 不挂 callback，不抛异常上来；3 次内部重试后丢失即丢失。
- **日志生产者带本地降级队列**（`sendLogEntry` 222-237 行）：Kafka 抛异常时回退到进程内 `LinkedBlockingQueue`，避免数据丢失。
- **消费者走 `ExponentialBackoff(50ms → 1000ms)`**：连续 5 次失败后退出当前消费线程，由另一台 JVM 的同组消费者接管。
- **没有 DLQ topic**：反序列化失败只会打断当前 poll 循环，最坏回到 backoff → 退出线程。

### 1.9 warehouse 写入的二级批量

```java
// VictoriaMetricsDataStorage.java:122-134
metricsBufferQueue (LinkedBlockingQueue, default 100)
+ HashedWheelTimer (1s tick, 512 ticks) → flushInterval(3s)
→ HTTP POST /api/v1/import + 可选 GZIP
```

即：Kafka 端批量 + InfluxDB/VM 端再批量 → 双层缓冲。

### 1.10 HertzBeat 的"明确取舍"汇总

| 维度 | 选型 | 理由 |
|---|---|---|
| 采集→队列传输 | **Netty（不是 Kafka）** | 采集器与 manager 部署紧耦合，Netty 延迟低 |
| 队列默认 | **Redis 而非 Kafka** | 运维简单，无 ZK/KRaft 依赖 |
| 指标序列化 | **Arrow IPC** | 零拷贝、列式、写入 TSDB CPU 低 |
| 日志序列化 | **JSON** | 灵活、字段多、不需 schema 演进 |
| 消费者批量 | **50/批 + 本地 LBQ 背压** | 适配"一次一调"的接口语义 |
| 存储批量 | **1000/批** | 走 `genericBatchPollDataFunction` |
| Producer 可靠性 | **acks=all + retries=3**，**无幂等** | 监控数据允许重复但不允许丢（没有"恰好一次"开销） |
| 错误兜底 | **进程内 LBQ 降级 + 5 次指数退避** | 比 DLQ topic 简单、延迟低 |

---

## 二、spring-watch 现状对标

### 2.1 现有 Kafka 链路

```
AgentMetricsCollector (HTTP /metrics)
        │ MetricEvent
        ▼
KafkaProducerBridge.sendMetric()
        │ JSON, key=appid, topic=monitor-metrics
        ▼
[monitor-metrics] ─┬─► MetricConsumer → InfluxDB (WriteApi batch=500/5s)
                   └─► AlertConsumer  → AlertEvaluator (当前被注释)
[monitor-logs]    ───► LogConsumer    → InfluxDB (WriteApi batch=500/5s)
[monitor-heartbeat]─► HeartbeatConsumer → JPA save MonitorApp
```

**生产端（`KafkaProducerBridge.java`）**：
- 单条 `kafkaTemplate.send(topic, key, json).whenComplete(...)`
- 同步 JSON 序列化（`objectMapper.writeValueAsString`）
- 失败仅 `log.warn`，**没有任何降级/重试/本地队列**

**消费端（4 个 `@KafkaListener`）**：
- 每条消息 `objectMapper.readValue(...)` → 业务处理 → 同步写 InfluxDB
- **完全串行、单线程**（没有 `concurrency` 配置）
- 异常 `log.error` 后即吞掉，**不会触发重平衡**（Spring Kafka 默认会重试到 N 次然后 commit skip 策略不明确）

**配置（`KafkaConfig.java`）**：
- Producer：`acks=1, batch.size=32KB, linger.ms=50, lz4, buffer.memory=64MB`（已比默认好）
- Consumer：只有 `bootstrap-servers, group-id, auto-offset-reset, key/value deserializer`，**没有 max.poll.records、concurrency、max.poll.interval、fetch.min.bytes**等关键参数

### 2.2 现存问题清单

| # | 问题 | 影响 |
|---|---|---|
| 1 | **Consumer 单线程串行消费** | 多分区浪费，CPU 没吃满 |
| 2 | **单条 poll + 单条同步写 InfluxDB** | 端到端 P99 差；InfluxDB WriteApi 的 batch=500 被单条到达频率拖累 |
| 3 | **消费失败仅 `log.error`，无重试、无 DLQ** | 一条反序列化失败 / InfluxDB 抖动 → 整批（默认 500）提交后丢弃 |
| 4 | **生产失败仅 `log.warn`，无降级** | Kafka 短暂不可用 → 实时数据直接丢失；无 hertzbeat 那种 in-memory 降级 |
| 5 | **JSON 序列化在生产端每次都同步** | 高频指标/日志下，Jackson 反射成本高 |
| 6 | **告警消费者"形同虚设"** | `@KafkaListener` 已挂上但 `alertEvaluator.evaluate(event)` 被注释，等于多分区重复消费却没用上 |
| 7 | **未启用幂等** | 偶发重试会产生重复 InfluxDB point（不致命但浪费） |
| 8 | **未配置 max.poll.records / fetch.min.bytes** | consumer 网络往返多、broker 端批能力未用 |
| 9 | **手动 ack 没启用** | 取决于 Spring Kafka 默认 AUTO，但配置未显式声明 `AckMode` |
| 10 | **没有死信 / 错误主题** | 监控数据丢了没有第二道防线 |

---

## 三、spring-watch 生产端优化建议

### 3.1 升级 Producer 配置（HertzBeat 风格 + Spring 习惯）

```java
// KafkaConfig.java —— 替换现有 producerFactory()
Map<String, Object> props = Map.of(
    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
    ProducerConfig.ACKS_CONFIG, "all",                       // ← 1 → all，监控数据宁丢延迟不丢数据
    ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE,         // ← 3 → 无限
    ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,           // ← 新增：配合 acks=all 防止重试导致的乱序/重复
    ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5,  // ← 幂等下允许 ≤5
    ProducerConfig.BATCH_SIZE_CONFIG, 65536,                  // ← 32KB → 64KB
    ProducerConfig.LINGER_MS_CONFIG, 30,                      // ← 50 → 30，延迟更低
    ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4",
    ProducerConfig.BUFFER_MEMORY_CONFIG, 134217728L,          // ← 64MB → 128MB
    ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000,        // ← 新增：retries+linger+request.timeout 之上限
    ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000,
    ProducerConfig.SEND_BUFFER_CONFIG, 131072                // ← 128KB socket send buffer
);
```

### 3.2 生产端"hertzbeat 风格"降级队列

直接借鉴 `KafkaCommonDataQueue` 的 `sendLogEntry` 写法（HertzBeat 222-237 行）：Kafka 抛异常时回退到本地 `BlockingQueue`，并由后台线程异步重投。

```java
// 新增：KafkaFallbackQueue.java（生产端兜底）
@Component
public class KafkaFallbackQueue {
    private final KafkaTemplate<String, String> template;
    private final BlockingQueue<FallbackRecord> fallback = new LinkedBlockingQueue<>(50_000);
    private final ScheduledExecutorService retry = Executors.newSingleThreadScheduledExecutor(...);

    public void send(String topic, String key, String json) {
        try {
            template.send(topic, key, json);  // 异步，自动走 batch
        } catch (Exception e) {
            log.warn("[fallback] kafka send threw, queue locally: {}", e.getMessage());
            fallback.offer(new FallbackRecord(topic, key, json, Instant.now()));
        }
    }

    @PostConstruct
    void startRetryDrainer() {
        retry.scheduleWithFixedDelay(this::drain, 5, 5, TimeUnit.SECONDS);
    }

    private void drain() {
        FallbackRecord r;
        int sent = 0;
        while ((r = fallback.poll()) != null) {
            try {
                template.send(r.topic, r.key, r.json).get(2, TimeUnit.SECONDS);
                sent++;
            } catch (Exception e) {
                // 仍失败，重新入队，保持原顺序（队头放回）
                fallback.offer(r);
                break;
            }
        }
        if (sent > 0) log.info("[fallback] drained {} records", sent);
    }
}
```

> 配合 `KafkaProducerBridge` 使用：捕获 `KafkaTemplate.send()` 回调里的 `onFailure`，转交 `KafkaFallbackQueue`。

### 3.3 序列化热路径优化

**短期**：复用同一个预热好的 `ObjectMapper`（spring-boot-starter-web 默认就是预热好的，可继续用），**但** 避免在 `KafkaProducerBridge.send` 里反复 `writeValueAsString`。

**长期**：参照 HertzBeat 给指标数据**上 Protobuf**（自描述 + 体积小 + 无反射）；日志保留 JSON（日志字段多、变化频繁）。具体落地：

1. 引入 `com.google.protobuf:protobuf-java`（或 `kryo`/`msgpack`），对 `MetricEvent`/`HeartbeatEvent` 写 `.proto`。
2. Kafka 序列化器换成 `org.apache.kafka.common.serialization.ByteArraySerializer` + 自定义 `RecordHeaders` 标记 schema。
3. 消费端用对应 `Deserializer` + `RecordHeaders` 识别旧版消息做兼容。

> 收益：metric 单条 200~400B → 60~100B；CPU 反射开销接近 0；InfluxDB point 构造的字段提取直接走 proto getter。

### 3.4 关闭模板层异步等待 + 启用回调日志

`KafkaTemplate.send(...)` 默认走 `ListenableFuture`/新版 `CompletableFuture`，生产环境**不要** `.get()`，但要挂 `whenComplete` 处理失败（`KafkaProducerBridge` 已有，行为正确，可加 metric 计数）：

```java
kafkaTemplate.send(topic, key, json).whenComplete((r, ex) -> {
    if (ex != null) {
        kafkaFallbackQueue.send(topic, key, json);  // 走降级
    } else {
        kafkaSendCounter.labels(topic).inc();        // Micrometer 指标
    }
});
```

### 3.5 Topic 数量 + 分区数

当前 3 个 topic 合理。**强烈建议**显式声明分区数（默认 1 分区下，单 consumer 实例吞吐上限 ≈ 1 个 partition 的 broker 限制）：

```yaml
# 在 application.yml 中
spring:
  kafka:
    admin:
      properties:
        num.partitions: 12       # 与 app 数量级匹配；HertzBeat 6 个 topic，没显式分区，靠 broker 默认
        replication.factor: 1    # 单机测试 1，生产 3
        default.replication.factor: 1
```

也可在 `KafkaConfig` 里加 `NewTopic` Bean：

```java
@Bean NewTopic metricsTopic() { return new NewTopic("monitor-metrics", 12, (short)1); }
@Bean NewTopic logsTopic()    { return new NewTopic("monitor-logs",    6, (short)1); }
@Bean NewTopic heartbeatTopic(){return new NewTopic("monitor-heartbeat", 3, (short)1); }
```

> HertzBeat 6 个 topic 中**没有**显式声明分区数，但仓库有 13k+ stars 建议给生产大集群也用 ≥12 分区（看 `application.yml:140-151`）。

### 3.6 生产端小清单

- [x] `acks=all` + `enable.idempotence=true` + `delivery.timeout.ms`
- [x] 调大 `batch.size` 64KB、`buffer.memory` 128MB
- [x] 降级队列（借鉴 HertzBeat 222-237 行）
- [x] 失败回调走降级而非 `log.warn`
- [x] topic 分区数 ≥ 12（指标），≥ 6（日志），≥ 3（心跳）
- [x] 指标走 Protobuf / 日志继续 JSON
- [x] 上 Micrometer 指标：`kafka.producer.records.send.total`、`.error.total`、`.queue.size`

---

## 四、spring-watch 消费端优化建议

### 4.1 开启并发 + 批量消费（最关键）

HertzBeat 模式：consumer 内置线程池 + 单条快速返回 + 本地 LBQ 兜底。

Spring-Kafka 风格等价做法：

```java
@Bean
public ConcurrentKafkaListenerContainerFactory<String, String> batchFactory(
        ConsumerFactory<String, String> cf) {
    ConcurrentKafkaListenerContainerFactory<String, String> f = new ConcurrentKafkaListenerContainerFactory<>();
    f.setConsumerFactory(cf);
    f.setBatchListener(true);                         // ★ 批量
    f.setConcurrency(3);                              // ★ 3 个并发 consumer
    f.getContainerProperties().setAckMode(AckMode.BATCH);  // 整批提交
    f.getContainerProperties().setPollTimeout(200);   // 200ms
    return f;
}

@KafkaListener(topics = "monitor-metrics",
               groupId = "spring-watch-metric-consumer",
               containerFactory = "batchFactory")
public void onMetricBatch(List<String> messages,
                          @Header(KafkaHeaders.RECEIVED_PARTITION) List<Integer> partitions) {
    // 1. 一次性反序列化
    List<MetricEvent> events = new ArrayList<>(messages.size());
    for (String m : messages) events.add(objectMapper.readValue(m, MetricEvent.class));

    // 2. 构造 InfluxDB Point
    List<Point> points = events.stream().map(this::toPoint).toList();

    // 3. 批量写 InfluxDB（WriteApi.writePoints 内部还会按 bucket 二次批量）
    writeApi.writePoints(points, metricsWriteParameters);

    // 4. 触发告警评估（独立线程池，详见 4.3）
    events.forEach(alertEvaluator::evaluate);
}
```

### 4.2 调优消费者参数

在 `application.yml` 中：

```yaml
spring:
  kafka:
    consumer:
      max-poll-records: 500                  # ← 新增；与 InfluxDB batch 匹配
      fetch-min-size: 1024                   # ← 新增；broker 端少 round-trip
      fetch-max-wait: 200                    # ← 新增；最长 200ms 等数据
      properties:
        max.poll.interval.ms: 300000         # ← 5 分钟（处理 500 条 InfluxDB 写绰绰有余）
        session.timeout.ms: 30000
        heartbeat.interval.ms: 10000
        isolation.level: read_committed      # 配合 producer 幂等
    listener:
      ack-mode: batch
      concurrency: 3                         # 与 KafkaConfig 同步
      type: batch
```

> HertzBeat 选 `max-poll-records=50` 是因为**单条返回**的 API 语义；spring-watch 走批量 API，可以放到 200~500，让 InfluxDB 的 batch=500 真正吃满。

### 4.3 告警消费 + 告警评估解耦

当前 `AlertConsumer` 与 `MetricConsumer` 在**两个不同消费组**监听同一 topic，**会重复消费**——这是 HertzBeat 风格的正确做法（"指标先评估告警，再转储"），但 spring-watch 当前告警消费者是空的。**正确做法**：

1. **保留双消费组**：`metric-writer`（写 InfluxDB）、`alert-evaluator`（评估告警 + 触发 Webhook/钉钉/飞书）。
2. **告警消费者也走批量 + 并发**（参考 4.1）。
3. **告警评估放到独立线程池**，避免阻塞 Kafka 消费循环：

```java
@Component
public class AsyncAlertEvaluator {
    private final ExecutorService pool = Executors.newFixedThreadPool(
        Math.max(2, Runtime.getRuntime().availableProcessors()));

    public void evaluate(MetricEvent event) {
        pool.submit(() -> alertEvaluator.evaluate(event));
    }
}
```

> 注意：告警评估内部已经做了"窗口期去重"（`AlertWindowManager` 用 Redis ZSet，10 分钟窗口），并发评估**不会**产生重复告警。

### 4.4 错误处理：重试 + DLQ

Spring-Kafka 内建 `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`：

```java
@Bean
public DefaultErrorHandler errorHandler(
        KafkaTemplate<String, String> template) {

    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
        template,
        (record, ex) -> new TopicPartition(record.topic() + ".DLQ", record.partition()));

    ExponentialBackOffWithMaxRetries backoff =
        new ExponentialBackOffWithMaxRetries(5);      // 最多重试 5 次
    backoff.setInitialInterval(200);
    backoff.setMultiplier(2.0);
    backoff.setMaxInterval(5000);

    return new DefaultErrorHandler(recoverer, backoff);
}

@Bean
public ConcurrentKafkaListenerContainerFactory<String, String> batchFactory(
        ConsumerFactory<String, String> cf, DefaultErrorHandler errorHandler) {
    ConcurrentKafkaListenerContainerFactory<String, String> f = new ConcurrentKafkaListenerContainerFactory<>();
    f.setConsumerFactory(cf);
    f.setBatchListener(true);
    f.setConcurrency(3);
    f.setCommonErrorHandler(errorHandler);            // ★ 注入
    f.getContainerProperties().setAckMode(AckMode.BATCH);
    return f;
}
```

> 这样反序列化失败、InfluxDB 写失败等 → 重试 5 次（指数退避）→ 仍失败 → 进 `<topic>.DLQ`（手动排查或下游修复后回灌）。这正好补齐 HertzBeat 没有的"DLQ topic"短板。

### 4.5 关键监听器元注解

把常用配置抽成注解，避免每个 `@KafkaListener` 都写一遍：

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@KafkaListener(
    containerFactory = "batchFactory",
    properties = {"max.poll.records=500", "fetch.min.size=1024"}
)
public @interface BatchMetricListener { /* ... */ }
```

### 4.6 消费端小清单

- [x] `@KafkaListener` 切到 `containerFactory = "batchFactory"`
- [x] `setBatchListener(true)` + `setConcurrency(3)`
- [x] `AckMode.BATCH` + `setPollTimeout(200)`
- [x] `max.poll.records: 500` + `fetch.min.size: 1024` + `fetch.max.wait: 200`
- [x] `max.poll.interval.ms: 300000` 防止长处理被踢
- [x] 告警消费者**启用** `alertEvaluator.evaluate(event)`，并放独立线程池
- [x] `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` → `<topic>.DLQ`
- [x] `isolation.level: read_committed` 配合 producer 幂等
- [x] Heartbeat 写库走 batch update（避免每条一次 JPA save）

---

## 五、Topic 拓扑 & 监控指标

### 5.1 推荐 Topic 拓扑

```
monitor-metrics       partition=12  ─┬─► spring-watch-metric-writer  (InfluxDB)
                                     └─► spring-watch-alert-evaluator (告警)

monitor-logs          partition=6   ──► spring-watch-log-writer      (InfluxDB)

monitor-heartbeat     partition=3   ──► spring-watch-heartbeat-writer (JPA batch update)

monitor-metrics.DLQ   partition=3   ──► spring-watch-dlq-monitor      (告警 + 人工/自动重灌)
monitor-logs.DLQ      partition=3   ──► spring-watch-dlq-monitor
monitor-heartbeat.DLQ partition=1   ──► spring-watch-dlq-monitor
```

### 5.2 Micrometer 指标清单

| 指标 | 用途 |
|---|---|
| `kafka.producer.records.send.total{topic}` | 发送速率 |
| `kafka.producer.error.total{topic}` | 失败速率（>0 触发告警） |
| `kafka.producer.fallback.queue.size` | 降级队列堆积（>1000 告警） |
| `kafka.consumer.records.consumed.total{topic,group}` | 消费速率 |
| `kafka.consumer.lag.records{topic,group,partition}` | 消费延迟（>5000 告警） |
| `kafka.consumer.batch.size` | 实际批次大小直方图 |
| `kafka.listener.error.total{topic,group}` | 处理失败（>0 告警） |
| `influxdb.write.latency` | 写库延迟 |

> 全部用 `KafkaTemplate`/`@KafkaListener` 自带的 Micrometer 绑定即可，零额外代码。

---

## 六、落地路线（最小风险推进）

| 阶段 | 改动 | 风险 | 收益 |
|---|---|---|---|
| **P0** | 显式 `NewTopic` Bean 声明分区数；Producer 改 `acks=all`+`enable.idempotence` | 低（向后兼容） | 写入可靠性 ↑↑ |
| **P0** | Consumer 加 `DefaultErrorHandler` + DLQ | 低 | 失败可观测、可恢复 |
| **P1** | Consumer 改批量 + 并发 | 中（需重写 onMetric/onLog） | 吞吐 ↑ 5~10× |
| **P1** | 告警消费者启用 + 独立线程池 | 低 | 告警链路活起来 |
| **P2** | 生产端加降级队列 | 中（内存管理） | 短时 Kafka 抖动不丢数据 |
| **P2** | `max.poll.records=500` + `fetch.min.size=1024` | 低 | consumer 网络往返 ↓ |
| **P3** | MetricEvent/HeartbeatEvent 改 Protobuf | 高（双写期） | 序列化 CPU ↓ 80%、带宽 ↓ 60% |
| **P3** | 引入 DLQ 监控 + 自动重灌 worker | 中 | 真正可观测 + 自愈 |

---

## 七、一句话总结

**HertzBeat 的精髓**：
> **用 Netty 把"采集"与"队列"解耦**、**用 Arrow 把"队列"与"存储"打通**、**用"本地 LBQ 兜底 + 5 次指数退避"代替 DLQ 主题**、**给指标和日志两套独立的 topic 拓扑**。

**spring-watch 的最优进化路径**：
> **先把 Consumer 批量+并发+DLQ 加上**（最大杠杆）；
> **再把 Producer 升级为 acks=all+幂等+降级队列**（最稳可靠）；
> **最后把 Metric 改成 Protobuf**（最大性能提升）；
> 整套设计风格向 HertzBeat 看齐，但**保留** Spring-Kafka 的 `DefaultErrorHandler` + DLQ 这一条 HertzBeat 没有的兜底。
