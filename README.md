# spring-watch

> 基于 **拉取模型** 的 Spring Boot 应用监控平台:平台主动 HTTP GET 目标应用的 Java Agent 暴露的 `/metrics` 端点,聚合指标 / 日志 / 心跳,提供告警与查询能力。

详细架构原则与硬约束见 [`白皮书.md`](./白皮书.md)。

---

## 一、技术栈

| 维度 | 选型 |
|---|---|
| 基础 | Spring Boot 4.0.1 / Java 25 |
| 持久化 | PostgreSQL 16 + Flyway / Redis 7 |
| 时序 | InfluxDB 2.7(原始 + 5m 降采样) |
| 消息 | Apache Kafka 4.3 |
| 表达式 | Apache Commons JEXL 3(告警规则) |
| 邮件 | Spring Mail(QQ SMTP) |
| 构建 | Maven 3 |

---

## 二、模块结构

```
com.springwatch
├── SpringWatchApplication       启动入口(@EnableKafka / @EnableScheduling)
├── collector                    拉取侧:调度、HTTP 客户端、Kafka 生产、兜底队列
│   └── schedule                 拉取调度注册、主机节流、重试排空
├── ingest                       日志摄入:解析、清洗、指纹、去重
├── consumer                     Kafka 批消费者:metric / log / heartbeat / alert / dlq
├── analysis                     日志聚合、错误率调度、异常突增检测
├── alerter                      告警引擎:规则缓存、状态机、JEXL 求值、邮件通知
├── service                      业务服务层
├── repository                   JPA 仓储
├── model                        DTO / Entity / Event
├── web                          REST API 控制器
├── config                       配置类(Redis / Mail / Kafka / Influx / JEXL / Flyway)
├── monitor                      自监控采集
└── util                         工具(Snowflake ID 等)
```

---

## 三、快速开始

### 1. 启动依赖

```bash
docker compose up -d          # postgres / redis / influxdb / kafka
```

| 服务 | 端口 | 账号 |
|---|---|---|
| PostgreSQL | 5432 | root / 123456 / db: spring_collector |
| Redis | 6379 | - |
| InfluxDB | 8086 | admin / admin123456 / org: spring-watch / token: sw-token-2024 |
| Kafka | 9092 | PLAINTEXT |

### 2. 配置环境变量

`.env`(项目根目录,可选):

```properties
MAIL_AUTH_CODE=xxxxxxxxxxxx   # QQ 邮箱 SMTP 授权码
```

### 3. 启动应用

```bash
./mvnw spring-boot:run
# 或
mvn spring-boot:run
```

应用启动后监听 `http://localhost:8080`。

---

## 四、配置说明(关键项)

完整配置见 `src/main/resources/application.yml`,以下为最常调整项:

### 拉取(`spring-watch.collector`)

| 项 | 默认 | 说明 |
|---|---|---|
| `pool-size` | 32 | 采集线程池大小 |
| `per-host-concurrent` | 4 | 单目标主机最大并发 |
| `jitter-percent` | 10 | 采集时间抖动,避免雪崩 |
| `http.connect-timeout-ms` | 3000 | HTTP 连接超时 |
| `http.read-timeout-ms` | 10000 | HTTP 读取超时 |
| `retry.max-attempts` | 5 | 单次请求最大重试 |
| `retry.max-queue-size` | 1000 | 重试队列容量 |

### 告警(`spring-watch.alert`)

| 项 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关 |
| `mail.from` | 2787901285@qq.com | 发件人 |
| `executor.pool-size` | 8 | 告警执行线程数 |
| `scan.interval-ms` | 5000 | 扫描间隔 |
| `rule-cache.refresh-interval-ms` | 30000 | 规则缓存刷新 |

### Kafka(`spring.kafka`)

- `bootstrap-servers`:默认 `localhost:9092`
- Topic:`monitor-metrics`(12 分区)、`monitor-logs`(6 分区)、`monitor-heartbeat`(3 分区)、`monitor-dlq`(3 分区)
- 副本因子默认 1(单节点),生产请调大

### InfluxDB

- 原始桶:`metrics` / `logs`(保留 30d / 7d)
- 降采样桶:`metrics_5m` / `logs_5m`(保留 365d)
- 降采样任务每小时调度一次,聚合窗口 5m

---

## 五、数据流

```
[目标 Spring Boot 应用 + Java Agent]
        │  HTTP GET /metrics (15s 一次,平台主动)
        ▼
[collector: AppPullTask / HostThrottler / RetryPull]
        │  Kafka send → monitor-metrics / monitor-logs / monitor-heartbeat
        ▼
[consumer: BatchMetricConsumer / BatchLogConsumer / BatchHeartbeatConsumer]
        │  批写 InfluxDB + PostgreSQL
        ▼
[analysis + alerter]
        │  规则命中 → AlertNotifier → 邮件
        ▼
[web: REST API]  ←  平台使用方
```

