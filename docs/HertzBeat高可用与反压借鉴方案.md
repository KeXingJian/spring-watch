# HertzBeat 高可用与反压机制分析 + spring-watch 借鉴方案

> 报告对象:`D:\codespace\ideaProject\hertzbeat`(被分析)vs `D:\codespace\ideaProject\spring-watch`(本项目)
>
> 分析时间:2026-06-28
>
> 关键模块定位:`hertzbeat-manager`(主控节点)、`hertzbeat-collector`(采集器)、`hertzbeat-remoting`(Netty 通信)、`hertzbeat-warehouse`(数据存储)、`hertzbeat-alerter`(告警)、`hertzbeat-common-core`(公共核心)

---

## 一、HertzBeat 高可用设计

### 1.1 总体形态:无 Manager 选举 + 有状态 DB 共享

| 维度 | 决策 | 关键证据 |
|---|---|---|
| 集群发现 | **自研 Netty + 心跳 + DB**,**不**用 ZK/Etcd/Nacos | `hertzbeat-remoting/.../netty/NettyRemotingServer.java:149` 应用层心跳 |
| Manager 选举 | **无**;Manager 进程多活无主从,所有状态在 PostgreSQL | `hertzbeat-startup/src/main/resources/application.yml:92-120` 无任何 raft/zk 配置 |
| Collector 注册 | 启动发 `GO_ONLINE` + 5s 心跳;落 DB + 入内存 Hash 环 | `CollectServer.java:193-226`、`CollectorOnlineProcessor.java:45-55` |
| 任务分片 | **一致性 Hash + 10 虚拟节点** | `hertzbeat-manager/.../scheduler/ConsistentHash.java:40-461` |
| 故障迁移 | 100s Idle 触发 `collectorGoOffline` → 顺时针迁移 + `dispatchJobCache` 兜底 | `ManageServer.java:232-247`、`ConsistentHash.java:128-159` |
| 故障检测三保险 | ① Netty `IdleStateHandler(100s)` ② `dispatchChannelHealthCheck 3s` 巡检 ③ `HeartbeatProcessor` 收到心跳自动重 add | `ManageServer.java:298-319`、`NettyServerConfig.java:30` |

### 1.2 任务分片细节:ConsistentHash

**核心数据结构**(自研 `ConcurrentTreeMap`,扩展 `TreeMap` + `ReentrantReadWriteLock`):

```java
// ConsistentHash.java:40-461
private final ConcurrentTreeMap<Integer, Node> hashCircle;       // 哈希环
private final Map<String, Node> existNodeMap;                   // 节点表
private final List<DispatchJob> dispatchJobCache;                // 无 Collector 兜底缓存
```

- 默认 `VIRTUAL_NODE_DEFAULT_SIZE = 10`(`ConsistentHash.java:60`),FNV1_32_HASH
- `dispatchKey` 当前是 `String.valueOf(monitorId)`,同一 Monitor 倾向落同节点
- 节点变化时仅迁移 `O(K/N)` 任务量,一致性 Hash 标准做法

### 1.3 故障检测三层防线

```
┌──────────────────────────────────────────────────────────┐
│  第一层:Netty IdleStateHandler(读空闲 100s)              │
│   └─ 主动断连 + onChannelIdle 回调                       │
│                                                          │
│  第二层:ManageServer.dispatchChannelHealthCheck(每 3s)   │
│   └─ 巡检 clientChannelTable,主动关闭无效通道            │
│                                                          │
│  第三层:HeartbeatProcessor 处理心跳时                     │
│   └─ 若通道不在表内 → 重新 addChannel + collectorGoOnline │
└──────────────────────────────────────────────────────────┘
```

### 1.4 HertzBeat 自身关键缺陷(也是 spring-watch 借鉴时要避开的坑)

- `dispatchJobCache` / `jobContentCache` **进程内内存**,Manager 重启即丢
- `dispatchKey = monitorId` 永远倾向同一 Collector,跨 Manager 不会按实例均匀
- Netty `sendMsgImpl` **未检查 `Channel.isWritable()`**,高吞吐时无反压(`NettyRemotingAbstract.java:108-110`)
- Manager 无标准熔断器、无令牌桶 QPS 限流
- Manager 无选举意味着 **DB 是事实单点**

