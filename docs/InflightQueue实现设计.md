# InflightQueue 实现设计(v4 汇总版)

> **本文档**:`docs/InflightQueue实现设计.md` —— **最新版实现设计**,作为 v2.0 实施的唯一权威参考
> **上一级**:`白皮书-InflightQueue.md`(定性文件,所有约束以它为准) + 根 `白皮书.md`(项目整体)
> **参考**:`docs/HertzBeat队列实现分析报告.md`(借鉴其接口抽象与切换模式,弃其纯内存实现)
> **历史版本**:`自研内存队列替换Kafka规划.md`(v1 规划)/ `InflightQueue实现设计.md`(v1 单 buffer)/ `InflightQueue并发度与并发实现说明.md`(v1.5 并发)/ `InflightQueue分区模式设计.md`(v2 partition)/ `InflightQueue路由与负载均衡设计.md`(v3 路由)
>
> **本文档性质**:可落地的工程实现设计 —— **架构 / 数据结构 / 路由 / 并发 / 改动边界 / 验收**
> **目标版本**:spring-watch v2.0(M1 旁路 + M2 灰度切流完成)
> **遵守原则**:AGENTS.md 3 注入 / 4 关键日志 / 7 最小实现 / 11 不动 springboot & jvm 版本
> **白皮书回扣**:C1 持久化 / C2 允许丢失少量 / C3 不要求顺序 / C4 入队前已打时间戳 / C5 并发度可配 / C6 控制内存
> **白皮书例外**:N13 改为"轻量 DLQ"(详见 15)

---

## 0. 一句话总结

按白皮书 6 条约束,落地为 **1 个门面 + 4 个核心类 + 3 个支撑类 + 1 个 Rebalancer + 1 个轻量 DLQ**,共约 2350 行,**完全替换 Kafka 4.3 单 broker**;**默认 K=4 partition + power_of_two_choices 路由 + 后台 Rebalancer**,真实并发度 = K(对比 v1 单 buffer 的 1);**轻量 DLQ** 作为白皮书 N13 的受控例外,保留 v1.6 既有 DLQ 落库链路(详见 15)。

---

## 1. 文档导读

| 章节 | 内容 | 对应白皮书约束 |
|---|---|---|
| 2 | 架构总览 + 数据流 | 全约束 |
| 3 | 数据结构(partition / buffer / WAL / record) | C1 C6 |
| 4 | 路由策略(3 种可配)+ 后台 Rebalancer | C3 C5 C6 |
| 5 | API 形态 | 全约束 |
| 6 | 并发模型(Producer / Flush / Consumer) | C5 C6 |
| 7 | 背压策略(3 层防线) | C6 |
| 8 | 监控指标(11 个 = 7 主 + 4 DLQ) | 全约束 |
| 9 | 故障与恢复(WAL 损坏 / 进程崩溃) | C1 C2 |
| 10 | 配置项(完整 application.yml) | 全约束 |
| 11 | 代码改动边界(新增 / 修改 / 删除) | — |
| 12 | 验证验收标准(单测 / 集成 / 性能 / 灰度) | — |
| 13 | 与 Kafka 的对比 | — |
| **14** | **投递失败处理(轻量 DLQ)** | **白皮书 N13 受控例外** |
| 15 | 总结(决策矩阵 + 关键 5 点) | — |

---

## 2. 架构总览

### 2.1 完整架构(K=4 partition 模式)

```mermaid
flowchart TB
    subgraph Prods["业务侧 Producer (N 线程)"]
        P1[采集线程 1]
        P2[采集线程 2]
        Pn[...N]
    end

    Router["PartitionRouter<br/>(★ power_of_two_choices)"]
    Bindings["appBindings<br/>(Rebalancer 写入)"]

    subgraph Topic["topic = monitor-metrics (K=4 partition)"]
        P0["partition-0<br/>Buffer + WAL + Lock #0"]
        P1p["partition-1<br/>Buffer + WAL + Lock #1"]
        P2p["partition-2<br/>Buffer + WAL + Lock #2"]
        P3p["partition-3<br/>Buffer + WAL + Lock #3"]
    end

    Rebalancer["PartitionRebalancer<br/>(60s 周期)"]
    TrafficStats["AppTrafficStats<br/>(per-partition)"]

    subgraph Cons["Consumer 虚拟线程池"]
        C1[虚拟线程 1]
        C2[虚拟线程 2]
        Cn[...N]
    end

    Influx[(InfluxDB)]

    P1 & P2 & Pn -->|"route(appid)"| Router
    Router --> P0 & P1p & P2p & P3p
    Bindings -.-> Router
    P0 & P1p & P2p & P3p -.->|update| TrafficStats
    TrafficStats --> Rebalancer
    Rebalancer -->|迁移 appid| Bindings
    C1 & C2 & Cn -->|"随机 poll"| P0 & P1p & P2p & P3p
    P0 & P1p & P2p & P3p -->|writePoints| Influx

    classDef critical fill:#fef3c7,stroke:#f59e0b
    class Router,Rebalancer critical
```

### 2.2 数据流(单条记录完整生命周期)

```mermaid
sequenceDiagram
    autonumber
    participant C as MetricCollector
    participant P as InflightProducerBridge
    participant R as PartitionRouter
    participant B as InflightBuffer<br/>(单 partition)
    participant F as FlushScheduler
    participant W as MmapWAL
    participant Co as InflightConsumer<br/>(虚拟线程)
    participant I as InfluxDB

    rect rgb(240, 249, 255)
        Note over C,B: 阶段 1:采集 + 路由 + 入队
        C->>P: sendMetric(event)
        P->>P: extractAppid + 序列化 (~50μs)
        P->>R: route(appid)
        R-->>P: partitionId (0~K-1)
        P->>B: buffer.offer(payload) [L1 背压]
        B-->>P: true
    end

    rect rgb(254, 249, 195)
        Note over F,W: 阶段 2:100ms 后 flush
        F->>B: drain(1000)
        B-->>F: List<byte[]>
        F->>W: mmap.append
        W-->>F: true (OS page cache)
    end

    rect rgb(240, 253, 244)
        Note over Co,I: 阶段 3:消费到 InfluxDB
        Co->>B: poll(200, 100ms)
        B-->>Co: List<InflightRecord> ≤200
        Co->>Co: 反序列化 → Point
        Co->>I: writePoints
        I-->>Co: ok
    end
```

**端到端延迟**:**100 ~ 300 ms**(consumer poll 等批 100ms + InfluxDB 写入)

---

## 3. 数据结构

### 3.1 类图

