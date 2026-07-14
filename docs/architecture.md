# spring-watch 架构图

> 覆盖范围:目标应用接入 → 平台采集 → 消息缓冲 → 入库存储 → 告警/自监控 → 前端可视化全链路。
> 部署形态:单机全栈单实例,`docker compose up -d` 5 分钟拉起。

---

## 一、总体架构图(Top-Level)

```mermaid
flowchart TB
    subgraph TARGET["目标 Spring Boot 应用(被监控方,N 台)"]
        APP["业务应用<br/>@WithSpan 注解"]
        AGENT["Java Agent<br/>(OTel v1 / 自研 v2)"]
        METRICS_EP["/metrics<br/>Prometheus 文本"]
        LOG_EP["/api/agent/logs<br/>JSON 流式"]
        APP -.字节码拦截.-> AGENT
        AGENT --> METRICS_EP
        AGENT --> LOG_EP
    end

    subgraph PLATFORM["spring-watch 监控平台(单实例)"]
        direction TB

        subgraph COLLECT["采集层 collector"]
            MC["AgentMetricsCollector<br/>(15s 定时拉取)"]
            LC["AgentLogCollector<br/>(增量 since= 拉取)"]
            HC["AppPullTask / 心跳"]
        end

        subgraph BUFFER["消息缓冲(Kafka 单 broker)"]
            T1["monitor-metrics (3p)"]
            T2["monitor-logs (3p)"]
            T3["monitor-heartbeat (1p)"]
            T1D["*.DLQ (1p)"]
        end

        subgraph INGEST["日志处理 ingest"]
            DEDUP["LogDedupService<br/>(Caffeine 指纹去重)"]
            FINGER["LogFingerprinter"]
            SANIT["LogSanitizer<br/>(脱敏)"]
            PARSE["LogParser"]
        end

        subgraph CONSUMER["消费层 consumer"]
            BMC["BatchMetricConsumer"]
            BLC["BatchLogConsumer"]
            BHC["BatchHeartbeatConsumer"]
            BAC["BatchAlertConsumer"]
            DLQ["DlqMonitorConsumer"]
        end

        subgraph ALERT["告警层 alerter"]
            AE["AlertEngine<br/>(状态机)"]
            EE["JexlExprEvaluator"]
            AS["AsyncAlertExecutor"]
            AM["AsyncMailExecutor<br/>(SMTP)"]
            AST["AlertStateStore<br/>(Caffeine)"]
        end

        subgraph ANALYSIS["日志分析 analysis"]
            LA["LogAggregator<br/>(TopN)"]
            LADV["LogAnomalyDetector<br/>(Caffeine)"]
            LAS["LogAlertScheduler"]
        end

        subgraph MONITOR["自监控 monitor"]
            SMC["SelfMonitorCollector<br/>(Micrometer)"]
            IMC["InfrastructureMetricsCollector"]
            KLM["KafkaLagMonitor<br/>(AdminClient)"]
        end

        subgraph SERVICE["服务层 service"]
            MQ["MetricQueryService"]
            LQ["LogQueryService"]
            MA["MonitorAppService"]
            ARS["AlertRuleService"]
            SMQ["SelfMetricQueryService"]
            IMQ["InfraMetricsQueryService"]
        end

        subgraph WEB["接口层 web"]
            API["REST Controllers<br/>/api/metric /log /alert /infra /self"]
        end

        MC --> T1
        LC --> T2
        HC --> T3
        T1 --> BMC
        T2 --> BLC
        T3 --> BHC
        T1 -.失败.-> T1D
        T1D --> DLQ
        BMC --> INGEST
        BLC --> INGEST
        BHC --> PG
        BAC --> AE
        AE --> EE
        AE --> AS
        AE --> AST
        AS --> AM
        AS --> MA
        BLC --> LA
        LA --> LADV
        LADV --> LAS
        LAS --> BAC
        SMC --> IMQ
        IMC --> IMQ
        KLM --> IMQ
    end

    subgraph STORE["存储层"]
        IDB["InfluxDB 2.7<br/>5 bucket / 4 WriteApi"]
        PG["PostgreSQL 16<br/>(元数据 + 告警历史)"]
    end

    subgraph FRONT["前端(独立部署)"]
        UI["Vite + Vue 3 + TS + Pinia<br/>ECharts + Tailwind + DaisyUI"]
    end

    METRICS_EP -->|HTTP GET 15s| MC
    LOG_EP -->|HTTP GET 增量| LC

    BMC -->|WriteApi split| IDB
    BLC -->|WriteApi split| IDB
    BAC --> PG
    AE --> PG
    ARS --> PG
    MA --> PG
    SMC -->|self_metrics| IDB
    IMC -->|infra_metrics| IDB

    API --> MQ
    API --> LQ
    API --> ARS
    API --> MA
    API --> SMQ
    API --> IMQ
    MQ --> IDB
    LQ --> IDB
    IMQ --> IDB
    SMQ --> IDB

    UI -->|HTTP/JSON| API
```

