# InflightQueue 白皮书

> 本文档为 spring-watch **自研消息队列组件 InflightQueue** 的**定性文件**。所有 InflightQueue 的实现代码、测试、Issue、PR、运维文档必须遵守本文定义的原则。如有冲突,**以本文为准**。
>
> 上一级文件:根 `白皮书.md`(项目整体白皮书)
>
> 适用范围:`spring-watch` v2.x 起的全部版本,以及后续可能抽出为独立 `spring-watch-inflight-queue` 模块的场景

---

## 0. 文档导读

| 章节 | 性质 | 核心问题 |
|---|---|---|
| 1 | 产品定位 | InflightQueue **是什么 / 不是什么** |
| 2 | 核心约束(6 条) | **必须满足的硬性要求**(用户已明确) |
| 3 | 设计哲学(3 原则) | 一切实现细节的**指导思想** |
| 4 | 关键决策 | 与 Kafka 的**逐项取舍** |
| 5 | 数据结构 | 内存 + 磁盘的**具体形态** |
| 6 | API 形态 | Producer / Consumer / 监控的**接口签名** |
| 7 | 背压策略 | 满 / 慢 / 卡的三层**降级动作** |
| 8 | 监控指标 | Micrometer 暴露的**全部 metric** |
| 9 | 并发模型 | 线程 / 锁 / 同步的**具体安排** |
| 10 | 故障与恢复 | 进程崩溃 / WAL 满 / OOM 的**处理流程** |
| 11 | 明确不做 | 14 条**反例边界** |
| 12 | 与项目原则的一致性 | 与根白皮书 / AGENTS.md 的**对齐声明** |
| 13 | 演进路线 | 三个版本的**功能增量** |

---

## 1. 产品定位

### 1.1 一句话定位

**InflightQueue 是 spring-watch 内部"采集 → 消费"链路上的持久化并发队列,用于替代 Kafka 4.3 单 broker,服务单机全栈单实例场景。**

### 1.2 角色图

```
                InflightQueue 替代的"三件套"
┌──────────────────────────────────────────────────┐
│  KafkaTemplate.send()                            │  ──→  InflightProducer.send()
│  @KafkaListener(topics="...", containerFactory)  │  ──→  InflightConsumer.poll()
│  KafkaFallbackQueue (LinkedBlockingQueue 兜底)  │  ──→  InflightQueue 内部 L1 背压
└──────────────────────────────────────────────────┘
```

### 1.3 适用场景 / 不适用场景

| 适用 ✅ | 不适用 ❌ |
|---|---|
| 100 监控目标以下,单 JVM 进程 | 1000+ 目标、需多实例水平扩展 |
| 数据可容忍少量丢失(秒级窗口) | 金融级"消息不丢"诉求 |
| 业务自带时间戳(本项目 `MetricEvent.timestamp` / `LogEvent.timestamp` / `HeartbeatEvent.timestamp`) | 业务依赖队列内"严格先后顺序" |
| 部署形态 = 1 台机器、5 分钟拉起 | 跨机器 / 跨机房复制 |
| 监控平台 / 日志平台 / 告警平台内部 | 对外提供消息中间件服务 |

### 1.4 非目标(Out of Scope)

- ❌ 替代通用消息中间件(Kafka / RabbitMQ / RocketMQ)对外提供服务
- ❌ 支持多语言客户端(只服务 Java 25+ JVM 内)
- ❌ 提供"消息事务"、"延迟消息"、"优先级队列"等高级特性

---

## 2. 核心约束(6 条硬性要求)

以下 6 条**不可违反**,违反任意一条视为组件未达标:

### 约束 C1:**一定要做持久化**

- **解释**:进程崩溃后,未消费的消息必须能从磁盘恢复,而不是清空
- **实现**:`mmap WAL`(Memory-Mapped Append-Only Log),见 5
- **失效判定**:WAL 写入失败时,必须计数器 + 告警,**不可静默丢数据**
- **错误反例**:HertzBeat `InMemoryCommonDataQueue` 的 5 个无界 `LinkedBlockingQueue`,无任何持久化

### 约束 C2:**允许丢失少量数据**