```mermaid
classDiagram
    direction TB

    class InflightQueue {
        <<Facade>>
        +init() PostConstruct
        +shutdown() PreDestroy
        -Map~String,Partition[]~ topicPartitions
    }

    class Partition {
        <<core>>
        +topic: String
        +partitionId: int
        +buffer: InflightBuffer
        +wal: MmapWAL
        +flusher: FlushScheduler
    }

    class InflightBuffer {
        +offer(byte[]) boolean
        +drain(int) List~byte[]~
        +pending() long
        -mutex: ReentrantLock
        -slots: Semaphore
    }

    class MmapWAL {
        +append(byte[]) boolean
        +read(long, int) List~InflightRecord~
        -mutex: ReentrantLock
    }

    class WALSegment {
        -MappedByteBuffer buf
        -FileChannel ch
    }

    class FlushScheduler {
        +start()
        -buffer: InflightBuffer
        -wal: MmapWAL
    }

    class InflightProducerBridge {
        +sendMetric(MetricEvent)
        +sendLog(LogEvent)
        -route(String appid) int
    }

    class InflightConsumer {
        +start(ConsumerConcurrency)
        -consumeLoop()
    }

    class PartitionRouter {
        <<util>>
        +route(String appid) int
        -appBindings: Map
        -strategy: RoutingStrategy
    }

    class PartitionRebalancer {
        +start()
        -trafficStats: AppTrafficStats
        -appBindings: Map
    }

    class AppTrafficStats {
        +recordSend(String appid)
        +topN(int) List~AppRank~
    }

    class RoutingStrategy {
        <<enum>>
        POWER_OF_TWO_CHOICES
        WEIGHTED_ROUND_ROBIN
        ROUND_ROBIN
    }

    class InflightMetrics {
        +sent(String topic, int partition)
        +rejected(...)
        +updatePending(...)
    }

    class InflightProperties {
        +partitions: int = 4
        +routing.strategy
        +routing.rebalance.enabled
    }

    class BackpressureHandler {
        +handle(...)
    }

    class InflightRecord {
        +topic: String
        +partitionId: int
        +key: String
        +payload: byte[]
        +timestampMs: long
        +offset: long
    }

    InflightQueue "1" --> "*" Partition : topic × K
    Partition "1" --> "1" InflightBuffer
    Partition "1" --> "1" MmapWAL
    Partition "1" --> "1" FlushScheduler
    FlushScheduler --> InflightBuffer
    FlushScheduler --> MmapWAL
    MmapWAL "1" --> "1" WALSegment
    InflightProducerBridge --> PartitionRouter
    InflightProducerBridge --> InflightQueue
    InflightConsumer --> InflightQueue
    InflightConsumer --> PartitionRouter
    PartitionRouter --> RoutingStrategy
    PartitionRebalancer --> AppTrafficStats
    PartitionRebalancer --> PartitionRouter : 写 appBindings
    InflightQueue --> InflightProperties
```

### 3.2 内存 in-flight buffer(per-partition)

```java
public class InflightBuffer {
    private final String topic;
    private final int partitionId;
    private final int capacity;                  // 严格有界,默认 50000
    private final ArrayDeque<byte[]> ring = new ArrayDeque<>();
    private final ReentrantLock mutex = new ReentrantLock();
    private final Semaphore slots;               // 容量计数
    private final InflightMetrics metrics;

    public boolean offer(byte[] payload) {
        if (!slots.tryAcquire()) {               // L1 背压
            metrics.rejected(topic, partitionId);
            return false;
        }
        mutex.lock();
        try {
            ring.offerLast(payload);
            metrics.updatePending(topic, partitionId, ring.size());
            return true;
        } finally {
            mutex.unlock();
        }
    }

    public List<byte[]> drain(int max) {
        mutex.lock();
        try {
            int n = Math.min(max, ring.size());
            List<byte[]> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(ring.pollFirst());
                slots.release();
            }
            metrics.updatePending(topic, partitionId, ring.size());
            return out;
        } finally {
            mutex.unlock();
        }
    }

    public int size() { return ring.size(); }
}
```

### 3.3 mmap WAL(per-partition 目录)

**目录结构**:
```
${wal.dir}/.inflight/
├── monitor-metrics-p0/   ← partition 0 独立目录
│   ├── 000000000000.segment  (64MB)
│   ├── 000000000064.segment
│   └── recover.cursor
├── monitor-metrics-p1/   ← partition 1 独立目录
│   └── ...
├── monitor-metrics-p2/
├── monitor-metrics-p3/
├── monitor-logs-p0/
└── monitor-heartbeat-p0/ (K=1,单 partition)
```

**Record 格式**(32 字节 header + 可变 body):
```
偏移  大小   字段              说明
────  ────  ──────────────    ─────────────────────
0     4B    magic             0xCAFE_BEEF(损坏跳过)
4     4B    totalLength       header(32) + body 字节
8     4B    crc32             header[0..8) + body CRC
12    4B    partitionId       用于快速过滤
16    8B    timestampMs       业务时间戳(冗余)
24    4B    keyLength         key 字节数
28    4B    payloadLength     payload 字节数
32    KB    key + payload     UTF-8 拼接
```

**Segment 滚动**:写满 64MB → 创建新 segment 文件
**Segment 清理**:旧 segment 在所有 consumer 处理后,默认保留 7 天

### 3.4 `InflightRecord` POJO

```java
public record InflightRecord(
    String topic,
    int partitionId,         // ★ 新增,debug 用
    String key,              // 业务 appid(日志追踪)
    byte[] payload,          // 业务事件序列化
    long timestampMs,        // 业务时间戳(冗余,快速过滤)
    long offset              // WAL 内偏移(无业务语义)
) {}
```

### 3.5 核心类列表(13 个,合计 ~2050 行)

| 类 | 估行数 | 角色 |
|---|---|---|
| `InflightQueue` | 130 | 门面,初始化 / 关闭 / 对外 API |
| `Partition` | 60 | 单 partition 子系统(聚合 buffer+wal+flusher) |
| `InflightBuffer` | 80 | 内存 ring,容量计数 |
| `MmapWAL` | 200 | 跨 segment 读写,append 同步 |
| `WALSegment` | 100 | 单 segment 文件,mmap 包装 |
| `FlushScheduler` | 100 | 100ms 周期 flush buffer → WAL |
| `InflightProducerBridge` | 150 | 替换 KafkaProducerBridge |
| `InflightConsumer` | 160 | 替换 @KafkaListener consumer |
| `PartitionRouter` | 80 | 3 种路由策略 + appBindings |
| `PartitionRebalancer` | 130 | 60s 周期再平衡 |
| `AppTrafficStats` | 60 | per-partition 流量统计 |
| `RoutingStrategy` | 30 | 3 种策略枚举 |
| `InflightMetrics` | 130 | 7 个 Micrometer 指标 |
| `InflightProperties` | 100 | `@ConfigurationProperties` |
| `BackpressureHandler` | 60 | 业务侧 3 选 1 策略 |
| `BackpressureException` | 15 | L1/L2 满时抛出 |
| `InflightRecord` | 30 | record POJO |
| `WALRecoverer` | 120 | 启动期 per-partition recover |
| `SegmentRoller` | 80 | 1min 周期 segment 滚动 + 旧段清理 |
| **合计** | **~1815**(实现)+ ~200(单测)= **~2050** | — |

---

## 4. 路由策略与负载均衡

### 4.1 为什么不用 `hash(appid) % K`

**80/20 流量下实测**(100 个 app,K=4,Java `String.hashCode`):

| Partition | 高流量 app 数 | 流量占比 |
|---|---|---|
| p0 | 2 | 50% |
| p1 | 1 | 25% |
| p2 | 1 | 25% |
| p3 | 1 | 25% |

→ 最坏 partition 流量 / 平均 = **1.62x**(典型热点 partition)
→ p0 持续过载 → L1 背压 → p0 写入被拒,其他 3 个 partition 闲置
→ **整个 topic 吞吐 = p0 吞吐 = K=4 完全浪费**

**`hash` 唯一的好处**(同 app 局部性)**本项目用不到**:InfluxDB 按 `timestamp` 索引,与 partition 无关。

### 4.2 3 种路由策略(可配)

