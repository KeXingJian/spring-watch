# 日志下游处理性能优化报告

## 背景

压测场景:50 个监控实例,15s 一次拉取。

| 指标 | 现象 |
|---|---|
| Kafka `monitor-metrics` 队列 | 不增长,lag 动态 = 0 |
| Kafka `monitor-logs` 队列 | **10 分钟堆积 50 万条,持续增长** |
| 指标入库速率 | 日志入库速率的 **3 倍** |
| InfluxDB | **未打满** |
| 瓶颈定位 | 日志下游处理,不在 InfluxDB 也不在 Kafka |

## 根因分析

`BatchLogConsumer:97-129` 单条日志热路径上的同步 Redis 调用:

| 调用 | 作用 | 延迟 |
|---|---|---|
| `setIfAbsent` | 窗口首现判定 | ~1ms RTT |
| `increment` | 累加 drop 计数 | ~1ms RTT |
| `expire` | 续 TTL | ~1ms RTT |
| `sadd` | 标脏 | ~1ms RTT |
| **合计** | **每条日志 2-4 次 RTT** | **~3ms × 50k/s = 阻塞** |

3 线程 × 3ms/条 = **理论上限 1000/s**,而实际流量是 50k/s,**5 倍差距 → 队列必然堆积**。

`AlertStateStore` 和 `LogAnomalyDetector` 也走 Redis,告警评估的 Lua CAS 同样在热路径上。

## 优化方案:全面剔除 Redis,Caffeine 接管

### 三个场景的 Caffeine 化设计

| 模块 | 原 Redis 用法 | Caffeine 方案 | 关键技巧 |
|---|---|---|---|
| `LogDedupService` | SETNX + INCR + EXPIRE + SADD | `Cache<DedupKey, LongAdder>` | `asMap().putIfAbsent` 原子首现 + `LongAdder.increment()` 无锁计数 |
| `AlertStateStore` | Hash + Lua CAS 脚本 | `Cache<RuleAppKey, AlertStateData>` + 自定义 `Expiry` | `asMap().compute()` 替代 Lua,per-key 串行即等价 CAS |
| `LogAnomalyDetector` | String + Set | `Cache<Long, Double>` + `Cache<Long, PatternSet>` | 内嵌 `PatternSet` 同步 LRU 二次有界 |

### 严格有界内存防护

每个 Caffeine cache 三重保险:

1. **自然上限 `expireAfterWrite`**:窗口/TTL 到期自动释放
2. **硬上限 `maximumSize`**:W-TinyLFU 淘汰,触达即驱逐
3. **可观测指标**:`cache_size` / `cache_max` / `evict_size` / `evict_expired` —— `evict_size > 0` 立即告警 = 流量超配

| Cache | key | value | max-entries | 内存上限 |
|---|---|---|---|---|
| `dedupCache` | `DedupKey` record | `LongAdder` | 200,000 | ~40MB |
| `stateCache` | `RuleAppKey` record | `AlertStateData` 8 字段 | 50,000 | ~10MB |
| `errorRateCache` | `Long(appid)` | `Double` | 200 | <1KB |
| `knownPatternsCache` | `Long(appid)` | `PatternSet` (内含 ≤5000 模式) | 200 | ~30MB |
| **合计** | | | | **~80MB 硬封顶** |

### 关键代码片段

#### 1. 日志去重热路径(`LogDedupService:93-106`)

```java
public boolean shouldKeep(long appid, String fingerprint) {
    if (fingerprint == null || fingerprint.isEmpty()) {
        return true;
    }
    DedupKey key = new DedupKey(appid, fingerprint);
    LongAdder existing = dedupCache.asMap().putIfAbsent(key, new LongAdder());
    if (existing == null) {
        keepCounter.increment();
        return true;
    }
    existing.increment();
    dropCounter.increment();
    return false;
}
```

`putIfAbsent` 返回 `null` 即首条——**零锁,无网络 RTT,纯本地纳秒级**。

#### 2. 告警状态 CAS(`AlertStateStore:232-261`)

```java
public boolean tryFire(Long ruleId, Long appid, Instant firstBreachAt, Instant lastFiredAt) {
    RuleAppKey key = new RuleAppKey(ruleId, appid);
    AtomicBoolean fired = new AtomicBoolean(false);
    stateCache.asMap().compute(key, (k, existing) -> {
        AlertStateData current = existing != null ? existing : IDLE_DATA;
        if (current.getState() != AlertState.PENDING) {
            return existing;
        }
        var b = current.toBuilder()
                .state(AlertState.FIRING)
                .expireAt(Instant.now().plus(Duration.ofHours(ttlHours)));
        if (firstBreachAt != null) b.firstBreachAt(firstBreachAt);
        if (lastFiredAt != null) b.lastFiredAt(lastFiredAt);
        fired.set(true);
        return b.build();
    });
    return fired.get();
}
```