**关键设计点**:
- **拉模型**:平台主动 HTTP GET 目标,目标永不推送(`白皮书 0.5 约束 1`)
- **5 桶分桶 + 4 WriteApi**:`metrics` / `logs` / `self_metrics` / `metrics_5m` / `infra_metrics`,各走独立 buffer
- **Kafka 单 broker + Caffeine 替代 Redis**:v1.5/1.6 收敛,零外置状态依赖
- **三栈可观测**:spring-watch 自身 + InfluxDB + Kafka 全部经 30s 周期拉取进 `infra_metrics` 桶

---

## 二、数据流分层图(按链路展开)

### 2.1 指标链路(Metrics)

```mermaid
flowchart LR
    A["目标应用<br/>@WithSpan"] -->|字节码| B["OTel Agent"]
    B -->|expose| C[":metricsPort/metrics"]
    C -->|HTTP GET<br/>15s 周期| D["AgentMetricsCollector"]
    D -->|连接池<br/>per-host 节流| D2["AgentHttpClient<br/>(host throttler)"]
    D2 -->|失败重试 3 次| D
    D -->|MetricEvent| E["Kafka<br/>monitor-metrics"]
    E -->|pull batch=500<br/>linger=50ms| F["BatchMetricConsumer"]
    F -->|4 独立 WriteApi<br/>按 app+metric 路由| G["InfluxDB<br/>bucket=metrics"]
    F -->|失败 / 不可重试错误| H["DLQ topic"]
    G -->|Flux 查询| I["MetricQueryService"]
    I -->|REST| J["前端指标面板"]
```

### 2.2 日志链路(Logs)

```mermaid
flowchart LR
    A["业务应用<br/>日志输出"] -->|拦截| B["InMemoryLogBufferAppender<br/>(客户内嵌)"]
    B -->|环形缓冲| C["AgentLogController<br/>:port/api/agent/logs"]
    C -->|HTTP GET<br/>since= 上次游标| D["AgentLogCollector"]
    D -->|原始 JSON 列表| E["Kafka<br/>monitor-logs"]
    E -->|pull batch=500| F["BatchLogConsumer"]
    F --> G["LogParser<br/>(JSON → 结构化)"]
    G --> H["LogSanitizer<br/>(手机号/身份证/邮箱 脱敏)"]
    H --> I["LogFingerprinter<br/>(SHA-256 模板指纹)"]
    I --> J["LogDedupService<br/>(Caffeine W-TinyLFU)"]
    J -->|新指纹| K["InfluxDB<br/>bucket=logs"]
    J -->|重复| J2["drop + deduped 计数"]
    K -->|Flux 查询| L["LogQueryService"]
    F --> M["LogAggregator<br/>(5min TopN)"]
    M --> N["LogAnomalyDetector<br/>(Caffeine 滑动窗口)"]
    N --> O["LogAlertScheduler<br/>(错误率超阈值)"]
    O --> P["BatchAlertConsumer<br/>(生成 AlertEvent)"]
    L -->|REST| Q["前端日志检索"]
    M -->|REST| Q
```

### 2.3 心跳链路(Heartbeat)

```mermaid
flowchart LR
    A["AppPullTask<br/>60s"] -->|HTTP GET /metrics| B["目标应用"]
    B -->|响应成功| C["monitor-heartbeat topic"]
    C --> D["BatchHeartbeatConsumer"]
    D -->|update last_heartbeat<br/>status=UP| E["PostgreSQL<br/>monitor_app 表"]
    D -->|60s 未更新| F["目标失联告警"]
    F --> G["AsyncAlertExecutor"]
    G --> H["SMTP 邮件"]
```

---

## 三、存储层架构

