# HertzBeat 项目 Redis 使用场景分析

## 一、总体概览

HertzBeat 项目使用 **Lettuce**(非 Spring Data Redis,非 Redisson)作为唯一 Redis 客户端,核心用途有 **2 大类** + **1 个旁支场景**:

| # | 场景 | 用途 | 关键模块 |
|---|------|------|---------|
| 1 | **实时数据存储** | 用 Redis Hash 存储采集到的最新监控指标 | `hertzbeat-warehouse` |
| 2 | **消息队列** | 用 Redis List 作为指标/日志数据的消息中间件 | `hertzbeat-common-spring` |
| 3 | **Redis 自身监控** | 监控 Redis 服务,采集 INFO/CLUSTER INFO | `hertzbeat-collector-basic` |

> **未使用**的能力:`@Cacheable` 缓存注解、`RedisTemplate`、`RedissonClient`、Jedis、分布式锁、限流、Session 共享、发布订阅、Lua 脚本、Stream、Pipeline。

---

## 二、配置相关

### 2.1 依赖声明(pom.xml)

显式依赖 `io.lettuce:lettuce-core`,**未引入** spring-data-redis / jedis / redisson。

| 模块 | 文件:行号 | scope |
|------|----------|-------|
| `hertzbeat-common-core` | `pom.xml:68-72` | compile |
| `hertzbeat-alerter` | `pom.xml:93-98` | provided |
| `hertzbeat-warehouse` | `pom.xml:146-151` | provided |
| `hertzbeat-collector-basic` | `pom.xml:116-120` | compile |

### 2.2 应用配置(application.yml)

**A. 消息队列配置** `hertzbeat-startup/src/main/resources/application.yml:139-161`

```yaml
common:
  queue:
    type: redis   # 默认使用 redis 作为消息队列
    redis:
      redis-host: 127.0.0.1
      redis-port: 6379
      metrics-data-queue-name-for-service-discovery: service_discovery
      metrics-data-queue-name-to-persistent-storage: metrics:to_persistent_storage
      metrics-data-queue-name-to-alerter: metrics:to_alerter
      metrics-data-queue-name-to-real-time-storage: metrics:to_realtime_storage
      alerts-data-queue-name: alerts
      log-entry-queue-name: log:to_alerter
      log-entry-to-storage-queue-name: log:to_storage
```

**B. 实时存储配置** `application.yml:262-276`

```yaml
warehouse:
  real-time:
    redis:
      enabled: true          # 默认开启 redis 作为实时存储
      mode: single           # 支持 single / sentinel / cluster
      address: 127.0.0.1:6379
      masterName: mymaster
      password: 123456
      db: 0
```

### 2.3 Docker Compose 编排

`docker-compose.yml:25-40`:Redis 7 容器,启用 AOF 持久化(`--appendonly yes`),挂载 `./.data/redis:/data`。

### 2.4 配置类

| 配置类 | 文件:行号 | 说明 |
|-------|----------|------|
| `CommonProperties.RedisProperties` | `hertzbeat-common-spring/.../CommonProperties.java:79-133` | 消息队列 redis 配置 |
| `RedisProperties`(record) | `hertzbeat-warehouse/.../store/realtime/redis/RedisProperties.java:30-41` | 实时存储 redis 配置 |
| `RedisProtocol` | `hertzbeat-common-core/.../entity/job/protocol/RedisProtocol.java:38-93` | 监控目标的 redis 协议配置 |
| `DataQueueConstants` | `hertzbeat-common-core/.../constants/DataQueueConstants.java:23-54` | 含 `String REDIS = "redis"` |
| `WarehouseConstants.RealTimeName.REDIS` | `hertzbeat-warehouse/.../constants/WarehouseConstants.java:58-63` | 实时存储后端名常量 |

---

## 三、核心使用场景

### 场景 1:实时监控指标数据存储(Hash)

**用途**:Redis Hash 缓存每个监控任务(monitorId)最近一次采集到的各 metric 指标数据,供前端实时查询面板使用。

**数据模型**:
- **Key** = `String.valueOf(monitorId)`(监控任务 ID)
- **Field** = `metricsData.getMetrics()`(指标名,如 `cpu`/`memory`)
- **Value** = `CollectRep.MetricsData`(Arrow IPC 序列化字节)

**Redis 命令**:`HGET`、`HGETALL`、`HSET`(异步)

