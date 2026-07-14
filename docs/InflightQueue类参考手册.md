# InflightQueue 类参考手册(v2.1 方案 C 修订)

> **包名**:`com.springwatch.inflight`(共 13 个类)
> **版本**:spring-watch v2.1
> **上游文档**:`白皮书-InflightQueue.md`(定性,§2 C1 改为"不要求持久化")+ `docs/InflightQueue实现设计.md`(设计)
> **本手册**:**逐类 API + 线程安全 + 调用关系**,面向后续维护者
> **重大变化**(v2.0 → v2.1):**方案 C 纯内存**——删除 WAL / FlushScheduler / JsonCodec / SegmentRoller / Recoverer,共减 ~500 行

---

## 0. 重大变更(v2.0 → v2.1)

| 类别 | 变化 | 原因 |
|---|---|---|
| **白皮书** | C1"一定要做持久化"→"**不要求持久化**(纯内存)" | 方案 A 代价过大,改方案 C |
| **删除** | `FlushScheduler` / `MmapWAL` / `WALSegment` / `JsonCodec` | 无 WAL,无序列化 |
| **删除** | `InflightMetrics.walAppendFail` / `wal.segments` / `drained` | 无 WAL,无 drained 含义 |
| **删除** | `InflightProperties.walDir` / `segmentBytes` / `flushIntervalMs` / `flushBatchSize` / `segmentRetentionDays` | 无 WAL |
| **删除** | `InflightBuffer.peek()` | 无 FlushScheduler 竞争 |
| **删除** | `MmapWALTest` / `FlushSchedulerTest` | 配套测试 |

**总代码量**:**905 行实现 + 85 行单测**(原 1500 + 180)

---

## 1. 13 个类清单(按层)

| 层 | 类 | 行数 | 角色 |
|---|---|---|---|
| **数据层** | `InflightRecord` | 22 | record POJO(测试用) |
| **数据层** | `BackpressureException` | 24 | L1 满时异常 |
| **配置层** | `InflightProperties` | 64 | `@ConfigurationProperties(prefix="spring-watch.inflight")` |
| **存储层** | `InflightBuffer` | 86 | **唯一存储层**:单 partition 内存 ring + Semaphore 容量 |
| **聚合层** | `Partition` | 35 | 单 partition 包装(纯内存,无 WAL) |
| **聚合层** | `InflightQueue` | 108 | 门面:`@PostConstruct` 启动 10 个 partition + 路由 |
| **指标层** | `InflightMetrics` | 122 | 5 个 Micrometer 指标(sent/rejected/drained/pending/capacity/batch.size) |
| **背压层** | `BackpressureHandler` | 44 | DROP / RETRY / RETRY_THEN_DROP 三策略 |
| **业务层** | `InflightProducerBridge` | 84 | 替换 `KafkaProducerBridge` |
| **业务层** | `InflightConsumer` | 124 | 替换 `@KafkaListener`,N 虚拟线程/topic 随机 poll partition |
| **业务层** | `MetricEventWriter` | 47 | `List<Object>` → InfluxDB Point |
| **业务层** | `LogEventWriter` | 55 | `List<Object>` → InfluxDB Point |
| **业务层** | `HeartbeatEventWriter` | 65 | `List<Object>` → PostgreSQL `MonitorApp` 更新 |

---

## 2. 数据层(3 个类)

### 2.1 `InflightRecord`(POJO)

**职责**:record POJO,表示 buffer 中流转的一条事件。**当前未被业务代码使用**(业务直接传 Object),保留供未来测试和扩展用。

```java
public record InflightRecord(
    String topic, int partitionId, String key,
    Object payload, long timestampMs, long offset
)
```

**约束**:`topic` 非空,`partitionId >= 0`,`payload` 非空。
**线程安全**:`record` 不可变,线程安全。

### 2.2 `BackpressureException`

**职责**:L1 满时抛出的运行时异常(v2.1 删了 WAL_APPEND_FAIL 枚举值)。

```java
public class BackpressureException extends RuntimeException {
    String getTopic();
    int getPartitionId();
    Reason getReason();   // 仅 IN_FLIGHT_FULL
}
```

