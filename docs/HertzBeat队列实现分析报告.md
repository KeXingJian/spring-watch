# HertzBeat 队列实现分析报告

> **对比项目**:Apache HertzBeat(Java 25 / Spring Boot 4.0.3 / 多模块 / 默认 redis 队列)
> **被分析目录**:`D:\codespace\ideaProject\hertzbeat`(对应 `/mnt/d/codespace/ideaProject/hertzbeat`)
> **本项目**:spring-watch(v1.6,Java 25 / Spring Boot 4.0.1 / Kafka 4.3 单 broker)
> **分析目的**:HertzBeat 已有 4 种可切换的 `CommonDataQueue` 实现,作为 spring-watch 自研内存队列替换 Kafka 的**参考实现**
> **结论先行**:HertzBeat 的**接口抽象 + `@ConditionalOnProperty` 切换**模式可直接借鉴;但其 `InMemoryCommonDataQueue` 是**无界纯 `LinkedBlockingQueue`**,**没有持久化、没有背压、没有告警**——本项目**不能照搬**,需在其基础上**叠加 mmap WAL + 三层背压 + Micrometer 埋点**

---

## 0. 摘要

| 维度 | HertzBeat | spring-watch(本项目) | 是否可借鉴 |
|---|---|---|---|
| **接口抽象** | `CommonDataQueue` 13 个方法 | `EventBus` + `EventConsumer`(规划中) | ✅ **完全借鉴** |
| **多实现切换** | `@ConditionalOnProperty` × 4 | `@ConditionalOnProperty` × 2~3 | ✅ **完全借鉴** |
| **内存实现** | 5 个 `LinkedBlockingQueue` 无界 | 需要有界 + 持久化 + 背压 | ⚠️ **骨架可借鉴,实现需重写** |
| **批量 poll 模式** | `poll(1s) + drainTo(max-1)` | 需要 | ✅ **值得借鉴** |
| **背压** | ❌ 无 | ✅ 需要 | ➕ 增量设计 |
| **持久化** | ❌(纯内存) / ✅(Kafka log) / ✅(Redis AOF) | ✅ 需要(mmap WAL) | ➕ 增量设计 |
| **监控指标** | `getQueueSizeMetricsInfo()` 4 个 | 需要 6 个 + 告警阈值 | ⚠️ 借鉴思路,扩展 |
| **partition key** | `appId`(业务字段) | `appid`(已有) | ✅ 一致 |

---

## 1. HertzBeat 队列架构总览

### 1.1 模块定位

HertzBeat 把"数据队列"作为**横切关注点**,放在 `hertzbeat-common-core`(接口)+ `hertzbeat-common-spring`(实现)两个模块:

```
hertzbeat-common-core/
└─ org/apache/hertzbeat/common/queue/
   ├─ CommonDataQueue.java            ← 接口(13 个方法,纯 POJO + InterruptedException)
   └─ ../constants/DataQueueConstants.java  ← 4 种实现名常量(memory/kafka/redis/netty)

hertzbeat-common-spring/
└─ org/apache/hertzbeat/common/queue/impl/
   ├─ InMemoryCommonDataQueue.java    ← 5 个 LinkedBlockingQueue
   ├─ KafkaCommonDataQueue.java       ← 6 个 Kafka topic
   ├─ RedisCommonDataQueue.java       ← Redis Lettuce client,4 个 List
   └─ NettyDataQueue.java             ← 跨进程 RPC(collector→manager)
```

> 注意:`NettyCommonDataQueue.java` **不在 common-spring 模块**,在 `hertzbeat-collector-common/.../dispatch/export/NettyDataQueue.java`,因为它给独立的 collector 进程用。

### 1.2 接口全景:`CommonDataQueue`