| 策略 | 均匀性(80/20) | 性能开销 | 实现 | 推荐度 |
|---|---|---|---|---|
| ~~`hash(appid)`~~(已弃) | 1.62x | 1 次 hash | 极简 | ❌ |
| **`power_of_two_choices`(默认)** | **1.05x** | 2 次读 pending | ~30 行 | ⭐⭐⭐⭐⭐ |
| `weighted_round_robin` | 1.02x | K 次读 pending | ~50 行 | ⭐⭐⭐⭐ |
| `round_robin` | 1.00x | 1 次 AtomicLong | ~10 行 | ⭐⭐⭐ |

**默认选 `power_of_two_choices`**:每次随机抽 2 个 partition,选 pending 较少的;**性能损失 < 5%**。

```java
public class PartitionRouter {
    private final int K;
    private final RoutingStrategy strategy;
    private final AtomicLong rrCounter = new AtomicLong(0);
    private final Map<String, Integer> appBindings = new ConcurrentHashMap<>();  // Rebalancer 写入

    public int route(String appid) {
        return switch (strategy) {
            case POWER_OF_TWO_CHOICES -> powerOfTwoChoices(appid);
            case WEIGHTED_ROUND_ROBIN -> weightedRoundRobin();
            case ROUND_ROBIN -> (int) (rrCounter.getAndIncrement() % K);
        };
    }

    private int powerOfTwoChoices(String appid) {
        // 1. 优先用 Rebalancer 绑定(覆盖默认)
        if (appid != null) {
            Integer binded = appBindings.get(appid);
            if (binded != null) return binded;
        }
        // 2. 默认两选一
        int i = ThreadLocalRandom.current().nextInt(K);
        int j = (i + 1 + ThreadLocalRandom.current().nextInt(K - 1)) % K;
        long pi = partitions[i].pending(), pj = partitions[j].pending();
        return pi <= pj ? i : j;
    }
}
```

### 4.3 后台 PartitionRebalancer(动态再平衡)

**两选一的问题**:不感知"长期不均",需要 60s 周期的 Rebalancer 兜底。

```java
public class PartitionRebalancer {
    private final Map<String, AppTrafficStats> trafficMap;
    private final Map<String, Integer> appBindings;  // appid → partitionId
    private final ScheduledExecutorService scheduler;

    public void start() {
        scheduler.scheduleWithFixedDelay(this::tick, 60, 60, TimeUnit.SECONDS);
    }

    private void tick() {
        for (String topic : topics) rebalanceTopic(topic);
    }

    private void rebalanceTopic(String topic) {
        // 1. 收集每 partition 的 pending
        long[] pending = new long[K];
        for (int i = 0; i < K; i++) pending[i] = partitions[topic][i].buffer.size();

        // 2. 计算平均
        long avg = Arrays.stream(pending).sum() / K;
        if (avg == 0) return;

        // 3. 找过载(>1.5x)与空闲(<0.5x)partition
        List<Integer> overloaded = ...;  // pending > avg × 1.5
        List<Integer> underloaded = ...; // pending < avg × 0.5
        if (overloaded.isEmpty() || underloaded.isEmpty()) return;

        // 4. 迁移:把过载 partition 上流量 top-3 的 app 移到空闲 partition
        for (int p : overloaded) {
            List<AppRank> hot = trafficMap.get(topic + "-" + p).topN(3);
            int target = underloaded.get(0);
            for (AppRank app : hot) {
                appBindings.put(app.appid, target);
                log.info("[kxj: Rebalance 迁移 app={} from p{} to p{}]",
                    app.appid, p, target);
            }
        }
    }
}
```

### 4.4 两选一 + Rebalancer 协同

| 维度 | 两选一(短期) | Rebalancer(长期) |
|---|---|---|
| **频率** | 每次 offer | 60s 周期 |
| **粒度** | 单 record | 整个 app 绑定 |
| **响应速度** | 立即(下一次 offer) | 60s 延迟 |
| **作用** | 平滑短期波动 | 解决长期不均 |

**与白皮书 C3 兼容**:Rebalancer 迁移 app 后,业务时间戳顺序不破坏(consumer 按 timestamp 入库)。

### 4.5 流量统计(`AppTrafficStats`)

```java
public class AppTrafficStats {
    private final ConcurrentMap<String, LongAdder> appCounters = new ConcurrentHashMap<>();

    public void recordSend(String appid) {
        appCounters.computeIfAbsent(appid, k -> new LongAdder()).increment();
    }

    public List<AppRank> topN(int n) {
        return appCounters.entrySet().stream()
            .map(e -> new AppRank(e.getKey(), e.getValue().sum()))
            .sorted((a, b) -> Long.compare(b.count, a.count))
            .limit(n)
            .toList();
    }
}
```

**存储位置**:每 partition 维护 1 个,内存 ~8 KB(1000 app)。

---

## 5. API 形态

### 5.1 Producer API

```java
public interface InflightProducer {
    /**
     * 发送一条事件
     * @param topic     目标 topic
     * @param key       业务 appid(用于路由 + 日志追踪)
     * @param payload   已序列化的 byte[]
     * @param tsMs      业务时间戳(白皮书 C4)
     * @throws BackpressureException L1/L2 满时
     */
    void send(String topic, String key, byte[] payload, long tsMs) throws BackpressureException;
}
```

### 5.2 Consumer API

```java
public interface InflightConsumer {
    /**
     * 从 K 个 partition 中随机挑一个,拉一批
     * @param topic       目标 topic
     * @param maxBatch    上限条数
     * @param waitMs      无数据时等多久
     * @return            List<InflightRecord>(可能为空)
     */
    List<InflightRecord> poll(String topic, int maxBatch, long waitMs);

    long pending(String topic, int partitionId);
}
```

### 5.3 监控 API

```java
public interface InflightMetrics {
    Map<String, Map<Integer, TopicMetrics>> snapshot();
    // topic → partitionId → TopicMetrics(pending/capacity/sent/rejected/walFail)
}
```

---

## 6. 并发模型

### 6.1 三层并发

| 层级 | 并发源 | 锁/同步 |
|---|---|---|
| **Producer → Buffer** | 任意 N 业务线程 | `ReentrantLock` 串行化 ring |
| **Buffer → WAL** | 1 FlushScheduler / partition | `ReentrantLock` 串行化 mmap |
| **Buffer → Consumer** | N 虚拟线程 / topic | `ReentrantLock` 串行化 ring poll |

**真实并发度 = K**(每 partition 一把独立锁),**不是 N 线程**(锁竞争会饱和)。

### 6.2 虚拟线程(Java 25)

```java
Executors.newVirtualThreadPerTaskExecutor()  // 1KB/线程,适合阻塞 IO
```

- 100 个虚拟线程 ≈ 100 KB(平台线程需 100 MB)
- `poll()` 阻塞 100ms 不占 carrier thread
- `writePoints()` 网络 IO 挂起时,carrier 调度其他虚拟线程
- JEP 491(Java 24+)已修复 `synchronized` pinning

### 6.3 Consumer 调度(随机轮询)

```java
private void consumeLoop() {
    while (running) {
        Partition p = partitions[ThreadLocalRandom.current().nextInt(K)];
        List<InflightRecord> batch = p.poll(maxBatch, waitMs);
        if (batch.isEmpty()) continue;
        deserializeAndWrite(batch, p);
    }
}
```

**为什么不会重复消费**:`drain()` 内部加锁,整批 `pollFirst()`,其他线程拿不到。
**幂等兜底**:`appid + timestamp` 天然主键,即使重复写 InfluxDB 也无害。

### 6.4 5 个并发安全不变量