#### 主类:RedisDataStorage

`hertzbeat-warehouse/.../store/realtime/redis/RedisDataStorage.java:39-82`

- 继承 `AbstractRealTimeDataStorage`
- `@ConditionalOnProperty(prefix="warehouse.real-time.redis", name="enabled", havingValue="true")`
- `@Primary` 标记

| 方法 | 行号 | 命令 | 说明 |
|------|------|------|------|
| `getCurrentMetricsData(Long, String)` | 50-52 | `HGET` | 单个 metric 查询 |
| `getCurrentMetricsData(Long)` | 54-58 | `HGETALL` | 整个 monitor 查询 |
| `saveData(MetricsData)` | 60-76 | `HSET` 异步 | 仅 `code==SUCCESS` 时写入,回调中 `metricsData.close()` 释放 Arrow 资源 |
| `destroy()` | 78-81 | - | 关闭底层 client |

关键代码 `RedisDataStorage.java:60-76`:
```java
@Override
public void saveData(CollectRep.MetricsData metricsData) {
    String key = String.valueOf(metricsData.getId());
    String hashKey = metricsData.getMetrics();
    if (metricsData.getCode() == CollectRep.Code.SUCCESS) {
        redisCommandDelegate.operate().hset(key, hashKey, metricsData, future -> future.thenAccept(response -> {
            metricsData.close();
            if (response) {
                log.debug("[warehouse] redis add new data {}:{}.", key, hashKey);
            } else {
                log.debug("[warehouse] redis replace data {}:{}.", key, hashKey);
            }
        }));
    } else {
        metricsData.close();
    }
}
```

#### 客户端委派层

| 类 | 文件:行号 | 说明 |
|----|----------|------|
| `RedisCommandDelegate` | `.../client/RedisCommandDelegate.java:32-75` | 单例(`private static final INSTANCE`),根据 `mode` 选择 Simple/Sentinel/Cluster 实现 |
| `RedisClientOperation<K,V>` | `.../client/RedisClientOperation.java:29-37` | 统一接口:`connect/hget/hgetAll/hset`,`AutoCloseable` |

#### 三种连接实现

| 类 | 文件:行号 | 模式 |
|----|----------|------|
| `RedisSimpleClientImpl` | `.../client/impl/RedisSimpleClientImpl.java:40-90` | **single** 单机,`RedisClient.create(uri)`,10s 超时 |
| `RedisSentinelClientImpl` | `.../client/impl/RedisSentinelClientImpl.java:43-117` | **sentinel** 哨兵,先 `getMasterAddrByName()` 再连 master |
| `RedisClusterClientImpl` | `.../client/impl/RedisClusterClientImpl.java:41-95` | **cluster** 集群,`RedisClusterClient.create(Set<RedisURI>)` |

#### 自定义序列化(Lettuce RedisCodec)

| 类 | 文件:行号 | 序列化方式 |
|----|----------|----------|
| `RedisMetricsDataCodec` | `hertzbeat-common-core/.../serialize/RedisMetricsDataCodec.java:42-106` | **Apache Arrow Stream**(`ArrowStreamWriter`/`ArrowStreamReader`) |
| `RedisLogEntryCodec` | `hertzbeat-common-core/.../serialize/RedisLogEntryCodec.java:33-66` | **JSON**(`JsonUtil.toJson/fromJson`) |

---

### 场景 2:消息队列(List,替代 Kafka/内存队列)

**用途**:通过 Redis List + `LPUSH`/`BRPOP` 实现"采集器→告警/持久化/服务发现/日志消费者"之间的解耦传输。

**Redis 命令**:`LPUSH`(生产)、`BRPOP`(阻塞消费)、`RPOP count`(批量)

#### 队列清单(来自 application.yml)

| 业务 | 队列名(默认) | 类型 |
|------|------------|------|
| 指标→Alerter | `metrics:to_alerter` | List |
| 指标→持久化存储 | `metrics:to_persistent_storage` | List |
| 指标→实时存储 | `metrics:to_realtime_storage` | List |
| 指标→服务发现 | `service_discovery` | List |
| 告警事件 | `alerts` | List |
| 日志→Alerter | `log:to_alerter` | List |
| 日志→存储 | `log:to_storage` | List |

#### 主类:RedisCommonDataQueue

`hertzbeat-common-spring/.../queue/impl/RedisCommonDataQueue.java:52-242`