**触发场景**:
- `IN_FLIGHT_FULL`:`InflightBuffer.offer()` 返回 false(buffer 容量满)

**使用方**:`BackpressureHandler.handle(topic, partitionId, payload, ex)` 接收。

---

## 3. 配置层

### 3.1 `InflightProperties`

**职责**:`@ConfigurationProperties(prefix = "spring-watch.inflight")` 配置类。

```java
@Data
@ConfigurationProperties(prefix = "spring-watch.inflight")
public class InflightProperties {
    private boolean enabled = true;
    private Map<String, Integer> partitions = ...;   // per-topic(3+3+1+1+1+1)
    private int bufferCapacity = 50000;
    private Routing routing = new Routing();
    private Consumer consumer = new Consumer();
    // ★ 不再有 walDir / segmentBytes / flushIntervalMs / flushBatchSize / segmentRetentionDays
}
```

**嵌套类**:
- `Routing.Strategy` 枚举:`POWER_OF_TWO_CHOICES`(默认) / `WEIGHTED_ROUND_ROBIN` / `ROUND_ROBIN`
- `Routing.Rebalance`:enabled / intervalSeconds / pendingThresholdRatio / underloadedThresholdRatio / migrateTopN
- `Consumer`:pollMaxBatch=200 / pollWaitMs=100 / concurrency: Map<topic, Integer>

**注册方式**:`InflightQueue` 上的 `@EnableConfigurationProperties(InflightProperties.class)`
**配置文件**:`src/main/resources/application-inflight.yml`

**v2.0 → v2.1 删除字段**:
- `walDir`(WAL 目录)
- `segmentBytes`(WAL segment 大小)
- `flushIntervalMs`(flush 周期)
- `flushBatchSize`(flush 单批)
- `segmentRetentionDays`(WAL 保留)

---

## 4. 存储层(1 个类,核心)

### 4.1 `InflightBuffer`(核心)

**职责**:**全栈唯一存储层**。单 partition 的有界内存 ring buffer,存 Object 引用,无序列化,无 WAL。

```java
public class InflightBuffer {
    public InflightBuffer(String topic, int partitionId, int capacity, InflightMetrics metrics);
    public boolean offer(Object payload);         // L1 背压:Semaphore.tryAcquire() 失败 → false
    public List<Object> drain(int max);           // 整批 pollFirst + 释放 slots
    public int size();                             // 加锁读 ring size
    public int capacity();
    public int availablePermits();
}
```

**线程安全**:
- `ReentrantLock mutex`:ring 读写互斥
- `Semaphore(capacity)`:容量计数,offer 前 `tryAcquire`(O(1) 原子),失败立即返回 false
- **5 个并发不变量**:ring 大小、drain 唯一消费等(详见 `InflightQueue并发度与并发实现说明.md`)

**v2.0 → v2.1 删除**:`peek(int)` 方法(v2.0 是为 FlushScheduler 服务,无 FlushScheduler 后不需要)

**性能**:
- `offer`:O(1),锁持有时间 < 100 ns
- `drain(N)`:O(N),单批 ~5 μs
- 并发 N 线程:锁竞争率 < 30%

**内存占用**:`bufferCapacity(50000) × sizeof(Object reference, 8B) × K(10 partitions) = ~4 MB` 总占用

---

## 5. 聚合层(2 个类)

### 5.1 `Partition`

**职责**:**单 partition 子系统**,只包 buffer(v2.0 还包 WAL+Flusher,v2.1 简化为只有 buffer)。

```java
public class Partition {
    public Partition(String topic, int partitionId, InflightBuffer buffer);
    public String topic();
    public int partitionId();
    public InflightBuffer buffer();
    public boolean offer(Object payload);
    public List<Object> drain(int max);
    public int pending();
    public int capacity();
    public void start();   // v2.1:空实现(无 WAL 启动)
    public void stop();    // v2.1:空实现(无 WAL 关闭)
}
```

**生命周期**:`@PostConstruct` 时 10 个 partition 全部 `start()`(空实现),`@PreDestroy` 时全部 `stop()`(空实现)。

**v2.0 → v2.1 删除**:WAL / Flusher 字段与 start/stop 内的关闭逻辑。

### 5.2 `InflightQueue`