```mermaid
flowchart TB
    subgraph PG["PostgreSQL 16(Flyway 管理 schema)"]
        T1["monitor_app<br/>(应用注册)"]
        T2["alert_rule<br/>(告警规则)"]
        T3["alert_history<br/>(告警事件)"]
        T4["alert_notification_config<br/>(SMTP 收件人)"]
        T5["dlq_message<br/>(死信队列表)"]
        T6["log_dedup_count<br/>(去重统计)"]
    end

    subgraph IDB["InfluxDB 2.7(5 bucket / 4 WriteApi)"]
        B1["metrics<br/>retention=7d"]
        B2["logs<br/>retention=3d"]
        B3["self_metrics<br/>retention=6h"]
        B4["metrics_5m<br/>retention=30d<br/>(降采样)"]
        B5["infra_metrics<br/>retention=7d"]
        W1["metricsWriteApi"]
        W2["logsWriteApi"]
        W3["selfMetricsWriteApi"]
        W4["infraWriteApi"]
        B1 --- W1
        B2 --- W2
        B3 --- W3
        B5 --- W4
    end

    subgraph KAFKA["Kafka 4.3 单 broker"]
        K1["monitor-metrics (3p)"]
        K2["monitor-logs (3p)"]
        K3["monitor-heartbeat (1p)"]
        K4["monitor-metrics.DLQ (1p)"]
        K5["monitor-logs.DLQ (1p)"]
        K6["monitor-heartbeat.DLQ (1p)"]
    end
```

**WriteApi 分桶原因**(`白皮书 0.5 M-WriteApiSplit`):
- 各 WriteApi 独立 buffer / batch / flush 节奏
- 业务 metric 写爆不会拖死 self_metrics 写入
- 单批体积 5x,减少 InfluxDB HTTP 握手次数

---

## 四、告警引擎状态机

```mermaid
stateDiagram-v2
    [*] --> IDLE
    IDLE --> PENDING: 首次触发阈值
    PENDING --> FIRING: 持续 duration_seconds
    PENDING --> IDLE: 阈值回落
    FIRING --> RESOLVED: 阈值回落
    RESOLVED --> IDLE: 通知完成
    FIRING --> FIRING: 持续触发(重发间隔)

    note right of PENDING
        JEXL 表达式评估
        计数器 +1
        仍未达 duration → 保持
    end note

    note right of FIRING
        AlertNotifier 发邮件
        写 alert_history
        记录 AlertStateStore
    end note

    note right of RESOLVED
        写恢复事件
        通知收件人
        Caffeine 状态清理
    end note
```

**规则类型**:
| ruleType | 数据源 | 评估周期 | 评估器 |
|---|---|---|---|
| `METRIC` | `metrics` bucket | 15s | `JexlExprEvaluator` |
| `LOG` | 日志错误率 | 1min | `LogAlertScheduler` |
| `INFRA` | `infra_metrics` bucket | 30s | `JexlExprEvaluator` |
| `HEARTBEAT` | `monitor_app.last_heartbeat` | 60s | `BatchHeartbeatConsumer` |

---

## 五、自监控 + 基础设施可观测(三栈)

```mermaid
flowchart TB
    subgraph SOURCE["三栈被监控源"]
        SW1["spring-watch<br/>Micrometer + JVM"]
        IDB1["InfluxDB<br/>_internal 库"]
        KF1["Kafka<br/>AdminClient + JMX"]
    end

    subgraph PULL["拉取层(30s 周期)"]
        SMQ1["SelfMetricQueryService<br/>(查 meter)"]
        IMC1["InfrastructureMetricsCollector<br/>(查 _internal)"]
        KLM1["KafkaLagMonitor<br/>(算 lag)"]
    end

    subgraph STORE2["存储"]
        SM1["self_metrics bucket<br/>(spring-watch 自身)"]
        IM1["infra_metrics bucket<br/>(三栈)"]
    end

    subgraph SHOW2["展示与告警"]
        UIS["自监控面板<br/>(7 模块)"]
        IAS["InfrastructureAlertScheduler"]
    end

    SW1 --> SMQ1
    IDB1 --> IMC1
    KF1 --> KLM1
    SMQ1 --> SM1
    IMC1 --> IM1
    KLM1 --> IM1
    SM1 --> UIS
    IM1 --> UIS
    IM1 --> IAS
    IAS -.触发.-> UIS
    IAS -.触发.-> SMTP2["SMTP"]
```

**自监控七大模块**:
1. 总览 — JVM 堆 / 进程 CPU / 启动时长
2. 采集 — HTTP 成功/失败/超时 / 重投队列
3. JVM — G1 Eden/Old/Survivor / 线程 / 类加载
4. 进程 — RSS / CPU / FD
5. 指标库 — InfluxDB 写吞吐 / WriteApi 内部队列
6. InfluxDB — Go 堆 / TSM 缓存 / 活跃查询
7. Kafka — 消费 lag(per topic)/ 生产速率 / rebalance

---

## 六、本地缓存层(Caffeine 替代 Redis)

