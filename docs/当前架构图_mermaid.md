# spring-watch 当前架构图 (Mermaid 版)

> 用 Mermaid 语法重画,支持 GitHub / VSCode / Typora 直接渲染
> 截止落地完成: 新生产消费架构 + 邮件告警 + InfluxDB buffer 调优

---

## 一、全景架构图

```mermaid
flowchart TB
    %% ============== 采集层 ==============
    subgraph COLLECTION["① 采集层 (JVM)"]
        direction TB
        REG["CollectScheduleRegistry<br/>32 虚拟线程池<br/>jitter ±10%, 间隔 15s"]
        PULL["AppPullTask.run(appid)<br/>1.可达性探测<br/>2.发心跳<br/>3.HostThrottler.tryAcquire<br/>4.doHeavyWork"]
        THR["HostThrottler<br/>Semaphore per host<br/>perHostConcurrent=4"]
        RETRY["PullRetryQueue<br/>2 drainer 虚拟线程<br/>指数退避 500ms×2^attempt"]
        METRIC["AgentMetricsCollector<br/>HTTP GET /metrics<br/>parse Prometheus"]
        LOG["AgentLogCollector<br/>HTTP GET /api/agent/logs?since="]
        BRIDGE["KafkaProducerBridge<br/>JSON 序列化 + send"]
        FB["KafkaFallbackQueue<br/>LinkedBlockingQueue 50K<br/>5s 单线程重投"]
        
        REG --> PULL
        PULL --> THR
        THR -->|限流| RETRY
        RETRY -.->|重投| PULL
        PULL --> METRIC
        PULL --> LOG
        METRIC --> BRIDGE
        LOG --> BRIDGE
        BRIDGE -->|失败回调| FB
    end

    %% ============== Kafka 集群 ==============
    subgraph KAFKA["② Kafka 集群 (KRaft)"]
        direction LR
        T1["monitor-metrics<br/>P=12, rep=1"]
        T2["monitor-logs<br/>P=6, rep=1"]
        T3["monitor-heartbeat<br/>P=3, rep=1"]
        T1D["monitor-metrics.DLQ<br/>P=3"]
        T2D["monitor-logs.DLQ<br/>P=3"]
        T3D["monitor-heartbeat.DLQ<br/>P=1"]
    end

    BRIDGE -->|send acks=all+幂等| T1
    BRIDGE -->|send| T2
    BRIDGE -->|send| T3

    %% ============== 消费层 ==============
    subgraph CONSUMPTION["③ 消费层 (5 消费组)"]
        direction TB
        CMC["BatchMetricConsumer<br/>group=metric-writer<br/>concurrency=3, batch=500"]
        CLC["BatchLogConsumer<br/>group=log-writer<br/>concurrency=2, batch=500"]
        CHC["BatchHeartbeatConsumer<br/>group=heartbeat-writer<br/>concurrency=1<br/>同 appid 取最新 → saveAll"]
        CAC["BatchAlertConsumer<br/>group=alert-evaluator<br/>concurrency=2, batch=500<br/>noAppid 单独计数"]
        CDC["DlqMonitorConsumer<br/>group=dlq-monitor<br/>concurrency=1<br/>仅 error 日志"]
    end

    T1 -->|partition key=appid| CMC
    T1 --> CAC
    T2 --> CLC
    T3 --> CHC
    T1D --> CDC
    T2D --> CDC
    T3D --> CDC

    %% ============== 存储层 ==============
    subgraph STORAGE["④ 存储层"]
        direction TB
        INFLUX["InfluxDB<br/>bucket=metrics,logs<br/>共享 WriteApi<br/>batch=1000, flush=1s<br/>bufferLimit=100K, retry=3"]
        PG["PostgreSQL<br/>• monitor_app<br/>• alert_rule<br/>• alert_history"]
        REDIS["Redis<br/>• alert:state:*<br/>(状态机 Hash)"]
    end

    CMC -->|writePoints| INFLUX
    CLC -->|writePoints| INFLUX
    CHC -->|saveAll| PG

    %% ============== 告警链路 ==============
    subgraph ALERT["⑤ 告警链路"]
        direction TB
        EXEC["AsyncAlertExecutor<br/>8 虚拟线程"]
        ENGINE["AlertEngine<br/>state machine"]
        EVAL["AlertEvaluator<br/>isBreached(rule,event)<br/>纯函数"]
        STATE["AlertStateStore<br/>Redis Hash<br/>TTL 24h"]
        CACHE["AlertRuleCache<br/>30s 定时刷新<br/>Controller 失效"]
        NOTIFY["AlertNotifier<br/>解析 notifyChannels<br/>JavaMailSender"]
        CTRL["AlertController<br/>/api/alerts/rules<br/>增删改后失效缓存"]
        
        CAC -.->|submit event| EXEC
        EXEC --> ENGINE
        ENGINE --> EVAL
        ENGINE --> STATE
        ENGINE --> CACHE
        ENGINE --> NOTIFY
        CACHE -.->|findByStatus enabled| PG
        ENGINE -->|写历史| PG
        STATE -.->|HGET/HSET| REDIS
        CTRL -->|invalidate| CACHE
    end

    NOTIFY -->|SMTP| MAIL["⑥ 邮件<br/>JavaMailSender<br/>(显式 Bean)"]

    %% 样式
    classDef kafka fill:#fff3cd,stroke:#856404
    classDef storage fill:#d1ecf1,stroke:#0c5460
    classDef alert fill:#f8d7da,stroke:#721c24
    classDef fallback fill:#ffeaa7,stroke:#d63031
    
    class T1,T2,T3,T1D,T2D,T3D kafka
    class INFLUX,PG,REDIS storage
    class ENGINE,EVAL,STATE,CACHE,NOTIFY,EXEC,CTRL alert
    class FB fallback
```