```java
public interface CommonDataQueue {
    CollectRep.MetricsData pollMetricsDataToAlerter()    throws InterruptedException;
    CollectRep.MetricsData pollMetricsDataToStorage()    throws InterruptedException;
    CollectRep.MetricsData pollServiceDiscoveryData()    throws InterruptedException;
    void sendMetricsData(CollectRep.MetricsData);
    void sendMetricsDataToStorage(CollectRep.MetricsData);
    void sendServiceDiscoveryData(CollectRep.MetricsData);
    void sendLogEntry(LogEntry);
    LogEntry pollLogEntry()                              throws InterruptedException;
    void sendLogEntryToStorage(LogEntry);
    LogEntry pollLogEntryToStorage()                     throws InterruptedException;
    void sendLogEntryToAlertBatch(List<LogEntry>);
    List<LogEntry> pollLogEntryToAlertBatch(int maxBatchSize)   throws InterruptedException;
    void sendLogEntryToStorageBatch(List<LogEntry>);
    List<LogEntry> pollLogEntryToStorageBatch(int maxBatchSize)  throws InterruptedException;
}
```

**抽象出的 5 类消息**:
1. `MetricsData` → 告警评估器
2. `MetricsData` → 持久化存储
3. `ServiceDiscoveryData` → 服务发现
4. `LogEntry` → 告警 + 存储
5. 批量发送/接收(给吞吐大的日志路径)

### 1.3 切换机制

```java
@ConditionalOnProperty(
    prefix = DataQueueConstants.PREFIX,        // "common.queue"
    name    = DataQueueConstants.NAME,         // "type"
    havingValue = DataQueueConstants.IN_MEMORY // "memory"
    , matchIfMissing = true                    // ← 默认内存版!
)
```

**`application.yml`**:
```yaml
common:
  queue:
    type: redis           # memory | kafka | redis | netty
    kafka: { ... }        # 6 个 topic 名 + servers
    redis: { ... }        # 4 个 queue 名 + host:port
```

**默认是 `memory`**(matchIfMissing=true),生产推荐 `redis`(hertzbeat 官方推荐)。这点和 spring-watch 思路一致——**单实例默认用最轻量的实现**。

---

## 2. `InMemoryCommonDataQueue` 详细分析

### 2.1 完整实现(173 行,本报告去 Apache License 头后核心代码)

```java
public class InMemoryCommonDataQueue implements CommonDataQueue, DisposableBean {

    private final LinkedBlockingQueue<CollectRep.MetricsData> metricsDataToAlertQueue;
    private final LinkedBlockingQueue<CollectRep.MetricsData> metricsDataToStorageQueue;
    private final LinkedBlockingQueue<CollectRep.MetricsData> serviceDiscoveryDataQueue;
    private final LinkedBlockingQueue<LogEntry>                logEntryQueue;
    private final LinkedBlockingQueue<LogEntry>                logEntryToStorageQueue;

    public InMemoryCommonDataQueue() {
        metricsDataToAlertQueue       = new LinkedBlockingQueue<>();   // ← 无界!
        metricsDataToStorageQueue     = new LinkedBlockingQueue<>();
        serviceDiscoveryDataQueue     = new LinkedBlockingQueue<>();
        logEntryQueue                 = new LinkedBlockingQueue<>();
        logEntryToStorageQueue        = new LinkedBlockingQueue<>();
    }

    @Override public CollectRep.MetricsData pollMetricsDataToAlerter() throws InterruptedException {
        return metricsDataToAlertQueue.take();   // 阻塞取
    }

    @Override public void sendMetricsData(CollectRep.MetricsData metricsData) {
        metricsDataToAlertQueue.offer(metricsData);  // 非阻塞 offer
    }

    @Override public List<LogEntry> pollLogEntryToAlertBatch(int maxBatchSize) throws InterruptedException {
        List<LogEntry> batch = new ArrayList<>(maxBatchSize);
        LogEntry first = logEntryQueue.poll(1, TimeUnit.SECONDS);   // ← 等 1s 凑批
        if (first != null) {
            batch.add(first);
            logEntryQueue.drainTo(batch, maxBatchSize - 1);         // ← 一次性 drain
        }
        return batch;
    }

    public Map<String, Integer> getQueueSizeMetricsInfo() {
        Map<String, Integer> metrics = new HashMap<>(8);
        metrics.put("metricsDataToAlertQueue",   metricsDataToAlertQueue.size());
        metrics.put("metricsDataToStorageQueue", metricsDataToStorageQueue.size());
        metrics.put("logEntryQueue",             logEntryQueue.size());
        metrics.put("logEntryToStorageQueue",    logEntryToStorageQueue.size());
        return metrics;
    }

    @Override public void destroy() {
        metricsDataToAlertQueue.clear();
        metricsDataToStorageQueue.clear();
        serviceDiscoveryDataQueue.clear();
        logEntryQueue.clear();
        logEntryToStorageQueue.clear();
    }
}
```

