# mock-test 行为丰富 + 自动化方案

> 目标:把"3 用户 / 5 商品 / 3 订单"的死数据,变成能持续触发 spring-watch 全链路指标、日志、告警的"活"应用,并让这一切能在 `mvn package` / `docker compose up` 之后**自动**运转,不需要人工一直点按钮。

---

## 0. 现状诊断 — 为什么"jdbc 数据很平淡"

逐项对照前端 `JdbcPane.vue` 需要的指标,再去看 mock-test 现在能不能产出:

| 前端要的指标 | OTel 指标名 | 触发条件 | 现状 | 平淡根因 |
|---|---|---|---|---|
| 连接池 max | `db_client_connections_max` | 启动后由 HikariCP 暴露 | 1 个连接(单机 H2) | **max=1**, 任何一次使用率都会 0%/100% 跳,曲线无意义 |
| 连接池 idle/used | `db_client_connections_usage{state=idle/used}` | 每次执行 SQL | 几乎恒为 idle=1, used=0 | **QPS 太低**(无后台流量),`/api/ping` 都不走 DB |
| 等待请求 | `db_client_connections_pending_requests` | 连接被占满时 | 永远是 0 | 永远占不满, 没人抢锁 |
| QPS | `db_client_connections_use_time_milliseconds_count`(rate) | 每次 borrow 连接 | 永远是 0 | 没有后台定时 SQL |
| use/wait/create P50/95/99 | OTel 自动直方图 | 有 borrow/return 时 | 没有分位数据 | borrow 频率 < 1次/分, 直方图桶都是空 |
| 慢查询 | 业务自定义 `@WithSpan` + duration | 业务侧主动埋点 | 没埋点 | 没人写慢 SQL, 也没人 sleep 后再查 |
| 业务指标丰富度 | 4 个 counter | createOrder / payOrder | 长时间不变 | 没人**自动**调订单接口 |

**根因一句话**:mock-test 是一个**纯响应式**应用 — 你不点,它就不动;它不动,JDBC / 业务 / 日志 / 告警就都死。

**次要根因**:
1. 数据库只有 3 订单, 任何"总览"类 SQL( `COUNT(*)` / `SUM(amount)` )在曲线图上都是一条**横线**
2. 业务日志只有入口/出口过滤器打的,没有"业务过程"日志(创建订单成功/失败/异常)
3. 没有错误/异常路径的注入, 告警引擎**永远**收不到告警
4. 商品库存/订单状态从不自动流转, 缺一个"时间维度"的事件流

---

## 1. 目标 — 丰富到什么程度

### 1.1 必达指标(前后端面板必须有数据)

| 面板 | 必达行为 |
|---|---|
| **JdbcPane** | 1) 后台持续有 SQL 流量(>5 QPS),让 use/idle 出现 30/70 比例波动;2) 周期性触发"连接池打满"场景,看到 pending>0;3) 偶发"慢 SQL", use_time 出现 P99 离群点 |
| **HttpPane** | 1) 后台持续有 HTTP 流量;2) 4xx/5xx 出现,让 errorRate 有曲线;3) 多 route 分布, 让 TopN 柱图有内容 |
| **JvmPane** | 1) 周期触发 minor GC(分配速率);2) 偶发 full GC(分配大对象);3) class_loaded 有增量 |
| **OsPane** | 后台持续 IO(写日志/写库), 让 disk_io_bytes、net_io_bytes 有数据 |
| **LogsView** | 1) INFO/WARN/ERROR 各档位都有;2) 出现"错误突增"场景;3) 出现"已知/未知模式"日志 |
| **AlertHistory** | 周期性触发"真实报警条件" — 错误率、慢查询、连接池打满 — 让告警状态机有可观察的 IDLE→PENDING→FIRING→RESOLVED 流转 |

### 1.2 非目标(避免过度工程)

- ❌ 不做真实业务逻辑(下单/支付/库存), 只要"事件能跑通"
- ❌ 不接入真实外部依赖(消息队列、第三方支付、Redis 缓存)
- ❌ 不重写 OTel agent(那是另一个大项目)
- ❌ 不引入 Guava/Avro 等重依赖

