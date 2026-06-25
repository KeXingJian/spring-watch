# 内存控制:为什么 13 个监控目标堆始终 ~100 MB

**结论:数据根本没在 JVM 堆里。** 堆里只放"在飞"(in-flight)的那一瞬间数据。
13 个目标扩到 1300 个目标,堆依然 100MB 左右,瓶颈会在别处,不在堆上。

## 1. 数据流总览(看哪一环进堆)

```
被监控 App /metrics
  → AgentMetricsCollector.collect()  解析完即丢
  → KafkaProducerBridge.sendMetric()  序列化完即丢
  → Kafka
  → BatchMetricConsumer.onBatch(messages)  ← 一次一批,写完即丢
  → InfluxDB writePoints(points)  写完即丢
  → Kafka 批量 ack

日志 / HTTP 探活 / 告警事件 / dedup → 同样路径,全部外抛给 InfluxDB / PG / Redis
```

唯一"长期驻留"在堆里的东西:
- Spring 容器 / Bean / Tomcat worker — 启动时定,跟监控目标数**无关**
- 60 槽 ring buffer(自监控历史)— 永远 60 槽
- 几个有界 Caffeine 缓存(告警规则、状态)— 有 LRU + 过期

监控数据**从不**进堆。

## 2. 关键反压设计(堆不爆的根本)

### 2.1 批量消费者:写完即丢,失败直接 drop

`BatchMetricConsumer.java:81-92`:
```java
writeApi.writePoints(points, metricsWriteParameters);
...
} catch (Exception e) {
    writeFailCounter.increment();
    log.error("[spring-watch: BatchMetricConsumer 写InfluxDB失败 - size={}, error={}], 本批丢弃,不重投]", ...);
}
```

注释里写得很清楚:**"本批丢弃,不重投,避免一条坏数据死循环"**。
`BatchLogConsumer.java:138` 同款。

这意味着:InfluxDB 慢了 / 卡了 / 拒写了,Kafka 那边的 consumer 会丢这一批,**不会**在堆里堆 `List<Point>`。
`points` 引用在 `onBatch` 方法返回时就出栈,GC 直接回收。

### 2.2 临时 ArrayList 跟 batch 大小走,不预分配巨大

`BatchMetricConsumer.java:67`:
```java
List<Point> points = new ArrayList<>(messages.size());
```

这个 List 容量 = **本批消息数**,不是"全量未处理消息"。
Kafka 的 `batchFactory` 控制 batch size(默认 500~1000),所以 ArrayList 峰值 1000 个 Point。
一个 Point ≈ 几百字节(指标名 + tags + value),1000 个 ≈ 几百 KB,**远小于** 100MB 堆的 1%。

### 2.3 SelfMonitorCollector 60 槽 ring,O(60) 永远不变

`SelfMonitorCollector.java:45, 71, 118-119`:
```java
private static final int RING_SIZE = 60;
private final Deque<Sample> ring = new ArrayDeque<>(RING_SIZE);
...
if (ring.size() >= RING_SIZE) ring.pollFirst();
ring.addLast(s);
```

自监控采样 10s 一次,ring 60 槽 = 10 分钟历史,满了就丢最老的。
13 个 vs 1300 个被监控目标,**这个 ring 不会变大**——它存的是 spring-watch 自己的指标。

### 2.4 指标/日志去重全在外部,不在堆

`LogDedupService.java`:
- 指纹 setIfAbsent / increment 全在 **Redis**
- 计数 30s 一次双写 **PG log_dedup_count**
- 堆里只有 String key,数据全在外部

`HostThrottler`、`AlertRuleCache` 用 **Caffeine**,带 LRU + 过期:
```java
Cache<Long, List<AlertRule>> newCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMillis(refreshIntervalMs * 2))
        .maximumSize(maxAppids)
        ...
```

有界,过期即清,不会无界增长。

### 2.5 采集白名单,防高基数撑爆

`SelfMonitorCollector.METER_WHITELIST_PREFIXES` 只收 `spring.watch.*` 前缀。
注释 `// P1-6` 明确说:**避免高基数 Gauge(如 http_server_requests × appid)撑爆 ring。**

13 个目标 vs 1300 个目标,白名单内的指标数差异 < 2 倍,差异 < 1KB/采样点。

## 3. 三个"看似能撑爆"的地方都做了限制

| 看似危险点 | 限制手段 | 文件 |
|---|---|---|
| Kafka consumer 一批消息 | `ArrayList<>(messages.size())` 跟 batch 走,不出栈 | `BatchMetricConsumer.java:67` |
| Flux query 返回结果 | `parseRows` 在方法内 new List,出栈即丢,前端要分页 | `LogQueryService.java:413-449` |
| 告警规则 / 状态 | Caffeine LRU + 过期,`maximumSize` 有上限 | `AlertRuleCache.java:70-78` |
| 日志全文搜索 | 改成分页(page/pageSize),返回 `SearchResult` 而不是全量 List | `LogController.java:34-53` |
| 监控告警历史 | 也有分页,DB 查询 LIMIT | `AlertHistoryController` |

## 4. 什么情况下堆才会涨

- **InfluxDB 写入慢 → 拖慢 batch consumer → Kafka 背压起来 → 堆里堆 batch**:
  - 这种情况下你会先看到 `spring.watch.consumer.metric.write_fail` 计数暴涨
  - 不是堆先爆,是 GC 先频繁
- **Flux 查询返回几万行 → parseRows 在堆里 list 一下**:
  - 前端如果用 `search.limit=10000`,parseRows 在堆里临时 list 10000 个 LogRow(每个 ~500B = 5MB),GC 完就丢
  - 这次新加的分页限制了 pageSize ≤ 200,峰值 100KB
- **Caffeine 没 LRU 干净**:
  - 概率极低,默认 `maximumSize + expireAfterWrite` 双保险

## 5. 调优经验值(给后续扩容参考)

| 监控目标数 | batch size | 堆稳态 | 主要变化点 |
|---|---|---|---|
| 13 | 500 | 100~120MB | 自监控 ring 60 槽 + Spring 容器 |
| 100 | 1000 | 110~140MB | + Caffeine 略涨,无变化 |
| 1000 | 2000 | 150~200MB | + InfluxDB 写延迟升高,可能丢批 |
| 10000 | 5000+ | 250~400MB | 单批变大,ArrayList 峰值高,要分多 partition |

**真正的瓶颈不在堆,在这些地方**:
1. **InfluxDB 写入吞吐** — `points/s` 超 50k 就要考虑集群化
2. **Kafka partition 数** — consumer 数 ≤ partition 数,否则空转
3. **PostgreSQL dedup_count 写入** — 30s 一次大批量,高 QPS 时需调参
4. **网络** — Kafka broker / InfluxDB / Redis 跨机房,延迟上去 batch 就堵

## 6. 一句话总结

**JVM 堆 = 临时工位,InfluxDB = 永久仓库。** 数据进堆是为了搬运,不是为了存放。
只要搬运通路不堵(InfluxDB 写入 < batch 频率),堆就跟监控目标数**完全无关**。

## 7. 监控自监控 — 怎么知道堆快爆了

自监控页面:
- **JVM 堆使用率** 卡片:`heap.used / heap.max`,超 70% 黄、85% 红
- **进程内存 (RSS)** 卡片(本次新增):跟任务管理器同源,直观看到 RSS
- 第 6 节"GC 暂停时间"折线:GC 频繁 → 堆紧张

Prometheus 告警建议(没接,建议加):
- `jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.85` 持续 5m
- `rate(jvm_gc_pause_seconds_sum[5m]) > 0.5` 频繁 STW