- **解释**:**不要求** acks=all,**不要求** strict-fsync,接受 OS page cache 未刷盘的最后 N 毫秒数据丢失
- **量化**:丢失窗口 = OS page cache 异步刷盘间隔,典型 1 ~ 5 秒
- **不接受**:批量丢失(> 1000 条 / 次)、持久性故障(磁盘满 / WAL 损坏)
- **取舍**:换 5 ~ 10 倍的写入吞吐(无 fsync 比 strict-fsync 快 5x ~ 10x)

### 约束 C3:**不要求顺序消费**

- **解释**:多个 consumer 可并发拉取同一 topic,**不保证**消费者拿到数据的顺序与生产者写入顺序一致
- **理由**:业务侧(`MetricEvent` / `LogEvent` / `HeartbeatEvent`)在入队**前**已打时间戳(`timestamp` 字段),消费时按 `timestamp` 入库即可
- **实现简化**:
  - **无 commit offset**(消费者无需告诉队列"我处理到哪里了")
  - **无 partition 路由**(单 topic 全局并发,不需要 key 哈希)
  - **无 rebalance**(无多实例协调)
  - **WAL 文件无需一致性恢复**,scan 时按 magic 头跳过坏块即可

### 约束 C4:**入队前已打时间戳**

- **解释**:`MetricEvent` / `LogEvent` / `HeartbeatEvent` 在调用 `send()` 之前,业务侧已设置 `timestamp` 字段
- **责任划分**:
  - **业务侧责任**:保证 `timestamp` 准确(精确到毫秒或纳秒)
  - **InflightQueue 责任**:不分配时间戳、不修改时间戳、不做时钟同步
- **数据用途**:消费时按 `timestamp` 写 InfluxDB,InfluxDB 自身按时间索引

### 约束 C5:**支持并发度调整**

- **Producer 端**:并发 offer,内部按 topic 寻址,**无需配置**
- **Consumer 端**:每 topic 一个 `ExecutorService`,线程数可配置(`spring-watch.inflight.consumer.concurrency`)
- **虚拟线程**:基于 Java 25 `Executors.newVirtualThreadPerTaskExecutor`,默认 1 物理线程支撑 1000+ 虚拟线程
- **配置项**:`spring-watch.inflight.consumer.concurrency.{metrics|logs|heartbeat}` 默认 2 / 2 / 1

### 约束 C6:**控制内存用量**

- **解释**:**严格有界**,不允许 `Integer.MAX_VALUE` 这种无界配置
- **三层防线**(见 7):
  - L1 in-flight 内存 buffer 满 → offer 拒绝 + 计数
  - L2 WAL append 失败 → offer 拒绝 + 计数
  - L3 pending 超过 80% 容量持续 5 分钟 → 告警(不丢)
- **量化目标**:100 目标 × 50 指标 × 4 次/分 = 333 points/s,in-flight buffer 50000 条 ≈ 50 MB(假设 1 KB/条),远低于 JVM 堆 200 MB 上限

---

## 3. 设计哲学(3 原则)

> 一切实现细节必须从这 3 条原则推导。如有冲突,原则 > 实现。

### 原则 P1:**JVM 堆 = 临时工位,WAL = 永久仓库**

(对照根 `白皮书.md` 1.1 原则 A "数据不驻堆")

```
业务事件                      JVM 堆(临时)                      磁盘(WAL,永久)
─────────                      ────────────                      ──────────────
MetricEvent  ──send()──→   ConcurrentLinkedDeque  ──flush──→   mmap segment
                          (in-flight buffer, 有界)             (append-only log)
LogEvent     ──send()──→         ↑
Heartbeat    ──send()──→         │
                                  ↓ poll()
                              InfluxDB writePoints()
                          (消费完即丢,堆里不留)
```

**禁止模式**:
- ❌ 把全量数据加载到堆(必须流式)
- ❌ `List<EventRecord> 缓存 = new ArrayList<>()` 无上限累积
- ❌ "我先把所有数据收到堆里,再统一处理"——这是内存泄漏的标准姿势

### 原则 P2:**优先降级,而不是堆缓冲**

(对照根 `白皮书.md` 1.1 原则 B "优先降级")

```
in-flight 满 ──→ 拒绝 + 计数 + 告警(业务方决定是否重试)
                  ↑
WAL append 失败 ──→ 同上
                  ↑
pending 超阈值 ──→ 告警 + 自动扩 consumer 线程(若可)
```