---

## 二、HertzBeat 反压机制(分层防御)

### 2.1 整体数据流与反压防线

```
┌─────────────── 数据流 + 反压防线 ───────────────┐
│                                                  │
│  Collector 端                                    │
│   ├─ PriorityBlockingQueue(无界)+ 一次性任务优先  │
│   ├─ 虚拟线程池 Semaphore 准入控制                │
│   │    ├─ LIMIT_AND_REJECT → 降优先级重试        │
│   │    └─ LIMIT_AND_BLOCK                       │
│   ├─ DiscardOldestVirtualManagedExecutor(丢最老)│
│   └─ 采集超时监控 4min 强制丢弃                  │
│                                                  │
│  Collector → Manager(Netty 写缓冲)              │
│   └─ ⚠️ 仅靠 Netty 默认 ChannelOutboundBuffer  │
│                                                  │
│  Manager 端 队列抽象 CommonDataQueue            │
│   ├─ InMemory(无界)                              │
│   ├─ Redis(BRPOP/LPUSH + maxmemory)             │
│   ├─ Kafka(MAX_POLL_RECORDS=50, acks=all)      │
│   └─ Netty(发回 Manager,自循环)                  │
│                                                  │
│  消费侧                                          │
│   ├─ ExponentialBackoff(50ms~1s) 避免空转        │
│   └─ take() 阻塞取数据,不空轮询                   │
│                                                  │
│  告警 收敛链(应对告警风暴)                       │
│   ├─ AlarmCacheManager(PENDING 累积 + triggerTimes)│
│   ├─ AlarmGroupReduce(groupWait 30s / groupInterval 5m)│
│   ├─ AlarmInhibitReduce(类似 Prometheus 抑制)    │
│   ├─ AlarmSilenceReduce(时间窗静默)              │
│   └─ AlerterWorkerPool 按 channelType Semaphore  │
│                                                  │
│  TSDB 写入三级降级(最值得借鉴)                  │
│   ① 内存缓冲 offer()                              │
│   ② 80% 水位主动 flush                            │
│   ③ 失败降级直接同步写(绕过 buffer)              │
│   + 线性退避 + MAX_RETRIES                        │
│                                                  │
└──────────────────────────────────────────────────┘
```

### 2.2 关键源码定位(可作模板)

| 设计 | 文件 | 行号 |
|---|---|---|
| 虚拟线程准入三模式 | `hertzbeat-common-core/.../concurrent/ManagedExecutors.java` | 60-208 |
| 丢最老降级 | `ManagedExecutors.java` `DiscardOldestVirtualManagedExecutor` | 318-427 |
| TSDB 三级降级 | `hertzbeat-warehouse/.../vm/VictoriaMetricsDataStorage.java` | 572-613 |
| 告警分组防抖 | `AlarmGroupReduce.java` `ScheduledDispatchTask` | 415-472 |
| 指数退避 | `hertzbeat-common-core/.../util/ExponentialBackoff.java` | 49-57 |
| 周期告警防抖 | `PeriodicAlertRuleScheduler.java` `ScheduledTaskState` | 162-245 |
| 告警抑制 | `hertzbeat-alerter/.../reduce/AlarmInhibitReduce.java` | — |
| 告警静默 | `hertzbeat-alerter/.../reduce/AlarmSilenceReduce.java` | — |
| 采集超时 | `CommonDispatcher.java` | 153-191 |

### 2.3 虚拟线程准入三模式(核心抽象)

```java
// ManagedExecutors.java:60-208
public enum AdmissionMode {
    UNBOUNDED_VT,      // 不限制,OS 线程数被 concurrencyLimit 限制
    LIMIT_AND_REJECT,  // 达上限 tryAcquire() 立即抛 RejectedExecutionException
    LIMIT_AND_BLOCK    // 达上限 acquire() 阻塞提交者
}

// DiscardOldestVirtualManagedExecutor.java:318-427
// 满时丢弃队首旧任务 - 经典背压+降级
```

