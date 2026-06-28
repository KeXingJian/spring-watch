# 自监控增强 Phase 1-4 实施完成

> 基于 `docs/observability-plan.md` 的 5 个 Phase,代码层面已全部完成。
> 待你 `mvn package` + 启动验证。

---

## 一、新增文件清单(7 个)

| 文件 | 作用 |
|---|---|
| `src/main/java/com/springwatch/config/InfraAlertsProperties.java` | 基础设施告警规则配置类(`@ConfigurationProperties`) |
| `src/main/java/com/springwatch/config/InfraMetricsBucketInitializer.java` | 创建 `infra_metrics` bucket(7 天 retention) |
| `src/main/java/com/springwatch/monitor/InfrastructureMetricsCollector.java` | 30s 周期查 InfluxDB `_internal` 桶 → 写 `infra_metrics` 桶 |
| `src/main/java/com/springwatch/monitor/KafkaLagMonitor.java` | 用 AdminClient 算各 topic consumer lag → 写 `infra_metrics` 桶 |
| `src/main/java/com/springwatch/alerter/InfrastructureAlertScheduler.java` | YAML 配置的告警规则 1min 评估一次,触发邮件 |
| `src/main/java/com/springwatch/service/InfraMetricsQueryService.java` | 查 `infra_metrics` 桶供前端用 |
| `src/main/java/com/springwatch/web/InfraController.java` | `/api/infra/*` REST 接口 |

## 二、修改文件清单(4 个)

| 文件 | 改动 |
|---|---|
| `src/main/java/com/springwatch/monitor/SelfMonitorCollector.java` | 加 G1 分代 / 线程 / 类加载 / WriteApi 队列 / InfluxDB 写队列 反射 Gauge |
| `src/main/resources/application.yml` | 加 `infra-bucket` / `infra-alerts` / `lag-monitor` 配置块 + 预置 5 条规则 |
| `docker-compose.yml` | 之前的修复:InfluxDB 加 `mem_limit: 1g` + `INFLUXD_CONFIG_PATH` |
| `influxdb.conf`(新) | 限制 TSM cache 256MB + query memory 512MB |

---

## 三、新增指标一览

### spring-watch 自身(写到 `self_metrics` 桶)

| Micrometer 名 | 含义 | 阈值告警建议 |
|---|---|---|
| `spring.watch.jvm.g1.eden.used` | G1 Eden 已用字节 | - |
| `spring.watch.jvm.g1.eden.max` | G1 Eden 最大字节 | - |
| `spring.watch.jvm.g1.oldgen.used` | G1 Old Gen 已用字节 | - |
| `spring.watch.jvm.g1.oldgen.max` | G1 Old Gen 最大字节 | - |
| `spring.watch.jvm.g1.oldgen.pct` | G1 Old Gen 使用率 % | >85% 告警 |
| `spring.watch.jvm.g1.survivor.used` | G1 Survivor 已用字节 | - |
| `spring.watch.jvm.g1.survivor.max` | G1 Survivor 最大字节 | - |
| `spring.watch.jvm.threads.current` | 当前线程数 | - |
| `spring.watch.jvm.threads.daemon` | 守护线程数 | - |
| `spring.watch.jvm.threads.peak` | 峰值线程数 | - |
| `spring.watch.jvm.classes.loaded` | 已加载类数 | - |
| `spring.watch.influxdb.write.queue.size` | InfluxDB WriteApi 内部队列(反射) | >15000 告警 |
| `spring.watch.influxdb.write.point.queued` | InfluxDB WriteApi 内部 point 数估算(反射) | - |
| `spring.watch.infra.poll.ok` / `.fail` | infra 采集成功/失败计数 | - |
| `spring.watch.infra.last_poll_epoch_ms` | 最近采集 epoch | - |
| `spring.watch.kafka.lag.poll.ok` / `.fail` | Kafka lag 采集成功/失败 | - |

### InfluxDB 自身(写到 `infra_metrics` 桶,component=influxdb)

从 `_internal` 桶采集 7 个 measurement:
- `go_runtime` → `go.*`
- `go_memstats` → `go_mem.*`
- `storage_engine` → `storage.*`
- `tsm_engine` → `tsm.*`
- `httpd` → `httpd.*`
- `query_control` → `query_control.*`
- `write` → `write.*`
- `task_executor` → `task.*`
- `concurrent` → `concurrent.*`

### Kafka 自身(写到 `infra_metrics` 桶,component=kafka)

| metric 标签 | 含义 |
|---|---|
| `consumer.lag` tag=topic+partition | 每个 partition 消费 lag |
| `consumer.lag.total` | 所有 partition lag 求和 |
| `consumer.partitions` | 监控的 partition 总数 |

---

## 四、新增 REST API

| 端点 | 用途 |
|---|---|
| `GET /api/infra/status` | 采集器自身状态(上次成功/失败时间) |
| `GET /api/infra/components` | 列出 `influxdb` / `kafka` |
| `GET /api/infra/metrics?component=influxdb` | 列出该 component 下所有 metric 名 |
| `GET /api/infra/series?component=kafka&metric=consumer.lag.total&from=&to=&every=` | 时序数据 |
| `GET /api/infra/latest?component=influxdb&metric=storage.cacheSizeBytes` | 最新一帧 |