---

## 2. 行为丰富 — 4 个核心"自动化场景"

每个场景都是**纯后台 ScheduledExecutor**,不依赖任何人工请求。

### 2.1 场景 A: 持续小流量 (`SteadyTrafficSimulator`)

**目的**: 让所有"无操作时恒为 0"的指标在静态下也有曲线。

**频率**: 1s 一次,每次 1~5 个随机只读 SQL(`SELECT COUNT(*) FROM orders`, `SELECT * FROM products LIMIT N`, `SELECT * FROM users WHERE id=?`)

**产出指标**:
- `db_client_connections_use_time_milliseconds_count` ↑ (QPS ≈ 1~5)
- `db_client_connections_usage{state=used}` 出现短暂尖峰
- `http_server_request_duration_seconds` (通过 `RestApiAccessLogFilter` 间接触发 — 走 `GET /api/products` 等只读接口)
- 业务日志: `INFO` 级别 1 行/秒

**实现**: 一个 `@Component` + `@PostConstruct` 启动 `ScheduledExecutorService`,shutdown hook 关闭。

### 2.2 场景 B: 周期性业务事件 (`BusinessEventSimulator`)

**目的**: 让"业务指标"和"订单状态时序"有数据。

**频率**: 5~15s 一次,随机选一种行为:

| 行为 | 概率 | 调用的 API / DAO | 触发的指标 |
|---|---|---|---|
| 创建订单 | 30% | `orderDao.save` | `business.order.created` +1, `business.order.amount` 落点 |
| 支付订单(随机选一个 created) | 25% | `orderDao.updateStatus` | `business.order.paid` +1, `pay_time` 写入 |
| 发货(随机 paid) | 15% | 同上 | `ship_time` 写入 |
| 完成(shipped) | 10% | 同上 | `complete_time` 写入 |
| 取消(created/paid) | 5% | 同上 | `cancel_time` 写入 |
| 加购物车 | 10% | `cartService.addToCart` | 无新指标,但日志有 |
| 异常路径(库存不足) | 5% | 故意传 productId=9999 | 触发业务 4xx,产生**错误日志** |

**前置**: 因为有写操作,需要把 `OrderService.createOrder` 暴露给 scheduler(已经是 public),但需要一个"无 userId" 模式 — 自动选 `userId ∈ {1,2,3}`,商品 `id ∈ {1..5}`。

**实现**: `@Component BusinessEventSimulator` 调 `orderService.createOrder` 即可,无需改 service。

### 2.3 场景 C: 连接池打满 + 慢查询 (`StressScenarioSimulator`)

**目的**: 制造**异常**指标,让告警能真实触发。

**两个子场景,互斥触发**:

#### C-1: 连接池打满 (每 60s 一次,持续 3s)

原理: H2 默认 `maxPoolSize=10`, 我们发 30 个并发 SQL 抢锁。
- 启动 30 个线程, 每个执行 `SELECT pg_sleep(0.1)` (H2 等价 `CALL SLEEP(100)`)
- 抢不到的连接,会进入 `pending_requests`, 指标出现 3s 的高平台
- 结束后, 等待线程陆续返回, pending→0

**产出**:
- `db_client_connections_pending_requests` 出现 3s 高峰
- `db_client_connections_wait_time_milliseconds_sum/count` 暴涨
- 触发日志: `WARN  连接池压力测试已启动 30并发`

#### C-2: 慢查询 (每 90s 一次)

原理: 故意发一个耗时 SQL, 让 use_time 出现 P99 离群点。
- `SELECT *, SLEEP(2) FROM products` (H2 1.4.200+ 支持 `SLEEP`)
- 或者用代码 `Thread.sleep(2000)` 包裹在 service 里,但那样 OTel 拦不到 JDBC 时长
- 推荐: H2 直接执行 `SELECT SLEEP(2000)` (返回 1,耗时 2s), 走真实 JDBC 路径, OTel 拦截器记录真实时间