```mermaid
flowchart LR
    subgraph CAFFEINE["Caffeine W-TinyLFU 严格有界"]
        C1["LogDedupService<br/>max=100k 指纹<br/>expire=1h"]
        C2["AlertStateStore<br/>max=10k 规则状态<br/>expire=24h"]
        C3["LogAnomalyDetector<br/>max=5k 滑动窗口<br/>expire=5min"]
        C4["AsyncAlertExecutor<br/>评估队列<br/>max=2000"]
    end

    subgraph INPUTS["写入方"]
        I1["BatchLogConsumer"]
        I2["AlertEngine"]
        I3["LogAggregator"]
        I4["LogAlertScheduler"]
    end

    I1 --> C1
    I2 --> C2
    I3 --> C3
    I4 --> C4
```

**v1.6 收敛原因**(`白皮书 0.6.3`):单实例部署下 Redis 是多余依赖,Caffeine 单机性能优于 Redis 网络往返。

---

## 七、部署拓扑(Docker Compose)

```mermaid
flowchart TB
    subgraph HOST["单机(8C16G 起步)"]
        subgraph CONTAINERS["Docker Compose 容器"]
            C1["spring-watch<br/>:8080 → JVM 25<br/>4G heap"]
            C2["postgres:16<br/>:5432"]
            C3["influxdb:2.7<br/>:8086"]
            C4["kafka:4.3 (KRaft)<br/>:9092"]
        end
        C1 --> C2
        C1 --> C3
        C1 --> C4
    end

    subgraph TARGETS["被监控目标(N 台,远程)"]
        T1["Spring Boot App 1<br/>+ OTel Agent"]
        T2["Spring Boot App 2<br/>+ OTel Agent"]
        TN["..."]
    end

    C1 -->|HTTP GET 15s| T1
    C1 -->|HTTP GET 15s| T2
    C1 -->|HTTP GET 15s| TN
```

**资源基线**(`白皮书 0.4`):
| 组件 | CPU | 内存 |
|---|---|---|
| spring-watch JVM | 2~4 核 | 3~4 GB heap |
| InfluxDB | 1~2 核 | 1~2 GB |
| Kafka | 0.5 核 | 512 MB |
| PostgreSQL | 0.5 核 | 512 MB |
| **合计** | **4~7 核** | **5~7 GB** |

---

## 八、关键设计决策速查

| 决策 | 选择 | 备选 | 理由 |
|---|---|---|---|
| 拉 vs 推 | 拉 | 推(OTLP) | `白皮书 0.5 约束 1` 不可违反 |
| 存储 | InfluxDB 2.7 | Prometheus + Loki | 单技术栈,运维成本低 |
| 本地缓存 | Caffeine | Redis | 单实例下 Redis 是冗余依赖 |
| Kafka 分区 | 3/3/1/1 | 12/6/3/3 | `白皮书 0.5` 实测多分区无收益 |
| WriteApi | 4 桶分桶 | 1 个共享 | 单批体积 5x,故障隔离 |
| 告警表达式 | JEXL | 自研 DSL | 复用开源,表达式灵活 |
| 字节码 | OTel v1 / 自研 v2 | Spring AOP | 必须 Agent 拦截,AOP 失效 |
| 自监控栈 | 复用 InfluxDB | 引 Prometheus | 零外依赖,统一技术栈 |

---

## 九、演进路线

| 版本 | 目标 | 关键变更 |
|---|---|---|
| **v1.0~1.1** | MVP 跑通 | OTel Agent + Kafka + InfluxDB 基础链路 |
| **v1.2** | 接入简化 | `@WithSpan` 替代 `@SpringWatch` 注解 |
| **v1.3** | 高可用打磨 | WriteApi 分桶、CONCURRENTLY 死锁修复 |
| **v1.4** | 写入并发压榨 | M-WriteApiSplit 落地(4 WriteApi) |
| **v1.5** | 资源收敛 | Kafka partition 12→3/3/1/1 |
| **v1.6** | 零外状态依赖 | Caffeine 替代 Redis(`LogDedup` / `AlertState` / `Anomaly`) |
| **v2(目标)** | 自研 Agent | `-javaagent` 1 参数接入,0 annotation jar |

---

## 十、术语表

| 术语 | 含义 |
|---|---|
| **拉模型** | 平台主动 HTTP GET 目标,目标永不推送 |
| **WriteApi** | InfluxDB Java SDK 的异步批量写入客户端 |
| **DLQ** | Dead Letter Queue,死信队列,消费失败消息兜底 |
| **JEXL** | Java Expression Language,告警阈值表达式 |
| **Caffeine W-TinyLFU** | 高命中率 + 低内存的本地缓存算法 |
| **降采样** | 5min 窗口聚合指标,降低长期存储成本 |
| **Flyway** | 数据库 schema 迁移工具 |
| **OTel Agent** | OpenTelemetry Java Agent,字节码拦截器 |
| **自研 Agent(v2)** | 项目自研的 Java Agent,统一日志+方法级+SQL 监控 |