**职责**:**整个 InflightQueue 系统的门面(Facade)**。初始化 10 个 partition + 路由 + 全局 metrics 聚合。

```java
@Component
@EnableConfigurationProperties(InflightProperties.class)
@ConditionalOnProperty(name = "spring-watch.inflight.enabled", havingValue = "true", matchIfMissing = true)
public class InflightQueue {
    public static final String TOPIC_METRICS = "monitor-metrics";
    public static final String TOPIC_LOGS = "monitor-logs";
    public static final String TOPIC_HEARTBEAT = "monitor-heartbeat";
    public static final String TOPIC_DLQ_METRICS = "monitor-metrics.dlq";
    public static final String TOPIC_DLQ_LOGS = "monitor-logs.dlq";
    public static final String TOPIC_DLQ_HEARTBEAT = "monitor-heartbeat.dlq";

    public Partition[] partitionsOf(String topic);
    public Partition getPartition(String topic, int partitionId);
    public int partitionCount(String topic);
    public List<String> activeTopics();
    public int route(String topic, String key);
    public InflightMetrics metrics();
}
```

**路由算法**(基础版,两选一):
```java
route(topic, key):
    K = partitions.length
    if K == 1: return 0
    if key == null: return random(K)
    i = random(K)
    j = (i+1) mod K
    return partitions[i].pending() <= partitions[j].pending() ? i : j
```

**6 个 topic × 各 partition 数**:
- `monitor-metrics`:3 partition
- `monitor-logs`:3 partition
- `monitor-heartbeat`:1 partition
- `monitor-metrics.dlq`:1 partition
- `monitor-logs.dlq`:1 partition
- `monitor-heartbeat.dlq`:1 partition
- **总 partition 数 = 10**

**M1.5 升级**(未实施):替换为 `PartitionRouter`(支持 `appBindings` + `AppTrafficStats` + `PartitionRebalancer`)

---

## 6. 指标层(1 个类)

### 6.1 `InflightMetrics`

**职责**:**5 个 Micrometer 指标**,按 topic × partition 维度注册。

```java
@Component
public class InflightMetrics {
    public void registerPartition(String topic, int partitionId, int capacity);
    public void sent(String topic, int partitionId);
    public void rejected(String topic, int partitionId);
    public void drained(String topic, int partitionId, int n);
    public void recordBatchSize(String topic, int partitionId, int n);
    public void updatePending(String topic, int partitionId, int size);
    public long totalSent();
    public long totalRejected();
}
```

**5 个指标**:

| 指标 | 类型 | Tags | 含义 |
|---|---|---|---|
| `inflight.producer.sent` | Counter | topic, partition | producer 累计入队条数 |
| `inflight.producer.rejected` | Counter | topic, partition | L1 拒绝条数(背压) |
| `inflight.producer.drained` | Counter | topic, partition | consumer 累计消费条数 |
| `inflight.queue.pending` | Gauge | topic, partition | 当前堆积(buffer size) |
| `inflight.queue.capacity` | Gauge | topic, partition | buffer 容量上限(常量) |
| `inflight.consumer.batch.size` | DistributionSummary | topic, partition | p50/p95/p99 批大小 |

**v2.0 → v2.1 删除**:
- `wal.append.fail` Counter(WAL 不存在)
- `wal.segments` Gauge(WAL 不存在)

---

## 7. 背压层(1 个类)

### 7.1 `BackpressureHandler`

**职责**:**L1 满时的业务侧 3 选 1 策略**。

```java
public class BackpressureHandler {
    public enum Strategy { DROP, RETRY, RETRY_THEN_DROP }

    public void setStrategy(Strategy strategy);
    public void handle(String topic, int partitionId, Object payload, BackpressureException ex);
}
```

**3 选 1 策略**:`DROP`(默认)/ `RETRY` / `RETRY_THEN_DROP`

**调用方**:`InflightProducerBridge.send()` 内部 catch `BackpressureException` 后调用
**约束**:**不抛**,**不重投**(白皮书 C2)
**v2.1 注意**:`payload` 参数当前**未实际使用**(3 个策略都只调 `drop()`),保留供后续 RETRY 策略扩展

---

## 8. 业务层(5 个类)

### 8.1 `InflightProducerBridge`