### 2.4 TSDB 三级降级(直接可抄)

```java
// VictoriaMetricsDataStorage.sendVictoriaMetrics() 简化模板
while (!offered && retryCount < MAX_RETRIES) {
    offered = metricsBufferQueue.offer(content, MAX_WAIT_MS, TimeUnit.MILLISECONDS);
    if (!offered) {
        if (retryCount == 0) triggerImmediateFlush();   // ② 主动 flush
        retryCount++;
        Thread.sleep(100L * retryCount);                // 线性退避
    }
}
if (!offered) {
    doSaveData(contentList);   // ③ 降级直接同步写
}
if (buffer.size() > capacity * 0.8) triggerImmediateFlush();  // 80% 水位
```

### 2.5 告警 Reduce 责任链(Prometheus AlertManager 范式)

```
dataQueue → pollMetricsDataToAlerter
   ↓
MetricsRealTimeAlertCalculator(3 消费线程)
   ↓ JEXL 表达式 evaluate
AlarmCacheManager(pending + firing Guava Table)
   ↓ 状态机
AlarmCommonReduce.reduceAndSendAlarm
   ↓
   ├─ AlarmGroupReduce     ← groupWait 30s / groupInterval 5m / repeatInterval 4h
   ├─ AlarmInhibitReduce   ← source 告警抑制 target 告警
   └─ AlarmSilenceReduce   ← 时间窗/周天周期静默
   ↓
AlertNoticeDispatch
   ↓ 按 channelType Semaphore 限流(默认 4/通道)
SMTP / Slack / DingTalk / WeWork
```

---

## 三、spring-watch 现状盘点

### 3.1 高可用与反压能力矩阵

| 能力 | 状态 | 关键文件 |
|---|---|---|
| 多实例横向扩展 | ⚠️ **未做任务分片**;`CollectScheduleRegistry` 32 线程单实例跑全部 app | `CollectScheduleRegistry.java:38-44` |
| Kafka | ⚠️ 单 broker 复制因子 1 | `application.yml:190` |
| Redis / PG | ❌ 单点 | `docker-compose.yml:2-30` |
| 健康探针 | ❌ 无 `/health/live` `/health/ready` | — |
| Prometheus 端点 | ❌ `SimpleMeterRegistry` 未对外暴露 | `MetricsConfig.java:14-21` |
| 告警抑制 / 静默 | ❌ 无 GroupReduce / InhibitReduce / SilenceReduce | `AlertEngine.java:1-438` |
| HostThrottler 清理 | ⚠️ 仅删除 app 时清理,无空闲回收 | `HostThrottler.java:42-50` |
| InfluxDBWriteGuard | ❌ 失败直接 drop 整批,无重投 | `BatchMetricConsumer.java:88-93` |
| 告警热路径 L1 cache | ⚠️ 每条事件 Caffeine get + Redis SCAN | `AlertEngine.java:128-160` |
| 已有且扎实 | ✅ 状态外置、Redis Lua CAS、虚拟线程、Kafka 降级队列、PullRetryQueue、Host 限流、独立邮件线程池 | 全模块 |

### 3.2 已有的 5 级降级链(`docs/实现项目轻量化和高可用报告.md:400-456`)

| Level | 场景 | 实现 |
|---|---|---|
| L1 Host 限流 | 单 Agent 拖垮 | `HostThrottler`(per-host semaphore) |
| L2 重试队列 | 瞬时抖动 | `PullRetryQueue`(PriorityBlockingQueue, 2 drainer, 5 次重试) |
| L3 Kafka 降级 | Kafka 不可用 | `KafkaFallbackQueue`(10k 容量 + 16KB 截断) |
| L4 InfluxDB 写失败 | InfluxDB 卡死 | `BatchMetricConsumer` / `BatchLogConsumer` 失败**丢批不重投** |
| L5 SMTP 慢响应 | 邮件阻塞告警 | `AsyncMailExecutor`(独立虚拟线程池, Semaphore(4)) |

### 3.3 真实故障暴露的薄弱点