- `@Configuration` + `@ConditionalOnProperty(prefix=common.queue, name=type, havingValue=redis)`
- 实现 `CommonDataQueue` 与 `DisposableBean`
- 共用一个 `RedisClient`、双连接(指标 + 日志各一条 `StatefulRedisConnection`,使用不同 codec)

##### 关键方法与 Redis 命令映射

| 方法 | 行号 | Redis 命令 | 队列 Key |
|------|------|----------|---------|
| `sendMetricsData(MetricsData)` | 113-119 | `LPUSH` | `metrics:to_alerter` |
| `sendMetricsDataToStorage(MetricsData)` | 122-128 | `LPUSH` | `metrics:to_persistent_storage` |
| `sendServiceDiscoveryData(MetricsData)` | 131-137 | `LPUSH` | `service_discovery` |
| `sendLogEntry(LogEntry)` | 140-146 | `LPUSH` | `log:to_alerter` |
| `sendLogEntryToStorage(LogEntry)` | 154-160 | `LPUSH` | `log:to_storage` |
| `sendLogEntryToAlertBatch(List<LogEntry>)` | 169-178 | `LPUSH` 批量 | `log:to_alerter` |
| `sendLogEntryToStorageBatch(List<LogEntry>)` | 187-196 | `LPUSH` 批量 | `log:to_storage` |
| `pollMetricsDataToAlerter()` | 97-99 | `BRPOP` | `metrics:to_alerter` |
| `pollMetricsDataToStorage()` | 102-105 | `BRPOP` | `metrics:to_persistent_storage` |
| `pollServiceDiscoveryData()` | 108-110 | `BRPOP` | `service_discovery` |
| `pollLogEntry()` | 149-151 | `BRPOP` | `log:to_alerter` |
| `pollLogEntryToStorage()` | 163-165 | `BRPOP` | `log:to_storage` |
| `pollLogEntryToAlertBatch(int)` | 181-183 | `RPOP key count` | `log:to_alerter` |
| `pollLogEntryToStorageBatch(int)` | 199-201 | `RPOP key count` | `log:to_storage` |

##### 阻塞消费 `RedisCommonDataQueue.java:212-227`

```java
private <T> T genericBlockingPollFunction(String key, RedisCommands<String, T> commands) throws InterruptedException {
    try {
        // Use BRPOP for blocking pop with the configured timeout.
        KeyValue<String, T> keyData = commands.brpop(waitTimeout, key);
        return keyData != null ? keyData.getValue() : null;
    } catch (Exception e) {
        log.error("Redis BRPOP failed: {}", e.getMessage());
        throw new CommonDataQueueUnknownException(e.getMessage(), e);
    }
}
```

##### 批量取 `RedisCommonDataQueue.java:229-240`

```java
private List<LogEntry> genericBatchPollFunction(String key, RedisCommands<String, LogEntry> commands, int maxBatchSize) {
    List<LogEntry> batch = new ArrayList<>(maxBatchSize);
    try {
        List<LogEntry> elements = commands.rpop(key, maxBatchSize);  // RPOP key count 一次拿一批
        if (elements != null) batch.addAll(elements);
    } catch (Exception e) {
        log.error("Redis batch poll failed: {}", e.getMessage());
    }
    return batch;
}
```

##### 销毁 `RedisCommonDataQueue.java:203-208`

```java
public void destroy() {
    connection.close();
    logEntryConnection.close();
    redisClient.shutdown();
}
```

---

### 场景 3:Redis 服务自身监控(INFO / CLUSTER INFO)

**用途**:HertzBeat 作为监控系统,提供对 Redis 服务(含单机/哨兵/集群)的指标采集能力。属于"用 Redis 客户端采集 Redis"。

#### 涉及类

| 类 | 文件:行号 | 说明 |
|----|----------|------|
| `RedisCommonCollectImpl` | `hertzbeat-collector/.../collect/redis/RedisCommonCollectImpl.java:66-383` | 单机+哨兵+集群通用采集器 |
| `RedisConnect` | `hertzbeat-collector-common/.../cache/RedisConnect.java:27-46` | 连接缓存包装,包装 `StatefulConnection<String,String>` |
| `RedisProtocol` | `hertzbeat-common-core/.../entity/job/protocol/RedisProtocol.java:38-93` | 采集参数:host/port/username/password/pattern/sshTunnel |
| `app-redis.yml` | `hertzbeat-manager/src/main/resources/define/app-redis.yml` | 监控模板:11 组指标 |
| `app-kvrocks.yml` | `hertzbeat-manager/src/main/resources/define/app-kvrocks.yml` | KVRocks(Redis 兼容协议)监控模板 |