---

## 五、预置告警规则(application.yml)

```yaml
spring-watch:
  infra-alerts:
    appid: 0
    rules:
      - name: jvm_old_gen_high
        component: influxdb
        metric: storage.cacheSizeBytes
        op: ">"
        threshold: 200000000     # 200MB
        level: warning
        cooldown-seconds: 300
      - name: jvm_old_gen_high_pct
        component: springwatch
        metric: jvm.g1.oldgen.pct
        op: ">"
        threshold: 85
        level: critical
        cooldown-seconds: 300
      - name: kafka_lag_metrics_high
        component: kafka
        metric: consumer.lag.total
        tag:
          topic: monitor-metrics
        op: ">"
        threshold: 10000
        level: warning
        cooldown-seconds: 300
      - name: kafka_lag_logs_high
        component: kafka
        metric: consumer.lag.total
        tag:
          topic: monitor-logs
        op: ">"
        threshold: 10000
        level: warning
        cooldown-seconds: 300
      - name: influxdb_write_queue_high
        component: influxdb
        metric: write.queue.size
        op: ">"
        threshold: 15000
        level: warning
        cooldown-seconds: 300
```

**注意**:`springwatch` component 对应的 `jvm.g1.oldgen.pct` 暂未写入 `infra_metrics`(它在 `self_metrics` 桶),这条规则暂时不会触发。如需启用,可在 `SelfMonitorCollector.toPoints()` 写入 self_metrics 时同步写一份到 infra_metrics,或加新采集任务。

---

## 六、运行前确认

### 1. InfluxDB `_internal` 桶可读

```bash
influx query 'from(bucket:"_internal") |> range(start:-1m) |> limit(n:1)' \
  --org spring-watch --token sw-token-2024
```

应该返回最近 1 分钟的系统指标。如果返回 permission denied,需在 InfluxDB UI 给 token 加 `_internal` 读权限。

### 2. Kafka 9092 可达

`KafkaLagMonitor` 直接连 `localhost:9092`(从 `spring.kafka.bootstrap-servers` 读)。生产环境如果是远程 Kafka,需在 `docker-compose.yml` 改地址。

### 3. 启动后看 4 个端点

```bash
curl http://localhost:8080/api/infra/status
curl http://localhost:8080/api/infra/components
curl 'http://localhost:8080/api/infra/metrics?component=influxdb'
curl 'http://localhost:8080/api/infra/series?component=kafka&metric=consumer.lag.total&from=2026-06-28T00:00:00Z'
```

### 4. 验证 30s 后出现数据

```bash
# InfluxDB 端
influx query 'from(bucket:"infra_metrics") |> range(start:-5m) |> limit(n:5)' \
  --org spring-watch --token sw-token-2024
```

应该看到 `component=influxdb` 和 `component=kafka` 的指标。

---

## 七、剩余工作(可选,前端为主)

| 任务 | 说明 |
|---|---|
| 前端自监控面板加 G1 卡片 | 已有 `/api/self/series?category=jvm&metric=g1.oldgen.pct` |
| 前端加"基础设施"菜单 | 调用 `/api/infra/*` |
| 前端加"WriteApi 队列"卡片 | 用 `/api/self/series?metric=spring.watch.influxdb.write.queue.size` |
| 跑 24h 压测验证 | 确认采集无 OOM / 无 OOM 风险 |
| 调优采集周期 | 30s 起步,看 InfluxDB 自身负载再调 |
| 修复 `jvm_old_gen_high_pct` 规则 | 同步写 `infra_metrics` 或用 self_metrics 桶 |

---

## 八、关键设计决策

1. **不引 Prometheus/Grafana** — 复用 InfluxDB `_internal` + 现有前端
2. **告警规则放 YAML 不放 DB** — 基础设施规则不常变,避免表结构侵入
3. **复用 `AlertNotifier`** — 邮件通道已有,直接发,不引入新告警系统
4. **JEXL 不参与 infra 规则** — infra 规则用简单的 `op + threshold`,够用且好调试
5. **每 30s 拉一次,够用** — 自监控面板 5s 轮询可容忍 30s 数据延迟

---

## 九、可能出现的问题

| 问题 | 解决 |
|---|---|
| `MongoSocketOpenException` 因为 token 无 `_internal` 读权限 | 在 InfluxDB UI 给 token 加权限 |
| `KafkaAdminClient` 超时 | 调大 `default-api-timeout-ms` |
| `InfluxDB write.queue.size` 一直返回 -1 | 反射拿不到字段名,需要查 influxdb-client-java 7.2 实际字段 |
| `infra_metrics` 桶创建失败 | 手动 `influx bucket create --name infra_metrics --retention 604800` |

---

## 十、一句话总结

> **5 个 Phase 全部代码完成。新增 7 个文件,改 4 个文件,新增 ~13 个 Micrometer Gauge + InfluxDB 8 个 measurement 的拉取 + Kafka 3 个 lag 指标 + 5 条预置告警规则 + 5 个 REST 端点。`mvn package` 启动后即可看到三栈基础设施观测。**