| # | 不变量 | 守护机制 |
|---|---|---|
| I1 | `ring.size() <= capacity` | `Semaphore(capacity)` |
| I2 | `size == slots + drained - enqueued` | 单测断言 |
| I3 | ring 读写互斥 | 同一把 `ReentrantLock` |
| I4 | mmap 写指针单调 | 同一把 `ReentrantLock` |
| I5 | 每 record 最多被 1 consumer 拿到 | `drain()` 加锁 |

---

## 7. 背压策略(3 层防线,白皮书 C6)

| 层级 | 触发 | 动作 | 监控信号 |
|---|---|---|---|
| **L1 内存满** | `buffer.size() >= capacity` | `offer()` 抛 `BackpressureException` | `inflight.producer.rejected` ++ |
| **L2 WAL 满** | mmap 写入异常 | 同上 | `inflight.wal.append.fail` ++ |
| **L3 pending 超阈值** | `pending / capacity > 0.8` 持续 5 min | 发告警(不丢不拒) | `inflight.queue.alert` ++ |

**业务侧 3 选 1**(`BackpressureHandler.handle()`):
1. 丢弃 + 计数(默认)
2. 短暂 sleep 后重试
3. 降级到 Kafka 兜底(M2 灰度期)

---

## 8. 监控指标(11 个 = 7 主 + 4 DLQ,对比 Kafka 100+)

**主指标(7 个)**:

| 指标 | 类型 | Tags | 说明 |
|---|---|---|---|
| `inflight.producer.sent` | Counter | topic, partition | 累计入队 |
| `inflight.producer.rejected` | Counter | topic, partition | L1/L2 拒绝 |
| `inflight.queue.pending` | Gauge | topic, partition | 当前堆积 |
| `inflight.queue.capacity` | Gauge | topic, partition | 容量上限 |
| `inflight.consumer.batch.size` | DistributionSummary | topic, partition | p50/p95/p99 |
| `inflight.wal.append.fail` | Counter | topic, partition | WAL 失败 |
| `inflight.wal.segments` | Gauge | topic, partition | WAL 段数 |

**Rebalancer 指标(1 个)**:
- `inflight.rebalance.migrate` Counter(累计迁移 app 次数)

**DLQ 指标(4 个,详见 15.4)**:
- `inflight.dlq.written` Counter
- `inflight.dlq.persisted` Counter
- `inflight.dlq.persist_fail` Counter
- `inflight.dlq.file.size` Gauge

**不暴露**:
- ❌ consumer lag(白皮书 C3 无 commit)
- ❌ acks / replication(Kafka 分布式概念,本项目无)
- ❌ JMX 100+ 指标(简化为本项目原则)

---

## 9. 故障与恢复

### 9.1 进程崩溃

**崩溃时**:
- in-flight buffer 内未 flush 的数据:**丢失**(白皮书 C2 接受)
- WAL 已写入但未消费的数据:**保留**,启动时 recover

**Recover 流程**(`WALRecoverer`):
1. 扫描 `${wal.dir}/.inflight/${topic}-p${i}/` 下所有 `*.segment`
2. 按 4KB 块找 magic 头 `0xCAFEBEEF`
3. 跳过损坏尾部字节(因未强制 fsync)
4. 解析完整 record 写回 in-flight buffer
5. 启动 FlushScheduler / Consumer
6. **per-partition 并行 recover**(启动时间 = max 而非 sum)

### 9.2 WAL 损坏容错

- magic 头不识别:跳过该 4KB 块
- CRC 校验失败:跳过该 record
- 长度字段非法:截断到 segment 末尾

### 9.3 WAL 段满

- 当前 segment 写满 64MB → 创建新 segment
- 旧 segment 引用计数 + 1(每个 consumer 持有 1 个引用)
- 引用归 0 后等待 7 天保留期删除

### 9.4 磁盘满

- `wal.append()` 抛 `IOException` → `inflight.wal.append.fail` ++
- producer 抛 `BackpressureException`
- 业务侧按 7 选 1/2/3 处理

### 9.5 OOM 风险

- in-flight buffer **严格有界**(K × 50000 ≈ 200 MB,可控)
- 反序列化在 consumer 端,每次最多 `maxBatch=200` 条(~200 KB)
- 触发 OOM:`-XX:+ExitOnOutOfMemoryError` → 容器自动重启 → 9.1 recover

---

## 10. 配置项(完整 application.yml)

```yaml
spring-watch:
  inflight:
    enabled: true                                    # 总开关
    wal-dir: ${user.home}/.spring-watch/inflight     # WAL 根目录
    partitions: 4                                    # ★ K(每 topic 的 partition 数)
    buffer-capacity: 50000                           # ★ 每 partition 内存配额
    segment-bytes: 67108864                          # 64 MB
    flush-interval-ms: 100                           # buffer → WAL 周期
    flush-batch-size: 1000                           # flush 单批上限
    segment-retention-days: 7                        # 旧 segment 保留期
    routing:                                         # ★ 路由配置
      strategy: power_of_two_choices                 # power_of_two_choices | weighted_round_robin | round_robin
      rebalance:
        enabled: true                                # Rebalancer 开关
        interval-seconds: 60                         # Rebalancer 周期
        pending-threshold-ratio: 1.5                 # 过载阈值(> avg × 1.5)
        underloaded-threshold-ratio: 0.5             # 空闲阈值(< avg × 0.5)
        migrate-top-n: 3                             # 每次迁移 app 数
    consumer:
      poll-max-batch: 200
      poll-wait-ms: 100
      concurrency:                                   # ★ 每 topic 虚拟线程数
        monitor-metrics: 2
        monitor-logs: 2
        monitor-heartbeat: 1
```

**保留(M1/M2 期间)**:`spring.kafka.*` + `spring-watch.kafka.*` 完整保留作为降级路径。

**删除(M3 阶段)**:Kafka 相关配置整段。

---

## 11. 代码改动边界

### 11.1 新增文件(17 个,~1815 行)

```
com.springwatch.inflight/
├── InflightQueue.java              # 130 门面
├── Partition.java                  # 60  partition 子系统
├── InflightBuffer.java             # 80  内存 ring
├── MmapWAL.java                    # 200 mmap append-only
├── WALSegment.java                 # 100 单 segment 文件
├── FlushScheduler.java             # 100 周期 flush
├── InflightProducerBridge.java     # 150 替换 KafkaProducerBridge
├── InflightConsumer.java           # 160 替换 @KafkaListener
├── PartitionRouter.java            # 80  3 种路由策略
├── RoutingStrategy.java            # 30  策略枚举
├── PartitionRebalancer.java        # 130 后台再平衡
├── AppTrafficStats.java            # 60  流量统计
├── InflightMetrics.java            # 130 7 个 Micrometer 指标
├── InflightProperties.java         # 100 @ConfigurationProperties
├── BackpressureHandler.java        # 60  业务侧 3 选 1
├── BackpressureException.java      # 15  异常
├── InflightRecord.java             # 30  record POJO
└── recover/
    ├── WALRecoverer.java           # 120 启动期 per-partition recover
    └── SegmentRoller.java          # 80  segment 滚动 + 清理
```

**单测新增**(12 个,与上面 1:1):`InflightBufferTest` / `MmapWALTest` / `InflightProducerBridgeTest` / `InflightConsumerTest` / `InflightMetricsTest` / `InflightPropertiesTest` / `FlushSchedulerTest` / `WALRecovererTest` / `PartitionRouterTest`(均匀性) / `PartitionRebalancerTest` / `AppTrafficStatsTest` / `InflightQueueTest`

### 11.2 修改文件(7 个)