**绝对禁止**:
- ❌ "满了自动扩容 in-flight buffer 容量"——这是把 OOM 推后
- ❌ "满了把旧数据淘汰"——这是数据丢失,违反约束 C1 持久化诉求
- ❌ "满了睡 1 秒再 offer"——这是把背压转嫁给业务方

### 原则 P3:**业务时间戳为准,不维护队列内时间**

(对照约束 C3 + C4)

- **不允许**给 record 分配 `enqueueAt` 字段(会误导消费者按 enqueue 顺序处理)
- **不允许**做 `eventTime vs enqueueTime` 的时间差补偿
- **WAL record 内的 `ts` 字段是业务时间戳的副本**,**仅做调试用途**(看 WAL 文件时知道"这批数据是哪个时刻的")

---

## 4. 关键决策(对比 Kafka)

| 维度 | Kafka 4.3 | InflightQueue | 决策依据 |
|---|---|---|---|
| **acks** | 0 / 1 / all | **始终 0 等价**(fire-and-forget) | 约束 C2 允许丢失 |
| **fsync** | 按 `log.flush.interval` 配置 | **不强制 fsync** | 约束 C2 + 性能 |
| **副本数** | `replication.factor=1` | **1**(单进程无副本) | 单实例 |
| **partition** | 3 个 topic 各 3 partition | **1**(无 partition 路由) | 约束 C3 不要求顺序 |
| **commit offset** | consumer 必提交 | **不需要** | 约束 C3 |
| **key 路由** | hash(key) % partition | **不用** | 约束 C3 |
| **rebalance** | consumer group rebalance | **不需要** | 单实例 |
| **strict 顺序** | partition 内强顺序 | **不保证** | 约束 C3 |
| **exactly-once** | 事务 API | **不实现** | 业务侧幂等 |
| **消息压缩** | lz4 / zstd | **不实现** | 单机内 CPU 比网络贵 |
| **JMX 100+ 指标** | 全暴露 | **只暴露 6 个** | 监控简化为本项目原则 |
| **partition 利用率** | gauge | **不需要** | 单 partition |
| **consumer lag 监控** | 必需 | **不需要** | 无 commit offset |
| **DLQ** | 必需 | **不实现,业务侧处理** | 11 反例 |
| **优先级 / 延迟消息** | 不支持 | **不实现** | 11 反例 |

**省下来的复杂度**:不实现 partition、不实现 consumer group、不实现 rebalance、不实现事务 → **代码量 < 1000 行**(Kafka 客户端 ~ 50 万行)

---

## 5. 数据结构

### 5.1 内存:in-flight buffer

```java
class InflightBuffer {
    private final String topic;
    private final int capacity;                   // 严格有界,默认 50000
    private final ArrayDeque<byte[]> ring;       // 单条 byte[] 引用,避免大对象
    private final Semaphore slots;               // 容量计数,offer 前 acquire
    private final AtomicLong writeSeq;           // 入队序号(仅供调试)
    private final ReentrantLock mutex;           // 写读并发(简化为分段锁)
}
```

**为什么是 `ArrayDeque<byte[]>` 而不是 `LinkedBlockingQueue<EventRecord>`**:
- `byte[]` 是定长,避免业务对象持有大字段引用
- `ArrayDeque` 无节点对象开销,比 LBQ 内存省 ~30%
- 序列化在 send 之前完成,buffer 里只是"待写盘"的纯字节

### 5.2 磁盘:mmap WAL

**目录结构**:
```
${spring-watch.inflight.wal.dir}/.inflight/
├── monitor-metrics/
│   ├── 000000000000.segment         # 64 MB,mmap
│   ├── 000000000064.segment
│   ├── 000000000128.segment
│   └── recover.cursor               # 启动时写入的 recover 起点(可为空)
├── monitor-logs/
│   └── ...
├── monitor-heartbeat/
│   └── ...
```

**Record 格式**(32 字节 header + 可变 body):
```
偏移  大小   字段              说明
────  ────  ──────────────    ──────────────────────────────
0     4B    magic             0xCAFE_BEEF (大端),用于损坏跳过
4     4B    totalLength       header(32) + body 字节数
8     4B    crc32             header[0..8) + body 的 CRC32
12    4B    topicHash         topic 字符串的 CRC32,用于快速过滤
16    8B    timestamp         业务时间戳(ms,来自 payload)
24    4B    keyLength         key 字符串字节数
28    4B    payloadLength     payload 字节数
32    KB    key + payload     UTF-8 编码,直接拼接
```