**产出**:
- `db_client_connections_use_time_milliseconds` 直方图出现 2000ms 桶
- P99 曲线出现一个尖刺
- 业务日志: `WARN  慢查询已注入 productId=3 duration=2003ms`

**告警联动**: 当 C-1/C-2 出现时, 错误率 / 慢查询告警规则会真的触发, 可以在 AlertHistory 页面看到。

### 2.4 场景 D: 错误突增 (`LogBurstSimulator`)

**目的**: 让 LogAggregator / LogAnomalyDetector / 错误率告警有真实数据。

**两个子场景**:

#### D-1: WARN 突增 (每 120s 一次, 持续 5s)
每秒打 10 条 `WARN`,模式一致(同一 fingerprint),走已知模式路径。
让 `patternsBar` (TopN) 有数据。

#### D-2: ERROR 突增 (每 300s 一次, 持续 5s)
每秒打 5 条 `ERROR`,模式**每次不同**,走异常检测路径。
让 `errorRate` 在窗口内暴涨, 触发 `log_error_rate` 告警。

**日志模板** (要确保能被 LogSanitizer / LogFingerprinter 处理):
```
ERROR [kxj: 模拟异常路径] type=NullPointerException path=/api/orders/{id} traceId=auto-{n} - 用户不存在
ERROR [kxj: 慢查询告警] query=SELECT * FROM huge_table duration={ms}ms userId={id} - 超过阈值
WARN  [kxj: 库存不足] productId={id} requested={qty} available=0 - 回滚订单
```

**实现**: 复用 `logback` 的 `Logger` 即可,`InMemoryLogBufferAppender` 会自动接住, 平台通过 `/api/agent/logs?since=` 拉取。

---

## 3. 自动化方案 — 让 4 个场景"装上就跑,卸载即停"

### 3.1 启用控制 — 单一开关

不引入额外配置文件,直接用 `application.yml` 加 4 个开关,**默认全开**:

```yaml
mock:
  sim:
    enabled: true                # 总开关
    steady-interval-ms: 1000     # 场景A 周期
    business-interval-ms: 8000   # 场景B 周期
    stress-pool-burst-ms: 60000  # 场景C-1 周期
    stress-slow-query-ms: 90000  # 场景C-2 周期
    log-warn-burst-ms: 120000    # 场景D-1 周期
    log-error-burst-ms: 300000   # 场景D-2 周期
```

每个 Simulator 自己 `@ConditionalOnProperty` — 改 `mock.sim.enabled=false` 或单独关 `mock.sim.business-interval-ms=0` 即可停某个场景。

### 3.2 启动顺序 — 用 `@DependsOn` + `@PostConstruct`

```java
@Component
@DependsOn({"businessMetrics", "orderService"})  // 业务指标 bean 必须先就绪
public class SteadyTrafficSimulator {
    @PostConstruct
    void start() { /* executor.scheduleAtFixedRate */ }

    @PreDestroy
    void stop() { executor.shutdownNow(); }
}
```

启动顺序(用 `@Order` 注解,数值小的先跑):
1. `Order(1)` `SteadyTrafficSimulator` — 先打底流量
2. `Order(2)` `BusinessEventSimulator` — 再写业务
3. `Order(3)` `StressScenarioSimulator` — 最后压测
4. `Order(4)` `LogBurstSimulator` — 错误日志

### 3.3 优雅停机 — 用 `ScheduledExecutorService` 而非 `@Scheduled`

为什么不用 `@Scheduled`:
- `@Scheduled` 启动早于 `OrderService` 的连接池就绪,首次执行容易 NPE
- `@Scheduled` 关停时不感知 Spring 生命周期
- 我们要的是"可以手动调下一次触发时间"和"可以独立 stop",`ScheduledExecutorService` 更合适

实现模板(每个 Simulator 都一样):