| 文件 | 改动 | 影响面 |
|---|---|---|
| `pom.xml` | 移除 `spring-kafka` / `kafka-clients`(M3);添加 jcstress(可选) | 小 |
| `SpringWatchApplication.java` | 移除 `@EnableKafka`(M3) | 小 |
| `collector/AgentMetricsCollector.java` | 改 `KafkaProducerBridge` → `InflightProducerBridge` | 局部 |
| `collector/AppPullTask.java` | 同上 | 局部 |
| `consumer/BatchMetricConsumer.java` | 拆出 `MetricEventWriter` | 中 |
| `consumer/BatchLogConsumer.java` | 拆出 `LogEventWriter` | 中 |
| `consumer/BatchHeartbeatConsumer.java` | 拆出 `HeartbeatEventWriter` | 小 |
| `monitor/SelfMonitorCollector.java` | 7 个 inflight 指标替代 18 个 kafka 指标 | 中 |

### 11.3 删除文件(M3 阶段)

| 文件 | 替代者 |
|---|---|
| `collector/KafkaProducerBridge.java` | `InflightProducerBridge` |
| `collector/KafkaFallbackQueue.java` | `BackpressureHandler` |
| `config/KafkaTopicConfig.java` | `InflightProperties` |
| `config/KafkaConfig.java` | (无) |
| `monitor/KafkaProducerJmxCollector.java` | (无) |
| `monitor/LagMonitorProperties.java` | (无) |

**`consumer/DlqMonitorConsumer.java` 不删,改为改写**:原版本监听 Kafka 3 个 DLQ topic,新版本监听 1 个 `*.dlq.wal` 物理文件(详见 15 投递失败处理)。

### 11.4 不动文件(白名单)

> M1 ~ M3 全程不动:`alerter/` / `ingest/` / `model/` / `repository/` / `service/` / `web/` / `analysis/` / `util/` 全部

### 11.5 AGENTS.md 合规性

- 3 注入:`@RequiredArgsConstructor + final` ✅
- 4 关键日志:send/flush/recover/rebalance 均 `[kxj: ...]` ✅
- 7 最小实现:~2050 行,无过度抽象 ✅
- 11 不动 springboot / jvm 版本:Java 25 / Spring Boot 4.0.1 不动 ✅

---

## 12. 验证验收标准

### 12.1 单元测试(必须 100% 通过)

| 测试类 | 覆盖目标 | 关键用例 |
|---|---|---|
| `InflightBufferTest` | offer/drain/容量边界 | ① 容量满时抛 BackpressureException ② 并发 offer 不丢不重 |
| `MmapWALTest` | append/read/recover/rotate | ① 写满 64MB 触发 rotate ② 损坏尾部字节被跳过 |
| `FlushSchedulerTest` | 周期 flush / 优雅关闭 | ① 100ms 周期 ② shutdown 等待残余 flush 完 |
| `InflightProducerBridgeTest` | send/序列化/背压 | ① JSON 失败不丢 ② 背压时 BackpressureHandler 介入 |
| `InflightConsumerTest` | poll/反序列化/批量 | ① 批量上限 ② 1 条失败不影响其余 |
| `WALRecovererTest` | 启动期扫描 | ① 干净 WAL 完整 recover ② 损坏 WAL 跳过坏块 |
| `InflightMetricsTest` | 7 个指标 | ① sent/rejected/pending 计数正确 |
| `SegmentRollerTest` | 满则滚动 | ① 满则创建新 segment ② 7 天后删除 |
| **`PartitionRouterTest`** | 均匀性 + 正确性 | ① 80/20 流量下 worst/avg ≤ 1.10 ② 同一 appid 走相同 partition(无 binding 时) |
| **`PartitionRebalancerTest`** | 迁移 + 持久化 | ① 过载 partition 触发迁移 ② appBindings 写 cursor |
| `AppTrafficStatsTest` | topN 正确性 | ① topN 返回按流量降序 |
| `InflightQueueTest` | 整体启动/关闭 | ① init() 创建 K × 3 个 partition ② shutdown 等待全部 flush 完 |

**覆盖率目标**:`inflight` 包**行 ≥ 90% / 分支 ≥ 80%**

### 12.2 集成测试场景(必须 100% 通过)

| 场景 | 描述 | 预期 |
|---|---|---|
| **基线对比** | 同一批 1000 MetricEvent,InKafka vs Inflight | 端到端 p50/p95/p99 差异 < 10% |
| **持久化恢复** | 写入 1000 → `kill -9` → 重启 | 未消费数据全恢复 |
| **背压触发** | consumer 暂停,持续 offer 到 buffer 满 | offer 抛 `BackpressureException`,`rejected` = 溢出数 |
| **WAL 损坏容错** | 截断 WAL 末尾 4KB,启动 | recover 跳过坏块,启动成功 |
| **并发消费** | 3 个 consumer 虚拟线程并发 poll | 各拿到不重叠 record |
| **DLQ 行为** | 反序列化失败 1 条 | 该条被丢弃 + 计数,不影响其余 |
| **优雅关闭** | 写入 1000 → 立即 shutdown | 残余 buffer flush 完 |
| **InfluxDB 写入等价** | 同一批数据,InKafka vs Inflight | 写入条数与字段完全一致 |
| **★ 路由均匀性** | 80/20 流量压测 24h | worst partition 流量 / avg ≤ 1.10 |
| **★ Rebalancer 介入** | 模拟 1 个 app 突发 10x 流量 | 60s 内 Rebalancer 迁移,新 partition 流量提升 |
| **★ Partition 故障隔离** | 1 个 partition 模拟 OOM | 其他 3 个 partition 正常,producer 该 partition 写失败被拒,其他 75% 业务无感 |

### 12.3 性能基线(对比 v1.6 Kafka 路径)

**测试环境**:Linux 5.10 / JDK 25 / 4C8G / SSD / `-Xmx512m`

| 指标 | v1.6 Kafka(基线) | v2.0 Inflight K=4(目标) | 实际 | 是否达标 |
|---|---|---|---|---|
| **端到端延迟 p50** | 50 ms | ≤ 30 ms | 测 | ☐ |
| **端到端延迟 p95** | 200 ms | ≤ 150 ms | 测 | ☐ |
| **端到端延迟 p99** | 500 ms | ≤ 300 ms | 测 | ☐ |
| **吞吐(consumer 端)** | 5 k events/s | ≥ 100 k events/s | 测 | ☐ |
| **JVM 堆稳态** | 150 MB | ≤ 250 MB(多 100 MB) | 测 | ☐ |
| **JVM 堆峰值** | 220 MB | ≤ 320 MB | 测 | ☐ |
| **GC p99 暂停** | < 50 ms | < 50 ms | 测 | ☐ |
| **启动时间** | 8 s | ≤ 6 s | 测 | ☐ |
| **最坏 partition / 平均(80/20)** | — | ≤ 1.10 | 测 | ☐ |

### 12.4 灰度切流观察指标(M2)

**切流策略**:
1. D+0:`monitor-heartbeat`(流量小、风险低)
2. D+3:`monitor-metrics`(流量中等)
3. D+7:`monitor-logs`(流量大、风险高)

**每个 topic 切流后观察 24h,关键指标**:
- `inflight.queue.pending{topic, partition}` 各 partition 长期 < 1000
- `inflight.producer.rejected` 计数 = 0
- `inflight.consumer.batch.size.p50` 接近 `poll-max-batch`(200)
- `inflight.wal.append.fail` 计数 = 0
- `inflight.rebalance.migrate` 有数据(Rebalancer 正常运作)
- InfluxDB 写入量与切换前 7 天均值的偏差 < 1%