---

## 二、Kafka 消费组拓扑

```mermaid
flowchart LR
    BR["KafkaProducerBridge<br/>(单实例, JVM 唯一)<br/>key=appid"]
    
    T1["monitor-metrics<br/>P=12"]
    T2["monitor-logs<br/>P=6"]
    T3["monitor-heartbeat<br/>P=3"]
    
    BR -->|"send(appid)"| T1
    BR -->|"send(appid)"| T2
    BR -->|"send(appid)"| T3
    
    subgraph G1["spring-watch-metric-writer (con=3)"]
        CMC1["Thread-1"]
        CMC2["Thread-2"]
        CMC3["Thread-3"]
    end
    subgraph G2["spring-watch-alert-evaluator (con=2)"]
        CAC1["Thread-1"]
        CAC2["Thread-2"]
    end
    subgraph G3["spring-watch-log-writer (con=2)"]
        CLC1["Thread-1"]
        CLC2["Thread-2"]
    end
    subgraph G4["spring-watch-heartbeat-writer (con=1)"]
        CHC1["Thread-1"]
    end
    subgraph G5["spring-watch-dlq-monitor (con=1)"]
        CDC1["Thread-1"]
    end
    
    T1 -.分配.-> G1
    T1 -.分配.-> G2
    T2 -.分配.-> G3
    T3 -.分配.-> G4
    T1D["metrics.DLQ"] -.-> G5
    T2D["logs.DLQ"] -.-> G5
    T3D["heartbeat.DLQ"] -.-> G5
    
    G1 -->|"writePoints"| INFLUX["InfluxDB"]
    G3 -->|"writePoints"| INFLUX
    G4 -->|"saveAll"| PG["PostgreSQL"]
    G2 -->|"submit"| ENGINE["AlertEngine"]
```

---

## 三、告警状态机

```mermaid
stateDiagram-v2
    [*] --> IDLE
    
    IDLE --> PENDING : 条件首次满足<br/>(记录 firstBreachAt)
    RESOLVED --> PENDING : 条件再次满足
    
    PENDING --> FIRING : 持续 ≥ duration 秒<br/>发邮件 + 写 AlertHistory
    PENDING --> IDLE : 条件恢复<br/>(静默, 不通知)
    
    FIRING --> RESOLVED : 条件恢复<br/>发恢复邮件 + 填充 resolvedAt
    FIRING --> FIRING : 条件持续<br/>(不重发, 幂等)
    
    RESOLVED --> IDLE : 清除 Redis 状态
    
    note right of PENDING : 防瞬时抖动
    note right of FIRING : 幂等:<br/>不重复发邮件
    note right of RESOLVED : 标记恢复时间
```

---

## 四、告警触发时序图

```mermaid
sequenceDiagram
    autonumber
    participant K as Kafka<br/>(monitor-metrics)
    participant C as BatchAlertConsumer<br/>(con=2)
    participant E as AsyncAlertExecutor<br/>(8 虚拟线程)
    participant EN as AlertEngine
    participant EV as AlertEvaluator
    participant SS as AlertStateStore<br/>(Redis)
    participant CA as AlertRuleCache<br/>(30s 刷新)
    participant N as AlertNotifier
    participant M as Mail<br/>(JavaMailSender)
    participant DB as PostgreSQL<br/>(alert_history)

    K->>C: onBatch(List<String> 500 条)
    loop 每条 event
        C->>E: executor.submit(event)
    end
    
    par 异步评估 (虚拟线程)
        E->>EN: process(event)
        EN->>CA: rulesFor(event.appid)
        CA-->>EN: List<Rule>
        loop 每条 rule
            EN->>EV: isBreached(rule, event)
            EV-->>EN: true/false
            alt 条件满足 + 达到 duration
                EN->>SS: setState(FIRING)
                EN->>DB: insert AlertHistory
                EN->>N: notify(rule, event, "firing")
                N->>M: send(msg)
                M-->>N: ok
                N-->>EN: notifyResult
                EN->>DB: update notifyResult
            else 条件恢复
                EN->>SS: setState(RESOLVED)
                EN->>DB: find open AlertHistory → set resolvedAt
                EN->>N: notify(rule, event, "resolved")
                N->>M: send(msg)
            end
        end
    end
```