**Segment 滚动**:当前 segment 写满 64 MB → 创建 `000000000064.segment`,append 续写
**Segment 清理**:旧 segment 在所有 consumer 都已读过其范围后异步删除(可配置保留期,默认 7 天)

### 5.3 全景

```
┌─────────────────────────────────────────────────────┐
│  InflightQueue 实例                                 │
│                                                     │
│  ┌──────────────────┐  ┌──────────────────┐         │
│  │ metricsBuffer    │  │ logsBuffer       │  ...    │
│  │ ArrayDeque(50000)│  │ ArrayDeque(50000)│         │
│  └────────┬─────────┘  └────────┬─────────┘         │
│           │                     │                   │
│           │  flush(100ms)       │                   │
│           ▼                     ▼                   │
│  ┌──────────────────────────────────────────┐       │
│  │ FlushScheduler (单线程)                  │       │
│  │ drain buffer → append WAL                │       │
│  └────────────────┬─────────────────────────┘       │
│                   │                                 │
│                   ▼                                 │
│  ┌──────────────────────────────────────────┐       │
│  │ MmapWAL(monitor-metrics/000000000000...) │       │
│  │ MmapWAL(monitor-logs/000000000000...)    │       │
│  │ MmapWAL(monitor-heartbeat/000000000000..)│       │
│  └────────────────┬─────────────────────────┘       │
│                   │                                 │
│                   │  poll(maxBatch)                 │
│                   ▼                                 │
│  ┌──────────────────────────────────────────┐       │
│  │ Consumer ThreadPool(虚拟线程,2/topic)   │       │
│  │ → 反序列化 → 写 InfluxDB                 │       │
│  └──────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────┘
```

---

## 6. API 形态

### 6.1 Producer API

```java
public interface InflightProducer {
    /**
     * 发送一条事件。
     * @param topic    目标 topic(monitor-metrics / monitor-logs / monitor-heartbeat)
     * @param key      用于日志追踪(不参与路由,见约束 C3)
     * @param payload  已序列化的 byte[],业务侧自行 JSON / Protobuf
     * @param timestampMs 业务时间戳(ms,见约束 C4)
     * @throws BackpressureException in-flight 满或 WAL 满时抛出
     */
    void send(String topic, String key, byte[] payload, long timestampMs) throws BackpressureException;
}
```

### 6.2 Consumer API

```java
public interface InflightConsumer {
    /**
     * 拉一批消息(≤ maxBatch)。可能返回空(无数据时)。
     * @param topic        目标 topic
     * @param maxBatch     最多拉多少条
     * @param waitMs       无数据时最多等多少 ms(0 = 立即返回)
     * @return             拉到的记录,可能为空列表
     */
    List<InflightRecord> poll(String topic, int maxBatch, long waitMs);

    /** 当前 topic 的 pending(写指针 - 读指针) */
    long pending(String topic);
}

public record InflightRecord(
    String topic,
    String key,
    byte[] payload,
    long timestampMs,    // 业务时间戳(冗余自 payload,方便消费侧快速过滤)
    long offset          // WAL 文件内偏移,仅供调试(无业务语义,见约束 C3)
) {}
```

### 6.3 监控 API

```java
public interface InflightMetrics {
    /** 各 topic 的关键指标快照 */
    Map<String, TopicMetrics> snapshot();

    record TopicMetrics(
        long pending,           // 当前堆积
        long capacity,          // in-flight buffer 容量
        long totalEnqueued,     // 累计入队
        long totalDrained,      // 累计消费
        long totalRejected,     // 累计拒绝(背压)
        long totalWalAppendFail // 累计 WAL 写入失败
    ) {}
}
```

---

## 7. 背压策略(对应约束 C6)

### 7.1 三层防线

| 层级 | 触发条件 | 动作 | 监控信号 |
|---|---|---|---|
| **L1 内存满** | `buffer.size() >= capacity` | `offer()` 抛 `BackpressureException` | `inflight.producer.rejected{topic=}` ++ |
| **L2 WAL append 失败** | mmap 写入异常 / 磁盘满 | 同上 | `inflight.wal.append.fail{topic=}` ++ |
| **L3 pending 超阈值** | `pending / capacity > 0.8` 持续 5 min | 发告警(不丢,不拒) | `inflight.queue.alert{topic=}` ++ |