#### SPI 注册

`hertzbeat-collector-collector/src/main/resources/META-INF/services/org.apache.hertzbeat.collector.collect.AbstractCollect:6`:
```
org.apache.hertzbeat.collector.collect.redis.RedisCommonCollectImpl
```

#### 关键方法

| 方法 | 行号 | Redis 命令 | 备注 |
|------|------|----------|------|
| `collect(MetricsData.Builder, Metrics)` | 93-121 | 总入口 | 按 `pattern`(1=single, 3=cluster)分支 |
| `getSingleRedisInfo(Metrics)` | 128-137 | `INFO <section>` | 解析为 `Map<String,String>` |
| `getClusterRedisInfo(Metrics)` | 144-162 | `INFO` + `CLUSTER INFO` | 遍历每个分片 |
| `getSingleConnection(RedisProtocol)` | 198-212 | 连接复用 | `GlobalConnectionCache` 复用 `StatefulRedisConnection` |
| `getConnectionList(RedisProtocol)` | 219-239 | 集群遍历 | `connection.getPartitions()` 逐一连接 |
| `redisUri(RedisProtocol, host, port)` | 308-319 | 构造 `RedisURI` | 含可选 username/password/timeout |
| `parseInfo(String, Metrics)` | 339-360 | 解析 INFO | 按行 `split(":")` |
| `resolveHostAndPort(RedisProtocol)` | 362-376 | SSH 隧道 | `SshTunnelHelper.localPortForward(...)` |
| `supportProtocol()` | 379-381 | 返回 `"redis"` | - |

#### 集群模式判断 `RedisCommonCollectImpl.java:95-101`

```java
if (Objects.nonNull(metrics.getRedis().getPattern()) && Objects.equals(metrics.getRedis().getPattern(), CLUSTER)) {
    List<Map<String, String>> redisInfoList = getClusterRedisInfo(metrics);
    doMetricsDataList(builder, redisInfoList, metrics);
} else {
    Map<String, String> redisInfo = getSingleRedisInfo(metrics);
    doMetricsData(builder, redisInfo, metrics);
}
```

#### 单点连接建立 `RedisCommonCollectImpl.java:198-212`

```java
private StatefulRedisConnection<String, String> getSingleConnection(RedisProtocol redisProtocol) throws ... {
    String[] resolvedArr = resolveHostAndPort(redisProtocol);
    String host = resolvedArr[0];
    String port = resolvedArr[1];

    CacheIdentifier identifier = doIdentifier(redisProtocol, host, port);
    StatefulRedisConnection<String, String> connection = (StatefulRedisConnection<String, String>) getStatefulConnection(identifier);
    if (Objects.isNull(connection)) {
        RedisClient redisClient = buildSingleClient(redisProtocol, host, port);
        connection = redisClient.connect();
        connectionCache.addCache(identifier, new RedisConnect(connection));
    }
    return connection;
}
```

#### 监控指标分组(11 组)

`server` / `clients` / `memory` / `persistence` / `stats` / `replication` / `cpu` / `errorstats` / `cluster` / `commandstats` / `keyspace`

对应命令:`INFO server` ... `INFO keyspace`,集群组额外调 `CLUSTER INFO`。

---

## 四、调用链路串联

### 4.1 实时数据流(实时存储 + 队列双管齐下)

```
[Collector 采集] ──▶ MetricsData(CollectRep)
        │
        ▼
[TimerDispatcher → CommonDispatcher]
        │
        ▼
[DataStorageDispatch.startPersistentDataStorage] (hertzbeat-warehouse/.../DataStorageDispatch.java:74-111)
        │
        ├─▶ commonDataQueue.pollMetricsDataToStorage()   ── BRPOP ──▶ Redis List "metrics:to_persistent_storage"
        │
        ├─▶ historyDataWriter.saveData()  ──▶ VictoriaMetrics / InfluxDB / ...
        │
        └─▶ realTimeDataWriter.saveData() ── HSET ──▶ Redis Hash {monitorId: metric → MetricsData}
                                                       (RedisDataStorage.saveData)
```