```java
@Slf4j
@Component
@ConditionalOnProperty(name = "mock.sim.enabled", havingValue = "true", matchIfMissing = true)
public class SteadyTrafficSimulator {

    private final JdbcTemplate jdbc;
    private final ScheduledExecutorService exec =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sim-steady");
            t.setDaemon(true);
            return t;
        });

    @Value("${mock.sim.steady-interval-ms:1000}")
    private long intervalMs;

    @PostConstruct
    void start() {
        log.info("[kxj: Simulator启动] steady interval={}ms", intervalMs);
        exec.scheduleAtFixedRate(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        log.info("[kxj: Simulator关闭] steady");
        exec.shutdownNow();
    }

    void tick() {
        try { /* 随机只读 SQL */ }
        catch (Exception e) { log.debug("sim tick fail", e); }
    }
}
```

### 3.4 容器化兼容 — `OTEL_ENABLED` 影响

`OTEL_ENABLED=true` 启动时, OTel agent 已经把 HikariCP 拦截了, 我们的连接池打满场景**依然有效**(OTel 只读不改)。

**唯一注意点**: 启动初期(前 30s)OTel 的 metrics 端点可能未就绪, 我们的 `healthcheck` 已经用 `start_period: 30s` 处理, 无需额外逻辑。

### 3.5 关闭/开启的运行方式

```bash
# 全关(回到原样,纯响应式)
SPRING_APPLICATION_JSON='{"mock":{"sim":{"enabled":false}}}' \
  java -jar mock-test-1.0.0.jar

# 单独关掉慢查询
SPRING_APPLICATION_JSON='{"mock":{"sim":{"stress-slow-query-ms":0}}}' \
  java -jar mock-test-1.0.0.jar

# Docker compose 注入
environment:
  - SPRING_APPLICATION_JSON={"mock":{"sim":{"enabled":true}}}
```

---

## 4. 自动化"端到端验证" — 配套测试脚本

> mock-test 仓库内自带一个 `scripts/verify-sim.sh` 脚本,**启动后跑一次**, 5 分钟内就能看出指标是否真的丰富了。

```bash
#!/usr/bin/env bash
# scripts/verify-sim.sh
# 启动 mock-test, 拉取 /metrics, 检查关键指标是否存在且非零
set -e

ENDPOINT="${1:-http://localhost:18081}"
echo ">>> 等待 $ENDPOINT 就绪..."
for i in $(seq 1 30); do
  if curl -sf "$ENDPOINT/api/ping" > /dev/null; then break; fi
  sleep 2
done

echo ">>> 拉取 $ENDPOINT:9464/metrics (OTel)"
METRICS=$(curl -sf "http://localhost:19464/metrics" || echo "")

check() {
  local name="$1"
  local expect_min="$2"  # 期望最小值(粗略)
  local val=$(echo "$METRICS" | grep -E "^$name" | awk '{print $2}' | head -1)
  if [ -z "$val" ]; then
    echo "  [缺] $name  —— 该指标未暴露"
  else
    echo "  [有] $name = $val"
  fi
}

echo "=== JDBC 指标 ==="
check "db_client_connections_max" 0
check "db_client_connections_usage" 0
check "db_client_connections_pending_requests" 0
check "db_client_connections_use_time_milliseconds_count" 0

echo "=== 业务指标 ==="
check "business_order_created_total" 0
check "business_order_paid_total" 0
check "business_order_amount" 0

echo "=== JVM 指标(应有自动直方图) ==="
check "jvm_memory_used_bytes" 0
check "jvm_gc_duration_seconds_count" 0

echo ">>> 触发连接池打满场景..."
for i in $(seq 1 30); do
  curl -s "$ENDPOINT/api/delay/100" > /dev/null &
done
wait
sleep 5

echo ">>> 触发慢查询场景..."
curl -s "$ENDPOINT/api/delay/2500" > /dev/null
sleep 3

echo ">>> 拉取日志(检查是否有自动产生的日志)"
LOGS=$(curl -sf "$ENDPOINT/api/agent/logs?since=1970-01-01T00:00:00Z" || echo "[]")
ERR_COUNT=$(echo "$LOGS" | grep -c '"level":"ERROR"' || true)
WARN_COUNT=$(echo "$LOGS" | grep -c '"level":"WARN"' || true)
echo "  ERROR 日志数: $ERR_COUNT (期望 ≥ 1,说明 D-2 触发了)"
echo "  WARN  日志数: $WARN_COUNT (期望 ≥ 5,说明 D-1 / 业务异常触发)"

echo "=== 验证完成 ==="
```