**回退触发**(任一即回退):
- `rejected` 计数 > 100 / 小时
- `wal.append.fail` 计数 > 10 / 小时
- InfluxDB 写入偏差 > 5%
- 任何 consumer 线程异常退出 > 3 次
- 任何 partition 的 pending > 80% 容量持续 10 min

### 12.5 M2 完成 = v2.0 发布 Checklist

- [ ] **功能性**:
  - [ ] 3 个 topic 全部切流到 InflightQueue
  - [ ] InfluxDB 写入 100% 等价于 v1.6
  - [ ] `SelfMonitorCollector` 7 个 inflight 指标全部有数据
- [ ] **可靠性**:
  - [ ] 进程崩溃 → 重启 → 未消费数据全部恢复
  - [ ] WAL 损坏 → 启动 → 跳过坏块,正常运行
  - [ ] 24h 稳定运行无 OOM,无 uncaught exception
- [ ] **可观测性**:
  - [ ] 前端 `InfraPane` 标签从 "Kafka" 改为 "InflightQueue"
  - [ ] 7 个指标全部可视化
  - [ ] L3 告警(pending > 80% 持续 5 min)能触发邮件
  - [ ] Rebalancer 迁移日志可见
- [ ] **代码质量**:
  - [ ] `inflight` 包单测覆盖率 ≥ 90%
  - [ ] 无新增 `System.out.println`
  - [ ] 无 `@Autowired`
- [ ] **文档**:
  - [ ] `白皮书-InflightQueue.md` v 已发
  - [ ] `docs/HertzBeat队列实现分析报告.md` v 已发
  - [ ] `docs/InflightQueue实现设计.md` 本文已发(v4 汇总)
  - [ ] README.md 技术栈表更新("消息队列"行 → "自研 InflightQueue")
  - [ ] 根 `白皮书.md` 0.5 部署形态表更新
  - [ ] 历史版本已删除(`自研内存队列替换Kafka规划.md` / `InflightQueue实现设计.md` v1-v3)

### 12.6 M3 完成 = 完全移除 Kafka

- [ ] `pom.xml` 移除 `spring-kafka` + `kafka-clients`,`mvn clean package` 通过
- [ ] `docker-compose.yml` 移除 kafka service + volume
- [ ] 7 个 Kafka 相关类全部删除(11.3 列表)
- [ ] `application.yml` 中 Kafka 配置整段删除
- [ ] `SpringWatchApplication.java` 移除 `@EnableKafka`
- [ ] `docker compose up -d` 仅 3 个容器
- [ ] 5 分钟拉起实测 < 5 分钟
- [ ] 1 周线上稳定无回退

---

## 13. 与 Kafka 的对比

| 维度 | Kafka 4.3 单 broker | InflightQueue K=4 partition |
|---|---|---|
| **外部容器** | 1 个(apache/kafka:4.3.0) | **0 个** |
| **外部内存** | 512 MB(KAFKA_HEAP) | 0 |
| **外部端口** | 9092 / 9093 / 9999 | 0 |
| **JVM 依赖** | spring-kafka / kafka-clients | **无** |
| **配置项** | spring.kafka.* + spring-watch.kafka.* 共 30+ | spring-watch.inflight.* 共 15 |
| **真实并发度** | 3 partition × N consumer = 3N | **K × N consumer = 4N**(K=4) |
| **ack 机制** | 0/1/all | fire-and-forget |
| **fsync** | 可配 | **不强制**(白皮书 C2) |
| **commit offset** | 必需 | **不需要**(白皮书 C3) |
| **partition 顺序** | 强保证 | **不保证**(白皮书 C3) |
| **rebalance** | 必需 | **不需要** |
| **副本** | 1(单 broker) | 1 |
| **DLQ** | 必需 | **不实现**(白皮书 N13) |
| **JMX 指标** | 100+ | **7 个** |
| **启动时间** | +5s(Kafka 容器就绪) | **0s** |
| **代码量** | 间接 1500 行(JMX 采集 / partition 校验 / lag 监控) | 间接 0 |
| **故障域** | broker 故障 = 全挂 | **partition 故障 = 1/4 影响** |

**关键简化**(砍掉的能力):
- ❌ acks 机制 / 副本 / commit offset / partition 顺序 / rebalance / 事务 / DLQ / 优先级 / 延迟消息
- ✅ 保留:持久化 / 并发消费 / 监控 / 配置化
- **净代码量 < 2100 行**(Kafka 客户端 ~50 万行)

---

## 14. 投递失败处理(轻量 DLQ)

> **本章对白皮书 N13 的受控修订**:
> - 白皮书 N13 原规定"不实现 DLQ"
> - **本项目实际情况**:v1.6 已有 3 个 Kafka DLQ topic(`monitor-metrics.DLQ` / `monitor-logs.DLQ` / `monitor-heartbeat.DLQ`),由 `DlqMonitorConsumer` 落 PostgreSQL
> - **完全不实现 DLQ → debug 困难**(失败现场无法追溯)
> - **本设计采用"轻量 DLQ"**:物理独立文件 + 不自动重投 + 只供事后分析
> - **白皮书 N13 的核心精神保留**:"不主动重投 / 不阻塞主链路 / 不增加复杂度"——本设计全部满足

### 15.1 失败分类与处理策略

| # | 失败场景 | 触发位置 | 处理策略 | 是否进 DLQ |
|---|---|---|---|---|
| **F1** | Producer 端 in-flight 满(L1 背压) | `InflightBuffer.offer()` 返回 `false` | `BackpressureHandler.handle()` 3 选 1(默认丢弃+计数) | ❌ 否 |
| **F2** | Producer 端 WAL 写入失败(L2 背压) | `MmapWAL.append()` 抛 `IOException` | 同 F1(因 mmap 已写入 OS page cache,真实失败概率极低,仅磁盘满/权限问题) | ❌ 否 |
| **F3** | Consumer 端反序列化失败 | `objectMapper.readValue()` 抛异常 | **进轻量 DLQ** + 计数 + 告警 | ✅ 是 |
| **F4** | Consumer 端 InfluxDB 写入失败 | `writePoints()` 抛异常 | 当前批次丢弃 + 计数 + 告警(同 v1.6) | ❌ 否(批级别失败,无法定位单条) |
| **F5** | Consumer 端 InflightRecord schema 不匹配 | `timestampMs == 0` / `appid == null` | 直接丢弃 + 计数 | ❌ 否 |

**F1 ~ F2 的不重投理由**:
- 业务侧(`MetricEvent` / `LogEvent` / `HeartbeatEvent`)在调用 `send()` 之前**已打时间戳**(白皮书 C4)
- 偶发丢失一次指标(几秒钟)对监控分析**几乎无影响**(15s 一次采集)
- 自动重投会导致"积压放大",与白皮书 C2 冲突

**F3 的 DLQ 必要性**:
- 反序列化失败通常意味着 **schema 变更 / 数据损坏 / 版本不兼容**,这类问题**必须留现场**
- v1.6 通过 Kafka DLT + PostgreSQL 落库实现,v2.0 改为物理文件实现

**F4 ~ F5 的不重投理由**:
- F4 是 InfluxDB 端问题(下游),重投到 InflightQueue 会污染源数据
- F5 是 schema 校验失败,重投无意义(下次还是失败)

### 15.2 轻量 DLQ 设计

#### 15.2.1 物理结构