### 2.2 优点

1. **极简** — 5 个 JUC 队列 + 13 个方法,无任何额外抽象
2. **零配置** — 无需任何 broker / 容器,`docker compose up` 只需 1 个 app 容器
3. **类型安全** — `LogEntry` / `CollectRep.MetricsData` 强类型,无 JSON 序列化开销
4. **批量模式巧妙** — `poll(1s) + drainTo(max-1)`,**最多等 1 秒**凑一批但不超过 `maxBatchSize`,吞吐和延迟平衡得很好
5. **`getQueueSizeMetricsInfo`** — 主动导出 4 个队列 size,自监控友好
6. **可热切换** — Spring `@ConditionalOnProperty` 一行注解,配置变更 + 重启即可

### 2.3 严重缺陷

1. **❌ 容量无界**:`new LinkedBlockingQueue<>()` 默认 `Integer.MAX_VALUE`,背压完全依赖下游消费速度。一旦消费卡住,堆内存会被吃光。spring-watch 现有 `KafkaFallbackQueue` 已经踩过这个坑(50000 → 10000 调小),本项目**不能重蹈覆辙**
2. **❌ 无持久化**:进程崩溃 → 5 个队列清空,HertzBeat 文档明说"hertzbeat 内存版适合开发/测试,生产请用 redis/kafka"
3. **❌ 无消费失败处理**:`take()` 拿出来的元素直接交给业务,**没有 DLQ、没有重试、没有 commit 机制**。一条坏数据可能导致死循环
4. **❌ 无超时 fallback**:`take()` 是无限阻塞的,虚拟线程的 take 可能无限挂起,无超时降级
5. **❌ 无指标埋点**:除了 `getQueueSizeMetricsInfo()` 手动调用,**没有 Micrometer 埋点**、没有告警阈值、没有反压告警
6. **❌ 无 partition 概念**:5 个独立队列,**没有按 appId 分区的能力**——这会导致 hot key(某个大业务)卡住整个队列。hertzbeat 的 Kafka/Redis 实现有 partition,但内存版没有
7. **❌ 无优雅关闭**:`destroy()` 只是 `.clear()`,不等待消费者处理完正在执行的数据

---

## 3. spring-watch 规划对比与改进点

### 3.1 借鉴清单(直接照搬)

| 借鉴点 | 来源 | spring-watch 改造 |
|---|---|---|
| **`CommonDataQueue` 接口抽象** | `hertzbeat-common-core/.../queue/CommonDataQueue.java` | 拆为 `EventBus`(send)+ `EventConsumer`(poll/commit)双接口,职责更清晰 |
| **`@ConditionalOnProperty` 切换** | `InMemoryCommonDataQueue.java:39-45` | 复用到 `InflightProducerBridge` / `InflightMetricConsumer` 等 |
| **`poll(timeout) + drainTo(max)` 批量模式** | `InMemoryCommonDataQueue.java:136-141` | 改造为 `poll(100ms) + drainTo(batchSize)`,虚拟线程友好 |
| **`getQueueSizeMetricsInfo` 思路** | `InMemoryCommonDataQueue.java:64-71` | 升级为 Micrometer `Gauge` × 5 个 topic,自动采集 |
| **`DisposableBean` 优雅关闭** | `InMemoryCommonDataQueue.java:166-172` | 升级为 `awaitTermination(超时)` 等消费完再关 |
| **配置前缀** | `common.queue.type` | 命名为 `spring-watch.eventbus.type`,与 spring-watch 既有风格一致 |