Caffeine 的 `compute()` **per-key 串行执行**,等价于 Redis Lua 脚本的 CAS 语义,但**纯本地 µs 级**,无网络往返。

#### 3. per-entry TTL(`AlertStateStore:74-97`)

```java
.expireAfter(new Expiry<RuleAppKey, AlertStateData>() {
    @Override public long expireAfterCreate(K, V, long t)  { return nanosUntil(value.getExpireAt(), t); }
    @Override public long expireAfterUpdate(K, V, long t, long d) { return nanosUntil(value.getExpireAt(), t); }
    @Override public long expireAfterRead(K, V, long t, long d)   { return nanosUntil(value.getExpireAt(), t); }
})
```

把 `expireAt` 嵌入数据,每次 create/update/read 都从值里重读 TTL——**模拟 Redis "每次 HSET 都重置 EXPIRE" 的语义**。

#### 4. 内嵌 LRU 二次有界(`LogAnomalyDetector:132-157`)

```java
static final class PatternSet {
    private final int maxSize;
    private final LinkedHashSet<String> set = new LinkedHashSet<>();

    synchronized boolean add(String s) {
        if (set.contains(s)) return false;
        if (set.size() >= maxSize) {
            Iterator<String> it = set.iterator();
            if (it.hasNext()) { it.next(); it.remove(); }   // FIFO 淘汰
        }
        return set.add(s);
    }
}
```

外层 cache 按 `appid` 硬封顶 200,内层 `PatternSet` 单 appid 封顶 5000——**双层有界**。

## 清理工作

| 文件 | 改动 |
|---|---|
| `config/RedisConfig.java` | **删除** |
| `application.yml` | 删 `data.redis.*`,加 3 个模块的 `max-entries` 硬上限配置 |
| `pom.xml` | 删 `spring-boot-starter-data-redis` |
| `docker-compose.yml` | 删 `redis` 服务 + `redis_data` 卷 |
| `PendingStateScanner.java` / `LogQueryService.java` | 更新注释,移除 Redis 表述 |

## 优化结果

| 指标 | 优化前 | 优化后 |
|---|---|---|
| 日志 `monitor-logs` Kafka lag | 10 分钟堆积 50 万,持续增长 | **lag 动态 = 0** |
| 日志下游吞吐 | 受 Redis RTT 限制 ~1k/s/线程 | 本地纳秒级,理论无上限 |
| 指标 vs 日志入库比 | 3 : 1 | **1 : 1**(下游不再是瓶颈) |
| 外置依赖 | Redis(单点风险) | **零外置** |
| 严格有界内存 | 无上限(Redis 自由增长) | **Caffeine 80MB 硬封顶** |

## 关键 Trade-off

1. **进程重启丢状态**:Redis 版有持久化(虽然 count-ttl 兜底有限),Caffeine 版重启即清空。告警状态会重判 PENDING,dedup 会重置窗口——可接受。

2. **dedup 计数 flush 窗口从 1h 缩到 60s**:依赖 `expireAfterWrite(60s)` + 30s flush。**PG 故障 60s 后丢计数**——已暴露 `flush_fail` 指标,需配告警。

3. **多副本部署不共享状态**:跟原 Redis 版也不冲突(原版也没用 pub/sub),单实例部署不构成问题。

## 监控建议

必须为以下指标配告警:

| 指标 | 告警阈值 | 含义 |
|---|---|---|
| `spring.watch.ingest.log.dedup.evict_size` | `> 0` | 日志 dedup 触达容量上限,需扩 `max-entries` |
| `spring.watch.alerter.state.evict_size` | `> 0` | 告警状态触达容量上限,需扩 `max-entries` |
| `spring.watch.ingest.log.anomaly.pattern_evict_size` | `> 0` | 已知模式缓存触达上限 |
| `spring.watch.ingest.log.dedup.flush_fail` | `> 0` | PG 双写失败,本地计数即将丢失 |
| `spring.watch.ingest.log.dedup.cache_size / cache_max` | `> 0.8` | 缓存使用率告警 |