### 7.2 业务侧处理建议

`BackpressureException` 抛出后,业务侧有 3 个选择:
1. **丢弃**:`MetricEvent` 偶发丢失可接受,直接 `log.warn` + 计数
2. **重试**:短暂 sleep 后重试 1 ~ 3 次(不阻塞,虚拟线程友好)
3. **降级到 Kafka**:作为 M2 灰度期的临时通道,InflightQueue 满 → 走 Kafka(详见规划 M2)

> 业务侧选择策略在 `InflightProducerBridge.send()` 中由 `BackpressureHandler` 决定,默认选 1(丢弃 + 计数)。

### 7.3 与 KafkaFallbackQueue 的关系

spring-watch 现有的 `KafkaFallbackQueue` 在 InflightQueue 落地后,**演变为**:L1 内存满时,如果业务选 3(降级到 Kafka),KafkaFallbackQueue 兜底。KafkaFallbackQueue 继续存在到 M3 移除 Kafka 阶段(详见 `自研内存队列替换Kafka规划.md`)。

---

## 8. 监控指标(共 7 个)

> 对比 Kafka 100+ JMX 指标,**只暴露 7 个**,够用就好。

| 指标名 | 类型 | Tags | 说明 |
|---|---|---|---|
| `inflight.producer.sent` | Counter | topic | 累计成功入队条数 |
| `inflight.producer.rejected` | Counter | topic | 累计 L1/L2 拒绝条数(背压计数) |
| `inflight.queue.pending` | Gauge | topic | 当前堆积(写指针 - 读指针) |
| `inflight.queue.capacity` | Gauge | topic | in-flight buffer 容量 |
| `inflight.consumer.batch.size` | DistributionSummary | topic | 每批消费条数(p50/p95/p99) |
| `inflight.wal.append.fail` | Counter | topic | 累计 WAL append 失败次数 |
| `inflight.wal.segments` | Gauge | topic | 当前 topic 占用的 WAL segment 数 |

**为什么没有 consumer lag**:
- 约束 C3 不要求顺序消费,无需 commit offset
- `pending` 已经足够反映"消费跟不上"的程度
- 不需要 broker 端的 `consumer_lag` 概念

**为什么没有 acks / replication 指标**:
- 约束 C1+C2 已明确无副本、无严格 ack
- 这些指标在 Kafka 是为了分布式场景,本项目无意义

---

## 9. 并发模型

### 9.1 线程清单

| 线程 | 数量 | 调度 | 职责 |
|---|---|---|---|
| **Producer 业务线程** | N(任意) | 由 spring 调度 | 调用 `producer.send()`,无锁 offer,O(1) |
| **FlushScheduler** | 1 / topic | `ScheduledExecutorService` | 100 ms 周期 drain in-flight buffer → append WAL |
| **Consumer 虚拟线程** | 可配,默认 2 / topic | `Executors.newVirtualThreadPerTaskExecutor` | poll → 反序列化 → 写 InfluxDB |
| **RecoverScanner** | 1(启动期) | 启动时启动,完成后退出 | scan WAL → 写回 in-flight buffer |
| **SegmentRoller** | 1 | `ScheduledExecutorService` | 1 min 周期检查 segment 满则滚动 + 删除旧 segment |
| **MetricsReporter** | 0(Micrometer 自动) | — | 由 Micrometer 框架调度 |

### 9.2 同步原语

| 数据结构 | 并发原语 | 理由 |
|---|---|---|
| `ArrayDeque<byte[]>` | `ReentrantLock` | 简单读写互斥;读写比约 1:1,锁开销可接受 |
| `Semaphore slots` | JDK 内置 | 容量计数,acquire/release 是 O(1) |
| `MmapWAL.append` | `ReentrantLock` | mmap 写指针必须原子推进 |
| `pending` 计数器 | `LongAdder` | 高并发读场景优化 |

### 9.3 同步顺序

```java
// 写入路径(无锁优先)
producer.send():
    1. slots.tryAcquire()                          ← L1 背压
    2. mutex.lock(); ring.offerLast(bytes); mutex.unlock()
    3. writeSeq.incrementAndGet()                  ← 仅调试

// Flush 路径(单线程,无并发)
flushScheduler(每 100ms):
    1. mutex.lock(); drain 1000 条; mutex.unlock()
    2. for each: wal.append(bytes)                 ← mmap 写入
    3. slots.release(n)                            ← 释放容量

// 消费路径(虚拟线程,可并发)
consumerThread:
    while (running):
        records = poll(topic, maxBatch, 100ms)    ← 等 100ms 凑批
        deserialize(records) → writeInfluxDB(records)
        // 不需要 commit offset(约束 C3)
```