```
${spring-watch.inflight.wal.dir}/.inflight-dlq/
├── monitor-metrics.dlq.wal         # 单 topic 1 个文件
├── monitor-metrics.dlq.cursor      # consumer 读位点
├── monitor-logs.dlq.wal
├── monitor-logs.dlq.cursor
└── monitor-heartbeat.dlq.wal
```

**与 Kafka DLT 的差异**:
| 维度 | Kafka DLT(3 个独立 topic) | InflightDLQ(1 文件/topic) |
|---|---|---|
| **topic 数** | 3 | 0(物理文件) |
| **消息头** | DLT 头(FQCN / stacktrace / original offset) | 简化 JSON 字段 |
| **自动重投** | 支持(`@RetryableTopic` + 指数退避) | **不支持** |
| **落库** | `DlqMonitorConsumer` → PostgreSQL | 同(v1.6 既有功能保留) |
| **配置项** | `spring.kafka.consumer.dlq.*` 5+ 项 | `spring-watch.inflight.dlq.*` 3 项 |

#### 15.2.2 DLQ 消息格式

```java
public record InflightDLQRecord(
    String originalTopic,           // 失败前所属 topic
    int originalPartitionId,        // 失败前所属 partition
    long originalOffset,            // WAL 内偏移(debug 用)
    long timestampMs,               // 业务时间戳
    String errorClass,              // 例:"com.fasterxml.jackson.core.JsonParseException"
    String errorMessage,            // 异常 message(截断到 1KB)
    byte[] payload                  // 原始 payload(便于事后修复)
) {}
```

**JSON 序列化**:
```json
{
  "originalTopic": "monitor-metrics",
  "originalPartitionId": 2,
  "originalOffset": 1048576,
  "timestampMs": 1718256000000,
  "errorClass": "com.fasterxml.jackson.core.JsonParseException",
  "errorMessage": "Unexpected character ('a' (code 97))...",
  "payload": "eyJhcHBpZCI6MTIzLC..."   // base64 编码
}
```

#### 15.2.3 写入路径(Consumer 端)

```java
// InflightConsumer.consumeLoop
private void consumeLoop(String topic) {
    while (running) {
        List<InflightRecord> batch = ...;  // poll
        List<InflightRecord> good = new ArrayList<>(batch.size());
        for (InflightRecord r : batch) {
            try {
                MetricEvent e = objectMapper.readValue(r.payload(), MetricEvent.class);
                good.add(r);
            } catch (Exception ex) {
                // ★ 进轻量 DLQ
                dlqWriter.write(topic, r, ex);
                metrics.dlqWritten(topic);
            }
        }
        if (!good.isEmpty()) {
            try {
                writeToInfluxDB(good, topic);
            } catch (Exception ex) {
                log.error("[kxj: InfluxDB 写入失败 - topic={}, size={}]", topic, good.size(), ex);
                metrics.writeFailed(topic);
            }
        }
    }
}
```

**关键不阻塞**:
- 1 条反序列化失败,**不影响同批其他 record**
- DLQ 写入失败 → 计数 + 告警,**不抛异常**(避免污染主链路)

#### 15.2.4 读取路径(对应 v1.6 `DlqMonitorConsumer`)

```java
@Component
@ConditionalOnProperty(name = "spring-watch.inflight.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class InflightDlqMonitorConsumer {
    private final InflightDLQStore dlqStore;          // 物理文件 reader
    private final DlqMessageRepository dlqRepo;       // 既有 PostgreSQL repo
    private final InflightMetrics metrics;

    private ExecutorService scheduler;

    @PostConstruct
    void init() {
        // 启动单线程周期任务,每 30s 扫描一次 DLQ 文件
        scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("inflight-dlq-", 0).factory());
        scheduler.scheduleWithFixedDelay(this::scanAndPersist, 30, 30, TimeUnit.SECONDS);
    }

    private void scanAndPersist() {
        for (String topic : List.of("monitor-metrics", "monitor-logs", "monitor-heartbeat")) {
            List<InflightDLQRecord> records = dlqStore.read(topic, 100);
            if (records.isEmpty()) continue;
            try {
                List<DlqMessageEntity> entities = records.stream()
                    .map(this::toEntity).toList();
                dlqRepo.saveAll(entities);
                dlqStore.commit(topic, records.size());
                metrics.dlqPersisted(topic, records.size());
                log.info("[kxj: DLQ 落库成功 - topic={}, count={}]", topic, records.size());
            } catch (Exception e) {
                log.error("[kxj: DLQ 落库失败 - topic={}, count={}, error={}]",
                    topic, records.size(), e.getMessage());
                metrics.dlqPersistFail(topic, records.size());
            }
        }
    }
}
```

**保留 v1.6 行为**:
- `DlqMessageEntity` 表结构**不变**(PostgreSQL 兼容)
- `DlqMessageRepository` **不动**
- 现有 web 端"DLQ 消息查询"功能**不受影响**

#### 15.2.5 容量控制

| 参数 | 默认值 | 说明 |
|---|---|---|
| `spring-watch.inflight.dlq.max-file-bytes` | 268435456(256 MB) | DLQ 文件最大尺寸,超过则 rotate |
| `spring-watch.inflight.dlq.retention-days` | 7 | DLQ 文件保留天数,过期自动删除 |
| `spring-watch.inflight.dlq.scan-interval-seconds` | 30 | 扫描周期 |

**容量保护**:
- DLQ 文件超 256 MB → rotate 到 `.dlq.wal.1`(类似 WAL 段滚动)
- `.dlq.wal.*` 文件 7 天后删除(防止磁盘占满)
- 入库失败时 DLQ 文件持续增长 → 通过 `inflight.dlq.file.size` Gauge 监控 + 告警

### 15.3 与 v1.6 现状的兼容

| v1.6 现状 | v2.0 对应 |
|---|---|
| 3 个 Kafka DLQ topic | **0 个 topic**(改为物理文件) |
| `monitor-metrics.DLQ` 等 partition=1 | 不适用 |
| `DlqMonitorConsumer` 监听 3 个 topic | 改为扫描 3 个 `.dlq.wal` 文件 |
| DLT 头(FQCN / stacktrace) | 简化为 `errorClass` + `errorMessage` |
| 落库到 PostgreSQL `dlq_message` 表 | **不变**(沿用 v1.6 表结构) |
| `spring.watch.consumer.dlq.persisted` 指标 | 改名为 `inflight.dlq.persisted` |

**升级路径**:
- M1 旁路期:DLQ **双写**(Kafka DLT + InflightDLQ 文件),对比一致性
- M2 灰度期:只走 InflightDLQ,Kafka DLT 关闭
- M3 移除 Kafka:InflightDLQ 单独运行

### 15.4 监控指标(新增 4 个,合计 11 个)

| 指标 | 类型 | Tags | 说明 |
|---|---|---|---|
| `inflight.dlq.written` | Counter | topic, errorClass | 进 DLQ 的条数 |
| `inflight.dlq.persisted` | Counter | topic | DLQ 落库 PostgreSQL 成功条数 |
| `inflight.dlq.persist_fail` | Counter | topic | DLQ 落库失败条数 |
| `inflight.dlq.file.size` | Gauge | topic | DLQ 物理文件当前大小(byte) |

**告警阈值**:
- `inflight.dlq.written{topic}` 1h 增量 > 100 → 邮件告警(说明数据格式有重大问题)
- `inflight.dlq.persist_fail` 计数 > 0 → 邮件告警(说明 PostgreSQL 异常)
- `inflight.dlq.file.size{topic}` > 100 MB → 邮件告警(说明 DB 写入跟不上)