来自 `docs/incident-influxdb-crash-2026-06-28.md`:
- InfluxDB query memory 无上限 → 已加 `influxdb.conf`
- downsample task 在 string 字段上跑 mean → 失败累积 → 已用 `cast float(v: r._value)` 修复
- **L4 当前是"丢批"**,InfluxDB 长时间挂掉会持续丢

来自 `docs/incident-memory-leak-2026-06-28-followup.md`:
- InfluxDB Java client 7.2.0 已知问题(OkHttp 池 / RetryQueue 累积)

---

## 四、借鉴方案(分 P0 / P1 / P2)

### 4.1 🔴 P0 — 必修(对应已识别的事故风险)

#### 借鉴点 1:InfluxDBWriteGuard(直接抄 `VictoriaMetricsDataStorage.java:572-613`)

```java
// 新增:BatchMetricConsumer.sendToInfluxDBWithGuard()
public void write(List<MetricEvent> batch) {
    int retry = 0;
    while (!buffer.offer(batch, MAX_WAIT_MS, MILLISECONDS)) {
        if (retry == 0) triggerImmediateFlush();      // ② 主动 flush
        if (++retry >= MAX_RETRIES) {
            doSaveDataSync(batch);                    // ③ 降级直写,绕过 buffer
            metricRegistry.counter("spring.watch.influxdb.degraded").increment();
            return;
        }
        Thread.sleep(100L * retry);                   // 线性退避
    }
    if (buffer.size() > buffer.capacity() * 0.8) triggerImmediateFlush();
}
```

- **收益**:2026-06-28 那种 InfluxDB 卡死不再丢批
- **注意**:同步直写路径需加超时(≤ 5s),防止拖死 Kafka consumer
- 报告已识别为 P1-G(报告中"暂不动"列表),建议升 P0

#### 借鉴点 2:K8s Health Probe(借鉴 `IdleStateHandler` 100s 检测思想)

```java
// 新增:HealthController
@RestController
@RequestMapping("/api/health")
public class HealthController {
    @GetMapping("/live")
    public ApiResponse live() { return ApiResponse.ok("UP"); }  // 进程是否存活

    @GetMapping("/ready")
    public ApiResponse ready() {
        Map<String, String> deps = new LinkedHashMap<>();
        deps.put("postgres", checkPg());         // SELECT 1, 1s
        deps.put("redis",    checkRedis());      // PING, 500ms
        deps.put("influxdb", checkInflux());     // /health, 1s
        deps.put("kafka",    checkKafka());      // AdminClient.describeCluster, 2s
        deps.put("fallbackQueue", kafkaFallbackQueue.size() < 0.8 * capacity ? "OK" : "BACKPRESSURE");
        deps.put("retryQueue",    pullRetryQueue.size()    < 0.8 * capacity ? "OK" : "BACKPRESSURE");
        boolean ready = deps.values().stream().allMatch(v -> "OK".equals(v) || v.startsWith("UP"));
        return ready ? ok(deps) : error(503, deps);
    }
}
```

- 借鉴 HertzBeat 用 `IdleStateHandler` 主动探测存活,改成主动探依赖
- K8s deployment 配 `livenessProbe` / `readinessProbe`

#### 借鉴点 3:`/actuator/prometheus` 暴露

```xml
<!-- pom.xml -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

```java
// MetricsConfig.java 改造
@Bean
public MeterRegistry meterRegistry() {
    return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
}
```

- HertzBeat 无对应实现,spring-watch 单独借鉴
- 现有 11 个 Micrometer 指标可被外部 Prometheus 抓取
- 给 P1 步骤的统一 Backpressure 指标提供落点

#### 借鉴点 4:Kafka 多 broker(降低消息层单点)

```yaml
# docker-compose.yml 升级 × 3 broker
KAFKA_NODE_ID: 1  # 2, 3
KAFKA_PROCESS_ROLES: broker,controller
KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 3
KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 3
KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 2
KAFKA_DEFAULT_REPLICATION_FACTOR: 3
KAFKA_MIN_INSYNC_REPLICAS: 2
```

```yaml
# application.yml
spring.kafka.producer.properties:
  acks: all
  min.insync.replicas: 2