### 3.2 增量设计(本项目必须新增)

#### 3.2.1 三层背压(借鉴 spring-watch 既有 `KafkaFallbackQueue`)

```java
class InflightQueue {
    // L1 内存配额
    private final Semaphore memSlots;                    // 默认 50000
    // L2 WAL 落盘速率
    private final RateLimiter walAppendRate;             // 默认 1000/s
    // L3 pending 阈值告警
    private final AtomicLong pendingThreshold;           // 80% 触发告警
}
```

#### 3.2.2 mmap WAL 持久化(本规划 M2)

```java
class MmapWAL {
    private final FileChannel channel;
    private final MappedByteBuffer segment;
    // Record: magic(4) + len(4) + crc(4) + ts(8) + keyLen(4) + key + payload
    public void append(byte[] record) throws IOException;
    public List<EventRecord> read(long fromOffset, int max);
    public void fsync();                    // 可选强制刷盘
    public void recover(long commitOffset); // 启动时从 commit 位点恢复
}
```

#### 3.2.3 Micrometer 全埋点(对比 hertzbeat 4 个指标)

| 指标名 | 类型 | 说明 |
|---|---|---|
| `spring.watch.inflight.producer.sent{topic}` | Counter | 发送累计 |
| `spring.watch.inflight.queue.pending{topic}` | Gauge | 当前堆积 |
| `spring.watch.inflight.queue.capacity{topic}` | Gauge | 容量上限 |
| `spring.watch.inflight.consumer.batch.size{topic}` | DistributionSummary | 每批条数 |
| `spring.watch.inflight.consumer.commit.lag{topic}` | Gauge | readOffset fsync 间隔 |
| `spring.watch.inflight.wal.append.fail{topic}` | Counter | WAL 落盘失败 |

#### 3.2.4 DLQ + 重试(本规划 4.5)

```java
// 复用 hertzbeat "失败转专用队列" 模式
class InflightDlqQueue {
    void offer(InflightRecord original, Throwable cause);
    // 监控:Sent/Size/Auto-Truncate
}
```

---

## 4. 对规划文档的更新建议

回看 `docs/自研内存队列替换Kafka规划.md`,建议如下修订:

| 章节 | 当前内容 | 修订建议 |
|---|---|---|
| 3.2 `EventBus` 接口 | 单 send 接口 | 拆为 `EventBus` + `EventConsumer` 双接口(借鉴 hertzbeat `CommonDataQueue`) |
| 3.3 `EventConsumer` | 已有 poll/commit | **加入 hertzbeat 的"批量 poll"模式**:`poll(100ms) + drainTo(max-1)` |
| 4.1 内存结构 | 5 个 topic × ArrayDeque + mmap | **加入 partition 概念**:每 topic 多分区,key=appid 哈希,避免 hot key(本项目暂不实现,但接口预留) |
| 4.4 监控指标 | 6 个 | **加入 hertzbeat 风格的 `getQueueSizeMetricsInfo()` 方法**,便于调试 + 单元测试 |
| 5 M1 任务 | 旁路双写 | **新增任务**:实现 `getInflightQueueMetricsInfo()` 调试方法(对标 hertzbeat 的同款方法) |
| 6 风险表 | 已有 7 行 | **新增**:"批量 drainTo 阻塞"风险(如果 1s 无人生产,poller 线程空转 1s) |
| 9 附录代码 | EventBus/Consumer 骨架 | **加入 hertzbeat 风格的接口示例** |

---

## 5. spring-watch 是否要"参照 hertzbeat 默认开内存版"?

### 5.1 HertzBeat 的"默认内存"适用条件

- ✅ HertzBeat 自身定位是"集群部署的监控",即使 default 是内存,生产也建议改 redis
- ✅ HertzBeat 队列对接的是 `CollectRep.MetricsData` 强类型,序列化无开销
- ✅ HertzBeat 业务可容忍"内存版丢一些数据"——告警计算可以重跑