---

## 五、采集层降级时序图

```mermaid
sequenceDiagram
    autonumber
    participant S as CollectScheduleRegistry
    participant T as AppPullTask
    participant H as HostThrottler
    participant A as AgentMetricsCollector
    participant K as KafkaProducerBridge
    participant F as KafkaFallbackQueue
    participant KB as Kafka Broker

    S->>T: 调度触发
    T->>H: tryAcquire(host, 0)
    
    alt 限流
        H-->>T: false
        T->>F: (跳过, 走 PullRetryQueue)
    else 获取成功
        H-->>T: true
        T->>A: collect(target)
        A-->>T: List<MetricEvent>
        T->>K: sendMetric(event)
        K->>KB: send(topic, key, payload)
        
        alt 成功
            KB-->>K: ack
        else 失败
            KB-->>K: 异常
            K->>F: offer(topic, key, payload)
            F-->>F: 5s 后后台重投
            F->>KB: 重试 send
        end
    end
```

---

## 六、可靠性保障层级图

```mermaid
flowchart TB
    subgraph L1["L1 Producer 端"]
        P1["acks=all"]
        P2["enable.idempotence=true"]
        P3["retries=MAX"]
        P4["delivery.timeout.ms=120s"]
    end
    
    subgraph L2["L2 进程内兜底"]
        P5["KafkaFallbackQueue<br/>50K LBQ<br/>5s 单线程重投"]
    end
    
    subgraph L3["L3 Consumer 端"]
        P6["AckMode.BATCH"]
        P7["max.poll.records=500"]
        P8["fetch.min.bytes=1024"]
        P9["isolation=read_committed"]
    end
    
    subgraph L4["L4 错误处理"]
        P10["DefaultErrorHandler<br/>5 次指数退避<br/>200ms×2 → max 5s"]
        P11["DeadLetterPublishingRecoverer<br/>→ &lt;topic&gt;.DLQ"]
    end
    
    subgraph L5["L5 存储端"]
        P12["InfluxDB WriteOptions<br/>bufferLimit=100K<br/>retry=3, maxRetryTime=60s<br/>jitter=200ms"]
        P13["PostgreSQL saveAll 批量"]
    end
    
    L1 --> L2
    L2 --> L3
    L3 --> L4
    L4 --> L5
    
    style L1 fill:#d4edda
    style L2 fill:#fff3cd
    style L3 fill:#cce5ff
    style L4 fill:#f8d7da
    style L5 fill:#d1ecf1
```

---

## 七、并发模型对比图

```mermaid
flowchart LR
    subgraph "采集侧 (虚拟线程)"
        V1["SchedulePool<br/>32 虚拟"]
        V2["RetryDrainer<br/>2 虚拟"]
        V3["FallbackDrainer<br/>1 虚拟"]
        V4["AsyncAlertExecutor<br/>8 虚拟"]
    end
    
    subgraph "消费侧 (平台线程, Spring Kafka)"
        P1["metric-writer × 3"]
        P2["log-writer × 2"]
        P3["heartbeat-writer × 1"]
        P4["alert-evaluator × 2"]
        P5["dlq-monitor × 1"]
    end
    
    subgraph "InfluxDB 共享 WriteApi"
        W1["1 个 WriteApi<br/>batch=1000/flush=1s<br/>bufferLimit=100K"]
    end
    
    V1 -.->|HTTP 拉| EXT["Agent /metrics /logs"]
    V4 -->|异步| ENG["AlertEngine"]
    P1 -->|writePoints| W1
    P2 -->|writePoints| W1
    P3 -->|saveAll| PG[("PostgreSQL")]
    
    style V1 fill:#d4edda
    style V2 fill:#d4edda
    style V3 fill:#d4edda
    style V4 fill:#d4edda
    style P1 fill:#cce5ff
    style P2 fill:#cce5ff
    style P3 fill:#cce5ff
    style P4 fill:#cce5ff
    style P5 fill:#cce5ff
```