---

## 10. 故障与恢复

### 10.1 进程崩溃

**崩溃时状态**:
- in-flight buffer 内未 flush 的数据:**丢失**(接受,见约束 C2)
- WAL 已写入但未消费的数据:**保留**,启动时 recover

**Recover 流程**(`InflightQueue.init()`):
1. 扫描 `${wal.dir}/.inflight/{topic}/` 下所有 `*.segment`
2. 对每个 segment,按 4KB 块读,找 magic 头 `0xCAFEBEEF`
3. 跳过损坏的尾部字节(因未强制 fsync,允许末尾 N KB 不一致)
4. 解析完整 record,反序列化 `payload`(此时不验证 schema,只读 length),放入 in-flight buffer
5. 写 `recover.cursor` 标记扫描到哪个 offset
6. 启动 FlushScheduler / Consumer 线程

**故障容错**:
- magic 头不识别:跳过该 4KB 块
- CRC 校验失败:跳过该 record
- 长度字段非法(length > segmentSize):截断到 segment 末尾

### 10.2 WAL 段满

**触发**:`currentSegment.size() >= 64 MB`
**动作**:
1. 创建下一个 segment 文件,mmap
2. 当前 segment 的引用计数 + 1(每个 consumer 持有 1 个引用)
3. consumer 读过该 segment 末尾 offset → 引用 - 1
4. 引用归 0 的 segment 等待 7 天保留期后删除

### 10.3 磁盘满

**触发**:`wal.append()` 抛 `IOException`
**动作**:
1. `inflight.wal.append.fail{topic}` ++
2. `producer.send()` 抛 `BackpressureException`
3. 业务侧按 7.2 选 1 / 2 / 3 处理
4. 告警通过 `SelfMonitorCollector` → InfluxDB → 自监控面板

### 10.4 OOM 风险

**预防**:
- in-flight buffer **严格有界**(`capacity` 默认 50000,~50 MB)
- 反序列化在 consumer 端,且每次最多 `maxBatch=200` 条,~200 KB
- 总堆占用:`in-flight × 3 topic + maxBatch × consumerThread × 3` ≈ 60 MB(可控)

**应急**:
- 触发 OOM:`-XX:+ExitOnOutOfMemoryError` 直接退出,操作系统自动重启容器
- 重启后走 10.1 流程 recover,业务自愈

---

## 11. 明确不做(反例边界)

以下 14 条**永久不做**,后续 Issue / PR 讨论时直接拒绝:

| 编号 | 反例 | 理由 |
|---|---|---|
| ❌ N1 | 严格消息不丢(acks=all) | 违反约束 C2 |
| ❌ N2 | 强制 fsync(strict-fsync) | 违反约束 C2 |
| ❌ N3 | partition 路由 | 违反约束 C3 |
| ❌ N4 | key 哈希到 partition | 违反约束 C3 |
| ❌ N5 | commit offset | 违反约束 C3 |
| ❌ N6 | consumer rebalance | 单实例无此需求 |
| ❌ N7 | 跨进程 / 跨机器通信 | 单 JVM only |
| ❌ N8 | 严格顺序消费 | 违反约束 C3 |
| ❌ N9 | exactly-once 语义 | 业务侧自行幂等 |
| ❌ N10 | 事务消息 | 复杂度不匹配 |
| ❌ N11 | 消息优先级 | 单 topic 内无差别 |
| ❌ N12 | 延迟消息(延时队列) | 无业务需求 |
| ❌ N13 | DLQ(死信队列) | **受控例外**:v1.6 已有 3 个 DLQ topic + `DlqMonitorConsumer` 落 PostgreSQL,完全删除会导致 web 端 DLQ 页面空白。**保留为"轻量 DLQ"**(物理文件 + 不自动重投 + 落库),详见 `docs/InflightQueue实现设计.md` §14 |
| ❌ N14 | 多语言客户端 | 仅服务 Java 25+ JVM 内 |