### 5.2 spring-watch 不适合"无持久化内存版"

- ❌ spring-watch 定位"单机全栈单实例",没有 redis 兜底
- ❌ spring-watch 白皮书 0.3 明确承诺"**进程崩溃 → 30s 内自愈,数据不丢**",**无持久化内存版直接违反**
- ❌ spring-watch 既有 `KafkaFallbackQueue` 已经证明"纯内存队列"在生产不够用

### 5.3 结论

spring-watch **不能直接照搬 hertzbeat 的默认内存版**,应在其基础上**强制叠加 mmap WAL**(本规划 4.2)。但**接口风格、`@ConditionalOnProperty` 切换、批量 poll 模式、queue size 导出**这四点**完全照搬**。

---

## 6. 经验提炼(对团队)

### 6.1 做得好的地方(借鉴)

1. **接口先行**:hertzbeat 1 个 `CommonDataQueue` 接口撑起 4 种实现,业务代码零感知 → **spring-watch 也要严格遵守"接口 + 多种实现"**
2. **`@ConditionalOnProperty` + `matchIfMissing=true`**:Spring 切换实现最优雅的方式 → **本规划 M2 沿用**
3. **`poll(timeout) + drainTo(max-1)` 批量模式**:简单但有效,虚拟线程友好 → **本规划 3.3 采纳**
4. **配置名空间**:`common.queue.type`,清晰独立 → **本项目用 `spring-watch.eventbus.type`**

### 6.2 做得不好的地方(避免)

1. **容量无界**:`new LinkedBlockingQueue<>()` 一定踩内存坑 → **本项目永远用有界 + 背压 + 告警**
2. **无持久化就声明"内存版"**:命名容易让人误以为"等同于持久化" → **本项目命名 `InflightQueue`**(明示是 in-flight 临时队列,WAL 才负责持久化)
3. **DLQ 缺失**:5 个队列没有 DLQ 概念 → **本项目保留 DLQ(4 个 topic × 1 DLQ = 4 个 DLQ,简化合并到 1 个 `*.dlq.wal`)**
4. **无指标埋点**:手动 `getQueueSizeMetricsInfo()` 不如自动 Micrometer → **本项目全 Micrometer 化**
5. **无优雅关闭**:`destroy()` 只 clear,不等待消费 → **本项目 `@PreDestroy` 加 `awaitTermination(timeout)` + drain 残余**

### 6.3 一句话总结

> **学 hertzbeat 的接口抽象和切换机制,弃 hertzbeat 的纯内存实现,在其上加 mmap WAL + 三层背压 + Micrometer 埋点 + DLQ,得到 spring-watch 专属的 InflightQueue。**

---

## 7. 附录:HertzBeat 关键文件清单

| 文件 | 行数 | 角色 |
|---|---|---|
| `hertzbeat-common-core/.../queue/CommonDataQueue.java` | 123 | 接口定义 |
| `hertzbeat-common-core/.../constants/DataQueueConstants.java` | 54 | 4 种实现名常量 |
| `hertzbeat-common-spring/.../queue/impl/InMemoryCommonDataQueue.java` | 173 | 内存实现(本次重点分析) |
| `hertzbeat-common-spring/.../queue/impl/KafkaCommonDataQueue.java` | ~450 | Kafka 实现(6 topic) |
| `hertzbeat-common-spring/.../queue/impl/RedisCommonDataQueue.java` | 242 | Redis 实现(Lettuce client) |
| `hertzbeat-collector-common/.../dispatch/export/NettyDataQueue.java` | 133 | Netty RPC 实现(给独立 collector) |
| `hertzbeat-common-spring/.../test/.../InMemoryCommonDataQueueTest.java` | 161 | 内存版单测(可作 spring-watch 单测模板) |
| `hertzbeat-startup/.../application.yml:139-159` | 21 | `common.queue.type` 配置 |

**任务已完成!kxj**