### 4.2 告警/日志数据流

```
[Alerter 端] 告警产生 ── LPUSH ──▶ Redis List "alerts"(配置项保留)
                            LPUSH ──▶ Redis List "log:to_alerter"   (RedisCommonDataQueue.sendLogEntry)
                                       │
                                       ▼ BRPOP
                            [Alerter WindowedLogRealTimeAlertCalculator]
                            [warehouse startLogDataStorage → log:to_storage]
```

---

## 五、未使用的能力清单

| 能力 | 是否使用 | 备注 |
|------|---------|------|
| `@Cacheable/@CacheEvict/@CachePut` | ❌ | 未引入 spring-boot-starter-cache |
| `RedisTemplate` / `StringRedisTemplate` | ❌ | 无 `import org.springframework.data.redis.*` |
| `RedissonClient` | ❌ | 无 `import org.redisson.*` |
| Jedis | ❌ | 无 `import redis.clients.jedis.*` |
| 分布式锁(SET NX / Redisson Lock) | ❌ | - |
| 限流(INCR + EXPIRE、Lua 滑动窗口、令牌桶) | ❌ | - |
| Session 共享 | ❌ | 未集成 spring-session |
| 发布订阅(PUBLISH/SUBSCRIBE) | ❌ | - |
| Redis Stream(XADD/XREAD) | ❌ | - |
| Lua 脚本(EVAL) | ❌ | - |
| HyperLogLog / GEO / Bitmap | ❌ | - |
| Pipeline / Transaction | ❌ | 仅用 sync() / async() |
| 持久化机制(RDB/AOF) | ❌ 业务侧不感知 | 仅 docker-compose 启用 AOF |

---

## 六、关键文件清单速查

| 文件路径 | 行数 | 核心职责 |
|---------|------|---------|
| `hertzbeat-warehouse/.../store/realtime/redis/RedisProperties.java` | 41 | 实时存储 redis 配置(record) |
| `hertzbeat-warehouse/.../store/realtime/redis/RedisDataStorage.java` | 82 | 实时数据 HSET/HGET 入口 |
| `hertzbeat-warehouse/.../client/RedisCommandDelegate.java` | 75 | 单例委派,按模式选 client |
| `hertzbeat-warehouse/.../client/RedisClientOperation.java` | 37 | 操作接口(hget/hgetAll/hset) |
| `hertzbeat-warehouse/.../client/impl/RedisSimpleClientImpl.java` | 90 | single 模式 |
| `hertzbeat-warehouse/.../client/impl/RedisSentinelClientImpl.java` | 117 | sentinel 模式 |
| `hertzbeat-warehouse/.../client/impl/RedisClusterClientImpl.java` | 95 | cluster 模式 |
| `hertzbeat-common-spring/.../queue/impl/RedisCommonDataQueue.java` | 242 | 消息队列(LPUSH/BRPOP) |
| `hertzbeat-common-spring/.../config/CommonProperties.java` | 167 | common.queue.redis 配置类 |
| `hertzbeat-common-core/.../serialize/RedisMetricsDataCodec.java` | 106 | Arrow 编解码 |
| `hertzbeat-common-core/.../serialize/RedisLogEntryCodec.java` | 67 | JSON 编解码 |
| `hertzbeat-collector/.../collect/redis/RedisCommonCollectImpl.java` | 383 | Redis 服务自身指标采集 |
| `hertzbeat-collector-common/.../cache/RedisConnect.java` | 46 | 采集侧连接缓存包装 |
| `hertzbeat-common-core/.../entity/job/protocol/RedisProtocol.java` | 93 | 采集协议参数 |
| `hertzbeat-collector-collector/.../META-INF/services/...AbstractCollect` | 38 | SPI 注册 |
| `hertzbeat-manager/src/main/resources/define/app-redis.yml` | 1658 | Redis 监控模板(11 组指标) |
| `hertzbeat-manager/src/main/resources/define/app-kvrocks.yml` | - | KVRocks 监控模板 |
| `hertzbeat-startup/src/main/resources/application.yml` | 379 | 运行时配置 |
| `hertzbeat-startup/src/main/resources/application-test.yml` | 223 | 测试环境配置(redis 实时存储 disabled) |
| `docker-compose.yml` | 110 | Redis 7 容器编排(AOF) |