**讨论触发条件**:如果业务侧提出"需要 N1 ~ N14 之一",**先讨论约束是否调整**,而不是直接实现。

---

## 12. 与项目既有原则的一致性

### 12.1 与根白皮书的一致性

| 根白皮书原则 | 章节 | 一致性 |
|---|---|---|
| 轻量化(减容器、减配置、减代码) | 0.3 | ✅ 0 容器 / 0 端口 / ~1000 行代码 |
| 故意不集群化 | 0.5 | ✅ 单 JVM only,无副本 |
| 进程崩溃 → 30s 内自愈,数据不丢 | 0.3 | ⚠️ "数据不丢"放宽为"数据基本不丢"(约束 C2),需在 README 中明示 |
| 数据不驻堆 | 1.1 原则 A | ✅ in-flight buffer < 60 MB |
| 优先降级 | 1.1 原则 B | ✅ 7 三层背压 |

### 12.2 与 AGENTS.md 的一致性

| AGENTS.md 规则 | 章节 | 一致性 |
|---|---|---|
| 3 注入用 `@RequiredArgsConstructor+final` | — | ✅ InflightQueue 的所有依赖采用此方式 |
| 4 关键逻辑打印日志 | — | ✅ send / flush / recover / 背压触发均打印 `[kxj: ...]` |
| 7 最小实现,关注任务本身 | — | ✅ ~1000 行实现,无过度抽象 |
| 11 不动 springboot / jvm 版本 | — | ✅ Java 25 / Spring Boot 4.0.1 不动 |

### 12.3 与既有 `KafkaFallbackQueue` 的关系

`KafkaFallbackQueue`(现有)在 InflightQueue 落地后:

| 阶段 | KafkaFallbackQueue 角色 |
|---|---|
| v1.6(当前) | Kafka 不可用时的内存兜底,LinkedBlockingQueue(capacity=10000) |
| v2.x M1(旁路) | 作为 InflightQueue 满 → Kafka 降级路径的临时通道 |
| v2.x M3(移除 Kafka) | 改为 `InflightFallbackQueue`,纯内存兜底,不再回投 Kafka |

**复用点**:`InflightFallbackQueue` 直接 fork 自 `KafkaFallbackQueue` 的 `offer` / `drain` / `truncate` / 告警逻辑,只改实现细节。

---

## 13. 演进路线

### v2.0(基础版,本规划 M1 + M2)

- ✅ 5 数据结构
- ✅ 6 API 形态
- ✅ 7 背压策略
- ✅ 8 7 个监控指标
- ✅ 9 并发模型
- ✅ 10 故障与恢复(recover 流程)

### v2.1(优化版)

- ☐ WAL segment 压缩(用 lz4 减少磁盘占用,~30% 节省)
- ☐ 批量 WAL append(把多条 record 合并一次 mmap write,~3x 吞吐提升)
- ☐ 自适应 consumer 并发度(根据 pending 自动扩缩,Java 25 `Thread.ofVirtual()` 友好)

### v2.2(独立模块版)

- ☐ 抽出为 `spring-watch-inflight-queue` 独立 Maven 模块
- ☐ 对外提供公共 API + 文档
- ☐ 允许其他 Spring Boot 单机监控项目复用

---

## 14. 总结(决策矩阵)

| 关键决策 | 选择 | 替代方案 | 拒绝理由 |
|---|---|---|---|
| 持久化 | mmap WAL | RocksDB / SQLite | C++ 依赖 / 启动慢 / 与"5 分钟拉起"冲突 |
| 顺序 | 不保证 | 严格 partition 顺序 | 业务自带时间戳,无需 |
| 副本 | 1 | 3 副本 | 单进程无副本意义 |
| acks | fire-and-forget | acks=all | 接受少量丢失换吞吐 |
| fsync | 不强制 | strict-fsync | 同上 |
| partition | 1 | 多 partition + key 路由 | 无需顺序 |
| 队列类型 | in-flight + WAL 双层 | 仅内存 / 仅磁盘 | 仅内存违反 C1,仅磁盘违反 C6 |
| 线程模型 | 虚拟线程 | 平台线程池 | Java 25,虚拟线程是标配 |
| 指标数 | 7 | Kafka 100+ | 简化为本项目原则 |
| 代码量 | ~1000 行 | ~5000 行(实现 commit offset / rebalance) | 最小实现 |

---

**任务已完成!kxj**