```

---

### 4.2 🟡 P1 — 强烈推荐(解决架构级缺陷)

#### 借鉴点 5:任务分片(借鉴 `ConsistentHash.java`)

**目的**:多实例 spring-watch 时,**按 `appid` 哈希分片**,每实例只拉自己负责的 app,避免重复拉取。

**简化方案**(比 HertzBeat 简单,spring-watch 拉取 HTTP 端点而非 RPC):

```java
// 新增:CollectScheduleRegistry.heartbeatLock() + Redis 分布式锁
private static final Duration LOCK_TTL = Duration.ofSeconds(30);
private static final Duration LOCK_RENEW = Duration.ofSeconds(10);

@Scheduled(fixedDelay = 10_000)
public void maintainAppOwnership() {
    for (MonitorApp app : apps.values()) {
        String lockKey = "spring.watch:appowner:" + app.getAppid();
        String token = instanceId;
        // SETNX + EX
        if (redis.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL)) {
            // 我抢到了,注册调度
            scheduleApp(app);
        } else {
            String owner = (String) redis.opsForValue().get(lockKey);
            if (instanceId.equals(owner)) {
                // 我是 owner,续期
                redis.expire(lockKey, LOCK_TTL);
            } else {
                // 不是我的,跳过
                continue;
            }
        }
    }
}
```

- **故障迁移**:锁 TTL 30s,owner 实例挂掉后 30s 内其他实例抢到,自动接管
- **状态外置**:Redis 已是状态中心,无需新增依赖
- **可演进**:成熟后改用 `KetamaHash` / `Redisson` 一致性 Hash 替代简单 SETNX

#### 借鉴点 6:告警 Reduce 链(借鉴 `AlarmGroupReduce` + `AlarmInhibitReduce` + `AlarmSilenceReduce`)

**当前链路**:`AlertEngine.handleBreach → AlertNotifier`

**借鉴后链路**:
```
              ┌─ AlarmGroupReduce     (同 appid+rule 30s 合并 / 5min 间隔)
FIRING 事件 → ├─ AlarmInhibitReduce   (severity=fatal 抑制 severity=warning)
              ├─ AlarmSilenceReduce   (时间窗静默 + 周天周期静默)
              └─ AlertNotifierDispatch
```

- **AlarmGroupReduce 必做**(`AlarmGroupReduce.java:415-472` `ScheduledDispatchTask` 防抖),直接砍掉 80% 告警邮件风暴
- **AlarmSilenceReduce 必做**,深夜维护期不再被打扰
- **AlarmInhibitReduce 可选**,基础设施告警可抑制应用告警

模板(`AlarmGroupReduce.java:259-280` 简化):
```java
public class AlarmGroupReduce {
    private final Map<String, GroupAlertCache> groupCacheMap = new ConcurrentHashMap<>();
    private final Map<String, SingleAlert> alertFingerprints = new ConcurrentHashMap<>();