**职责**:**替换 v1.6 的 `KafkaProducerBridge`**,提供 `sendMetric / sendLog / sendHeartbeat` 三个方法。

```java
@Component
@ConditionalOnProperty(name = "spring-watch.inflight.enabled", havingValue = "true", matchIfMissing = true)
public class InflightProducerBridge {
    public void sendMetric(MetricEvent event);
    public void sendLog(LogEvent event);
    public void sendHeartbeat(HeartbeatEvent event);
}
```

**send 流程**:
```java
send(topic, event):
    key = extractKey(event)
    partitionId = inflightQueue.route(topic, key)
    p = inflightQueue.getPartition(topic, partitionId)
    ok = p.offer(event)                    // 直接 Object 引用,无序列化
    if ok: metrics.sent()
    else: backpressureHandler.handle(event, ex)
```

**关键**:**没有 `objectMapper` 依赖**(纯内存,无序列化)
**调用方**:`AgentMetricsCollector` / `AgentLogCollector` / `AppPullTask`

### 8.2 `InflightConsumer`

**职责**:**替换 v1.6 的 `@KafkaListener`**,N 虚拟线程/topic 随机 poll partition。

```java
@Component
@ConditionalOnProperty(name = "spring-watch.inflight.enabled", havingValue = "true", matchIfMissing = true)
public class InflightConsumer {
    @PostConstruct void init();
    @PreDestroy void shutdown();
}
```

**consumeLoop**:
```java
while (!Thread.interrupted()):
    arr = inflightQueue.partitionsOf(topic)
    p = arr[random.nextInt(arr.length)]
    events = p.drain(maxBatch)             // ★ 纯 drain,无 WAL 竞争
    if events.empty(): sleep(waitMs); continue
    processBatch(topic, p.partitionId(), events)
```

**v2.1 关键变化**:`drain(maxBatch)` 是**唯一拿走事件**的路径,无 FlushScheduler 抢,简单清晰。

**processBatch 路由**:

| topic | 处理 |
|---|---|
| `monitor-metrics` | `metricWriter.write(events)` + `batchAlertConsumer.evaluate(events)` |
| `monitor-logs` | `logWriter.write(events)` |
| `monitor-heartbeat` | `heartbeatWriter.write(events)` |

**并发度**:`concurrency.monitor-metrics: 2` + `monitor-logs: 2` + `monitor-heartbeat: 1` = **5 个虚拟线程**(M2)
**真实并行度**:K partition × 1 锁 = **4 倍真实并发**(不受虚拟线程数影响,锁才是瓶颈)

### 8.3 `MetricEventWriter`

**职责**:`List<Object>` → InfluxDB `springboot_metrics` 桶。

```java
public int write(List<Object> events);
```

**字段映射**:`appid` / `metricName` / `method` / `value` / `count` / `tags` → tag/field

### 8.4 `LogEventWriter`

**职责**:`List<Object>` → InfluxDB `app_log` 桶。

**字段映射**:15 个字段(见 v2.0 手册)

### 8.5 `HeartbeatEventWriter`

**职责**:`List<Object>` → PostgreSQL `MonitorApp` 更新。

```java
public record WriteResult(int processed, int failed, int notInDb) {}
@Transactional
public WriteResult write(List<Object> events);
```

**特殊点**:**写 PostgreSQL 不写 InfluxDB**,批量 `findAllByAppidIn` + `saveAll`

---

## 9. 关键调用关系图(v2.1 简化版)