预期输出(运行 5 分钟后):
```
[有] db_client_connections_max = 10
[有] db_client_connections_usage = ...
[有] db_client_connections_use_time_milliseconds_count = 234
[有] business_order_created_total = 12
[有] business_order_paid_total = 9
ERROR 日志数: 8
WARN  日志数: 47
```

---

## 5. 实施路线 — 4 步走,每步可独立验收

| 步骤 | 工作量 | 验收点 |
|---|---|---|
| **Step 1** 基础设施 | 30 min | `application.yml` 加 4 个开关,新建 `sim/` 包和 4 个空 `@Component` 骨架(只打日志) |
| **Step 2** 场景 A + B | 2 h | 跑 5 分钟,`db_client_connections_use_time_milliseconds_count` > 0,`business_order_created_total` > 0 |
| **Step 3** 场景 C | 1.5 h | 跑 10 分钟,触发连接池打满,`pending_requests` 出现尖峰;触发慢查询,use_time 直方图有离群点 |
| **Step 4** 场景 D + verify 脚本 | 1 h | 跑 15 分钟,ERROR 日志数 > 0,verify-sim.sh 全绿 |

**总工作量**: 半天。**每步完成后,前端面板都能多看到一组有数据的曲线。**

---

## 6. 风险与权衡

| 风险 | 影响 | 缓解 |
|---|---|---|
| 后台流量过大,拖慢 mock-test 自身 | 自监控曲线被自身污染 | 4 个 Simulator 默认 daemon 线程,QPS 总和控制在 30 以内,可调 |
| 容器内 Spring 关闭时线程没退出 | Docker 启动慢 | 所有 `ExecutorService` 都 `setDaemon(true)`,`@PreDestroy` 调 `shutdownNow()` |
| 错误日志突增,前端 LogSanitizer 误判 | 真实告警被噪音淹没 | 日志模板遵循现有格式 `[kxj: ...]`,不会和真实业务混 |
| C 场景可能让 HikariCP 真打满,卡住其他请求 | 健康检查失败 | `maxPoolSize` 调到 30,打满数设 20,留余量;healthcheck `start_period: 30s` 已就绪 |
| 行为丰富后,前后端联调场景被自动化"覆盖" | 人工按钮点击看不到预期效果 | 验证手册里写明:`SPRING_APPLICATION_JSON='{"mock":{"sim":{"enabled":false}}}'` 启动即回到响应式 |

---

## 7. 不做的事(明确划界)

1. ❌ **不**改 `OrderDao` / `OrderService` 的 SQL — 现有 SQL 已经能被 OTel 拦截
2. ❌ **不**改 `application.yml` 的 H2 配置 — 内存库足够,不需要切到 MySQL
3. ❌ **不**改 OTel agent 启动参数 — 现有配置已启用 `jdbc/hikaricp/executors`
4. ❌ **不**改前端 — 前端已经能渲染丰富数据,只是缺数据源
5. ❌ **不**做单元/集成测试覆盖 — 4 个 Simulator 都是 `Runnable`,出问题 `try/catch` 即可
6. ❌ **不**做用户可配置"剧情编排" — 4 个开关够用了,YAGNI

---

## 8. 总结 — 一句话回用户

**「JDBC 数据平淡」是因为 mock-test 是个死的纯响应式应用,加 4 个 daemon 后台 Simulator(持续小流量 + 业务事件 + 偶发压测 + 错误突增),用单一 `mock.sim.enabled` 开关控制,装上就跑,卸载即停,半天工作量,前端 4 个面板立刻有真实曲线可看。**