    public void process(AlertEvent event) {
        String fp = fingerprint(event);  // appid|ruleId|severity
        if (alertFingerprints.containsKey(fp)
            && within(alertFingerprints.get(fp).lastSentAt, groupInterval)) {
            return;  // 5min 内已发,跳过
        }
        GroupAlertCache cache = groupCacheMap.computeIfAbsent(
            event.getGroupKey(), k -> new GroupAlertCache(groupWait));
        cache.add(event);
        cache.scheduleDispatch(() -> flushAndNotify(event.getGroupKey()));
    }
}
```

#### 借鉴点 7:HostThrottler 空闲清理(借鉴 `ManageServer.dispatchChannelHealthCheck`)

```java
// 新增:HostThrottler.cleanupIdle()
@Scheduled(fixedDelay = 3_600_000)  // 1h
public void cleanupIdle() {
    hostSemaphores.entrySet().removeIf(e -> {
        Semaphore s = e.getValue();
        return s.availablePermits() == MAX_PERMITS  // 持续空闲
            && !activePullTasks.contains(e.getKey());
    });
}
```

- 对应 HertzBeat 3s 通道健康巡检的简化版(本项目拉 HTTP 端点而非长连接,1h 周期足够)

#### 借鉴点 8:统一 Backpressure 指标(把现有 11 个 Micrometer 串成综合 Gauge)

```java
// 新增:BackpressureMetrics
Gauge.builder("spring.watch.backpressure.severity", () -> {
    int score = 0;
    if (kafkaFallbackQueue.size() > 8000) score += 2;
    if (pullRetryQueue.size() > 800)       score += 2;
    if (alertExecutor.activeCount() >= 7)   score += 1;   // semaphore=8
    if (influxdbWriteFailureRate > 0.1)    score += 3;
    if (hostThrottlerPeakUsage > 0.9)      score += 1;
    return score;  // 0~10,接到 Prometheus 端点
}).register(registry);
```

- score > 6 触发平台级告警(Prometheus alert rule)

---

### 4.3 🟢 P2 — 锦上添花

#### 借鉴点 9:告警调度防抖合并(借鉴 `PeriodicAlertRuleScheduler.ScheduledTaskState`)

```java
class ScheduledDispatchTask {
    private volatile boolean running = false;
    private volatile boolean pending = false;
    void trigger() {
        if (running) { pending = true; return; }
        running = true;
        try { doDispatch(); }
        finally {
            running = false;
            if (pending) { pending = false; trigger(); }
        }
    }
}
```
- 解决 PendingStateScanner 与实时路径同时触发同一规则的竞态

#### 借鉴点 10:告警热路径 L1 cache

```java
private final Cache<Long, Boolean> recentMatch = Caffeine.newBuilder()
    .expireAfterWrite(2, SECONDS).maximumSize(10_000).build();
// key = appid|ruleId|metricName
// 避免每条事件都走 JEXL + Redis
```

#### 借鉴点 11:Redis Sentinel / PG 主从

- 写多读少场景,可推迟
- 但需在 `application.yml` 留好配置入口(`spring.redis.sentinel.nodes`)

---

## 五、**不**建议借鉴的

| HertzBeat 设计 | 原因 |
|---|---|
| 自研 Netty Remoting | spring-watch 用 Kafka 已足够,自研 RPC 维护成本高 |
| Manager 进程多活无选举 | spring-watch 单体足够,无需这个复杂度 |
| Apache Arrow 零拷贝 | spring-watch 走 InfluxDB Line Protocol,框架已优化 |
| 虚拟线程(已借鉴) | ✅ spring-watch 已在用 |

---

## 六、落地路线图

```
Week 1  P0-1 InfluxDBWriteGuard(三级降级,直接抄 VM.java:572-613)
        P0-2 Health Probe(/api/health/live /ready)
        P0-3 Prometheus 端点暴露

Week 2  P0-4 Kafka 多 broker 化
        P1-7 HostThrottler 空闲清理
        P1-8 统一 Backpressure 指标

Week 3  P1-5 任务分片(先实现 Redis 锁简化版,后续可换 KetamaHash)
        P1-6 告警 GroupReduce(防邮件风暴,直接抄 AlarmGroupReduce)

Week 4  P1-6 告警 SilenceReduce + InhibitReduce
        P2-9 告警调度防抖
        P2-10 告警热路径 L1 cache
```

---

## 七、总结

**HertzBeat 的核心价值不在"高可用"本身**(它其实很轻、Manager 无选举),**而在它把"反压"做成了分层防御 + 多种降级模式(准入控制 / 丢弃 / 降级直写 / 告警收敛)的产品化体系**。

对 spring-watch 来说:

- **P0-1(InfluxDBWriteGuard 三级降级)** 直击 2026-06-28 InfluxDB 卡死故障,**最高 ROI**
- **P1-6(告警 Reduce 链)** 解决告警邮件风暴,AlertEngine 当前缺少收敛层
- **P1-5(任务分片)** 是 spring-watch 多实例水平扩展的"最后一块拼图"
- **P0-2 / P0-3(Health + Prometheus)** 是接入 K8s 编排和外部监控的必备

其他按需渐进即可。