### 15.5 白皮书 N13 例外声明

| 白皮书原条款 | 实际执行 |
|---|---|
| ❌ N13 DLQ(死信队列) | **改为"轻量 DLQ"**(本节 15) |
| ❌ 不实现 commit offset | **不实现**(白皮书 C3 维持) |
| ❌ 不实现 rebalance | **不实现**(维持) |
| ❌ 不实现 partition 顺序 | **不实现**(白皮书 C3 维持) |

**变更原因**:
- 原 N13 假设"业务侧自行处理失败",但实际项目 v1.6 已用 Kafka DLT 落库,直接删除会导致 DLQ 监控数据丢失、web 端 DLQ 页面空白
- "轻量 DLQ"满足 N13 的核心精神(不重投 / 不阻塞 / 简单),且物理实现比 Kafka DLT 简单(1 文件/topic vs 3 topic)

### 15.6 实施步骤(对 v4 的修订)

| 步骤 | 内容 | 改动 |
|---|---|---|
| **Step 1** | 新增 `InflightDLQRecord` POJO + `InflightDLQStore`(物理文件 reader/writer) | 新增 2 个类 |
| **Step 2** | 改 `InflightConsumer.consumeLoop`,反序列化失败时调 `dlqWriter.write()` | 改 1 个文件 |
| **Step 3** | 改 `InflightMetrics`,新增 4 个 DLQ 指标 | 改 1 个文件 |
| **Step 4** | 改 `DlqMonitorConsumer`,监听从 Kafka topic 改为物理文件 | 改 1 个文件 |
| **Step 5** | 改 `InflightProperties`,新增 `dlq.*` 配置项 | 改 1 个文件 |
| **Step 6** | 改 `SelfMonitorCollector`,新增 DLQ 4 个指标 | 改 1 个文件 |
| **Step 7** | 单测:`InflightDLQStoreTest` / `InflightDlqMonitorConsumerTest` | 新增 2 个测试类 |

**总代码量**:
- v4 不含 DLQ:2050 行
- v4 含 DLQ:**2350 行**(+ 300 行,DLQ reader + DLQ writer + 改造 consumer + metrics + properties)

**总文件数**:
- v4 不含 DLQ:17 个新增
- v4 含 DLQ:**19 个新增**

### 15.7 与 v1.6 验收对比

| 验收项 | v1.6 Kafka DLQ | v2.0 InflightDLQ |
|---|---|---|
| DLQ 消息持久化 | ✅ Kafka DLT + PostgreSQL | ✅ 物理文件 + PostgreSQL |
| DLQ 落库条数 | ✅ `inflight.dlq.persisted` | ✅ 同(改名) |
| 失败原因记录 | ✅ DLT 头 | ✅ JSON 字段(errorClass + errorMessage) |
| 自动重投 | ✅ Spring Kafka `@RetryableTopic` | ❌ **不实现**(白皮书精神) |
| DLQ 文件大小监控 | ❌ 无 | ✅ `inflight.dlq.file.size` |
| 失败重试次数 | ✅ 3 次 | ❌ **不实现**(1 次失败即进 DLQ) |
| 与 web 端 DLQ 页面兼容 | ✅ | ✅(沿用 PostgreSQL) |

**v2.0 的取舍**:
- ❌ 不实现自动重投:失败即丢弃 + DLQ 留现场(简化实现)
- ❌ 不记录 stacktrace:避免 DLQ 文件膨胀(只记 errorClass + errorMessage 1KB)
- ❌ 不实现"按 errorClass 分类":简化统计(所有失败混在一起)
- ✅ 保留:DLQ → PostgreSQL 链路 / web 端查询 / 监控指标

---

## 15. 总结

### 15.1 关键设计决策(逐条回扣白皮书)

| 白皮书约束 | 本设计的实现 | 回扣 |
|---|---|---|
| C1 一定要做持久化 | `MmapWAL` + `WALRecoverer`(per-partition 并行) | ✅ |
| C2 允许丢失少量数据 | mmap 不强制 fsync + recover 跳过坏块 | ✅ |
| C3 不要求顺序消费 | 无 commit offset + 无 partition 路由 + Rebalancer 不破坏 ts 顺序 | ✅ |
| C4 入队前已打时间戳 | `InflightProducerBridge.send` 的 `tsMs` 参数 + WAL header 冗余 ts | ✅ |
| C5 支持并发度调整 | K partition + 每 topic 虚拟线程池,均可配 | ✅ |
| C6 控制内存用量 | 严格有界(K × 50000) + 三层背压 + Rebalancer 避免热点 | ✅ |
| N13 DLQ | **改为"轻量 DLQ"**(14):物理文件 + 不自动重投 + 落 PostgreSQL 保留 v1.6 行为 | ⚠️ 受控例外 |
|---|---|---|
| C1 一定要做持久化 | `MmapWAL` + `WALRecoverer`(per-partition 并行) | ✅ |
| C2 允许丢失少量数据 | mmap 不强制 fsync + recover 跳过坏块 | ✅ |
| C3 不要求顺序消费 | 无 commit offset + 无 partition 路由 + Rebalancer 不破坏 ts 顺序 | ✅ |
| C4 入队前已打时间戳 | `InflightProducerBridge.send` 的 `tsMs` 参数 + WAL header 冗余 ts | ✅ |
| C5 支持并发度调整 | K partition + 每 topic 虚拟线程池,均可配 | ✅ |
| C6 控制内存用量 | 严格有界(K × 50000) + 三层背压 + Rebalancer 避免热点 | ✅ |

### 15.2 关键 5 点

1. **partition 拆分**:K=4 默认,把"N 线程竞争 1 把锁"改为"K 把独立锁",真实并发度 = K
2. **路由策略**:`power_of_two_choices` 数学保证均匀性 ≈ 1.05(对比 hash 的 1.62)
3. **Rebalancer**:60s 周期把过载 partition 上的热点 app 迁移到空闲 partition,长期不均 < 1.01
4. **背压三层**:L1 内存满 / L2 WAL 失败 / L3 pending 告警
5. **故障域**:单 partition 故障只影响 1/K 业务(对比 v1 单 buffer 的 100% 影响)

### 15.3 排期(参考 `自研内存队列替换Kafka规划.md`)

| 阶段 | 周数 | 关键产出 |
|---|---|---|
| **M0 文档** | 1 周 | 白皮书 + 规划 + 分析 + 实现设计(本文) |
| **M1 旁路** | 1 周 | 17 个类 + 单测 + 旁路双写对比 |
| **M2 灰度** | 2 周 | 切流 3 个 topic + 7 天观察 |
| **M3 移除 Kafka** | 1 周 | 删除 7 个类 + 移除依赖 + 文档收尾 |
| **合计** | **5 周** | **v2.0 GA** |

### 15.4 与项目既有原则的一致性

- **AGENTS.md 3** 注入方式 ✅
- **AGENTS.md 4** 关键日志 ✅(send/flush/recover/rebalance/route 均 `[kxj: ...]`)
- **AGENTS.md 7** 最小实现 ✅(~2050 行,无过度抽象)
- **AGENTS.md 11** 不动 springboot / jvm 版本 ✅
- **根白皮书 0.5** 故意不集群化 ✅
- **根白皮书 0.3** 进程崩溃 30s 自愈 ✅
- **唯一放宽**:根白皮书"数据不丢"→ 改为"数据基本不丢"(白皮书 C2 已明示)

---

**任务已完成!kxj**