Kafka 不可用时,生产者自动降级到**内存兜底队列**(`spring-watch.kafka.fallback-queue`),积压超阈值告警。

---

## 六、REST API 概览

所有接口返回统一结构 `ApiResponse<T>`:`{ code, message, data }`。

### 监控应用管理 `/api/monitor`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/monitor` | 列出全部应用 |
| GET | `/api/monitor/active` | 列出活跃应用 |
| GET | `/api/monitor/{id}` | 详情 |
| POST | `/api/monitor` | 注册新应用 |
| PUT | `/api/monitor/{id}` | 更新 |
| DELETE | `/api/monitor/{id}` | 删除 |

### 日志查询 `/api/logs`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/logs/search` | 关键字 + 多维过滤检索 |
| GET | `/api/logs/levels` | 级别分布 |
| GET | `/api/logs/stats/error-rate` | 当前窗口错误率 |
| GET | `/api/logs/stats/error-rate-series` | 错误率时序 |
| GET | `/api/logs/patterns` | TopN 异常模式 |
| GET | `/api/logs/fingerprint/{fp}` | 单 fingerprint 详情 |
| GET | `/api/logs/anomaly` | 异常突增检测 |
| GET | `/api/logs/trace/{traceId}` | 按 traceId 反查 |
| GET | `/api/logs/context` | 时间点上下文 |
| GET | `/api/logs/dedup/top` | 高频去重模式 |

### 指标查询 `/api/metrics`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/metrics/available` | 可用指标清单 |
| GET | `/api/metrics/latest` | 最新数据点 |
| GET | `/api/metrics/series` | 时序数据 |
| GET | `/api/metrics/grouped` | 按标签分组 |
| GET | `/api/metrics/histogram-quantile` | histogram 分位数估算 |
| GET | `/api/metrics/by-prefix` | 前缀模糊匹配 |

### 告警 `/api/alert`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/alert/rules` | 规则列表 |
| GET | `/api/alert/rules/{id}` | 规则详情 |
| POST | `/api/alert/rules` | 新建规则 |
| PUT | `/api/alert/rules/{id}` | 更新规则 |
| DELETE | `/api/alert/rules/{id}` | 删除规则 |
| POST | `/api/alert/rules/{id}/toggle` | 启停 |
| GET | `/api/alert/history` | 告警历史 |

### 通知配置 `/api/notification`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/notification/configs` | 配置列表 |
| POST | `/api/notification/configs` | 新建 |
| PUT | `/api/notification/configs/{id}` | 更新 |
| DELETE | `/api/notification/configs/{id}` | 删除 |
| POST | `/api/notification/test?to=xxx@xx.com` | 邮件测试 |

### 自监控 `/api/self`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/self/health` | 自身健康状态 |
| GET | `/api/self/metrics` | 平台自身指标 |

---

## 七、告警规则(JEXL)

`AlertRule.expression` 字段为 JEXL 表达式,可用变量:

- `value` — 当前指标值
- `avg(window)` — 窗口均值
- `max(window)` / `min(window)` — 窗口极值
- `tags` — 标签 Map

示例:

```
value > avg(5m) * 2 && tags.level == "ERROR"
```

---

## 八、本地调试

`mock-test/` 子模块提供目标应用的 mock,可一并启动验证端到端:

```bash
mvn -pl mock-test spring-boot:run
```

---

## 九、常见问题

**Q: 启动后日志大量 `Node -1 disconnected` / `Rebootstrapping`?**
A: Kafka 未启动或 `bootstrap-servers` 不可达。已通过 `logging.level.org.apache.kafka: warn` 屏蔽噪音,重连行为照旧进行。真正解决需保证 Kafka 可达。

**Q: 邮件发送失败?**
A: 检查 `.env` 中 `MAIL_AUTH_CODE`(QQ 邮箱授权码,非登录密码),并确认 `spring.mail.host / port / username` 正确。

**Q: InfluxDB 写入失败?**
A: 确认 `influxdb.token` 与 `docker-compose.yml` 中 `DOCKER_INFLUXDB_INIT_ADMIN_TOKEN` 一致;首次启动会自动创建桶。

**Q: Flyway 迁移报错?**
A: `spring.jpa.hibernate.ddl-auto=none`,表结构由 Flyway 管理。检查 `db/migration` 目录与 PostgreSQL `flyway_schema_history` 表。