```
┌─────────────────────────────────────────────────────────────┐
│  业务侧(已改写)                                             │
│  AgentMetricsCollector / AppPullTask / AgentLogCollector   │
└──────────────────────────┬──────────────────────────────────┘
                           │ sendMetric / sendLog / sendHeartbeat
                           ▼
                  ┌─────────────────────┐
                  │ InflightProducerBridge│
                  │  (无序列化, 纯内存)  │
                  └──────────┬──────────┘
                             │ offer(Object)
                             ▼
                  ┌─────────────────────┐
                  │ InflightQueue       │
                  │  route() + getPartition()│
                  └──────────┬──────────┘
                             │
                             ▼
                  ┌─────────────────────┐
                  │ Partition           │
                  │  offer/drain(直接转发 buffer)│
                  └──────────┬──────────┘
                             │
                             ▼
              ┌──────────────────────────┐
              │ InflightBuffer(ring)    │ ← 唯一存储,纯内存
              └──────────┬───────────────┘
                         │
                         │ drain(maxBatch)
                         ▼
                ┌─────────────────────┐
                │ InflightConsumer     │
                │  虚拟线程 poll       │
                └──────────┬──────────┘
                           │ List<Object>
                           ▼
                  ┌─────────────────────┐
                  │ MetricEventWriter   │
                  │ LogEventWriter      │
                  │ HeartbeatEventWriter│
                  │ BatchAlertConsumer  │
                  └──────────┬──────────┘
                             │
                             ▼
                     ┌──────────────┐
                     │ InfluxDB/PG  │
                     └──────────────┘
```

**v2.0 → v2.1 简化**:
- ❌ 删了 FlushScheduler 分支
- ❌ 删了 MmapWAL 节点
- ❌ 删了 JsonCodec 节点(无序列化)
- 路径从 4 层 → 3 层(Producer → Queue → Buffer → Consumer → DB)

---

## 10. 性能数据(v2.1 简化后)

| 操作 | 耗时 | 备注 |
|---|---|---|
| `InflightBuffer.offer()` | ~1 μs | 1 次 tryAcquire + 1 次 ReentrantLock.lock + 1 次 ArrayDeque.offerLast |
| `InflightBuffer.drain(200)` | ~5 μs | 1 次 lock + 200 次 pollFirst |
| `InflightQueue.route()` | ~100 ns | 2 次 `LongAdder.sum` + 比较 |
| `InflightConsumer.consumeLoop()`(每轮) | ~10 ms | poll 拿批 5 μs + write ~30 μs + sleep 100ms |
| `MetricEventWriter.write(200)` | ~30 μs | 200 次 `instanceof` + 1 次 `writePoints` |

**总端到端延迟**:**100 ~ 300 ms**(主要在 consumer poll 等批 100ms + InfluxDB 写入)

**v2.0 → v2.1 收益**:
- ❌ 删了 FlushScheduler.flushOnce 的 50 μs 序列化开销
- ❌ 删了 MmapWAL.append 的 1 μs 开销
- **总端到端延迟**:**100 ~ 300 ms → 100 ~ 200 ms**(略快,因省了序列化+落盘)

---

## 11. 测试覆盖

| 测试类 | 覆盖目标 | 关键用例 |
|---|---|---|
| `InflightBufferTest` | offer/drain/容量/并发 | 4 个测试,100 容量并发 offer 500 验证不变量 |

**覆盖率**:`InflightBuffer` 90%+ 行覆盖(核心存储层都覆盖)
**M2 增量**:`InflightQueueTest` / `InflightProducerBridgeTest` / `InflightConsumerTest` / `InflightMetricsTest` / `InflightPartitionRouterTest`(均匀性)

---

## 12. 已知问题 / 后续 TODO

| 编号 | 问题 | 影响 | 修复 |
|---|---|---|---|
| W1 | `InflightQueue.route()` 基础版,不支持 `appBindings` | 极端 80/20 流量下 partition 不均 | M1.5 引入 `PartitionRouter` |
| W2 | 无 `PartitionRebalancer` | 长期不均无法自动迁移 | M1.5 引入 |
| W3 | **无 WAL 持久化**——进程崩溃 = 当前所有 in-flight 数据丢失 | 监控数据丢失窗口 = 进程生命周期 | 接受(白皮书 C1 改) |
| W4 | 无 `InflightDLQStore` + `InflightDlqMonitorConsumer` | Consumer 端反序列化失败**没地方去** | M3.5 引入(白皮书 §14 轻量 DLQ) |
| W5 | `HeartbeatEventWriter` 用 `instanceof`,业务侧传错类型会**静默跳过** | debug 困难 | 加 log 计数 + 告警 |
| W6 | M2 阶段 DLQ 3 个 topic 创建了 partition 但无数据,空 buffer + 空 FlushScheduler(已删,改空 start/stop) | 浪费 ~1.5 KB × 3 = 4.5 KB 内存 | 接受(可忽略) |

任务已完成!kxj
