# 告警层 Bug 扫描报告

> 扫描范围:`alerter/*` + `analysis/LogAlertScheduler` + `consumer/BatchAlertConsumer` + `service/AlertRuleService`
> 扫描时间:2026-06-16
> 共发现 **10 个 Bug**(4 严重 / 4 中等 / 2 轻微)

---

## 严重 Bug(P0 必修)

### Bug-1: 多个邮件收件人地址格式错误,SMTP 会拒收

**位置**:`alerter/AlertNotifier.java:102-120`

**问题**:
`lookupConfigTargets` 用 `Collectors.joining(",")` 拼接多个 target,得到形如 `"a@x.com,b@x.com"` 的字符串,然后直接传给 `msg.setTo(to)`。`SimpleMailMessage.setTo(String)` 的实现是 `this.to = new String[] { to }`,**整个逗号拼接串被当成单个收件人**——这是个无效的邮件地址,SMTP 服务器会直接 `5xx` 拒收,告警永远发不出去。

**触发场景**:
- 规则 `notifyChannels` 配 `{"email":"a@x.com,b@x.com"}`
- 或 `alert_notification_config` 表中同一个 appid 配了多条 `target`

**测试盲点**:
`AlertNotifierTest.multipleRecipients_commaSeparated` 错误地把这个错误行为"固化"为期望值:
```java
assertEquals("a@x.com,b@x.com", String.join(",", to));  // 错误!应拆分后断言
```

**修复建议**:
```java
String[] toArr = Arrays.stream(to.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toArray(String[]::new);
msg.setTo(toArr);
```
并同步修正该测试用例。

---

### Bug-2: `@Transactional` 在同类内部调用,事务不生效

**位置**:`alerter/AlertEngine.java:243-285`(方法 `fire` / `resolve`)

**问题**:
`@Transactional` 注解在 `protected` 方法上,且调用方是同类内部的 `evaluateRule` / `evaluateLogRule` / `handleRecover` / `fireFromScanner`(`this.fire()` / `this.resolve()`)。Spring AOP 代理 **不会拦截同类内部的方法调用**,导致 `@Transactional` 完全失效,等同于没有事务。

**实际影响**:
- 邮件发送失败时,`alert_history` 记录无法回滚——可能产生"邮件已发但 history 缺失"或"history 已写但邮件未发"的不一致
- `resolve` 写 `resolvedAt` 与 `notifier.notify` 之间异常,数据库半更新

**修复建议**(任选其一):
1. 抽到独立 `AlertNotifierService` 类,Spring 注入,公开方法
2. 改 self-injection:`@Lazy private final AlertEngine self;` 然后 `self.fire(...)`
3. 显式 `TransactionTemplate` 编程式事务

---

### Bug-3: 日志类告警触发后永远无法自动恢复

**位置**:`alerter/AlertEngine.java:117-143`(`evaluateLogRule`)

**问题**:
```java
if (!breached) {
    return;  // ← 直接 return,完全没走恢复流程
}
```
`log_keyword` / `log_new_pattern` 规则触发 FIRING 后,后续不匹配 keyword 的日志事件只会走到这个 early return,**永远不会触发 `handleRecover`**,告警永久停留在 FIRING 状态,也不会发恢复通知。`alert_history` 的 `resolvedAt` 也永远是 null。

**触发场景**:
- 配 `log_keyword=Exception` 规则
- 短时间内大量 Exception 日志触发告警
- 之后日志恢复正常,`log_keyword` 不再匹配
- 告警**永远**停留在 FIRING 状态

**修复建议**:
```java
if (!breached) {
    handleRecover(rule /* 需要 MetricEvent 适配 */, current, Instant.now());
    return;
}
```
需要让日志类规则也走和 metric 相同的 resolve 路径(`synthetic` 一下,或重构为只传 `ruleId+appid+level`)。

---

### Bug-4: 规则增/改/删后缓存不失效,延迟 ≤30s 才生效

**位置**:`service/AlertRuleService.java:26-100` + `alerter/AlertRuleCache.java`

**问题**:
`AlertRuleCache` 有 `@PostConstruct init()` 和 `@Scheduled(fixedDelay=30s) refresh()`,**但 `AlertRuleService.createRule` / `updateRule` / `deleteRule` 中并未调用 `ruleCache.invalidate()` / `ruleCache.refresh()`**。
- 新规则:最长 30s 才会被 `AlertEngine.process` 看到
- 删除/禁用规则:**最长 30s 内,已禁用规则的 PENDING 状态仍会被扫描器兜底 fire**(PENDINGStateScanner:102-108 会在 `findById` 后才检查 `status`,但 `AlertRuleCache` 缓存里 30s 内可能还会匹配到)
- 修改 `expression` / `thresholdValue`:最长 30s 才生效

**触发场景**:
- 用户在控制台新增规则后,前 30s 内发生的指标事件不会被新规则覆盖
- 紧急删除某规则后,30s 内扫描器可能基于旧 PENDING 状态发误告警

**修复建议**:
在 `AlertRuleService` 注入 `AlertRuleCache`,在三个写操作后调用 `ruleCache.refresh()`。

---

## 中等 Bug(P1 应修)

### Bug-5: PENDING 持续时长未到但已 fire,违反 `times` 语义

**位置**:`alerter/AlertEngine.java:200-218` + `alerter/AlertEngine.java:301-313`

**问题**:
事件路径和扫描器路径对 `times` / `durationSec` 都是 **OR 关系**:
```java
// 事件路径 handleBreach
if (times > 1) {
    count = stateStore.incrementTriggerCount(ruleId, appid);
    if (count >= times) { fire; return; }   // ← 路径 1
}
if (elapsed >= durationSec * 1000L) { fire; }  // ← 路径 2
```
配 `times=10, durationSec=60s`,如果 60s 内只 breach 5 次(中间断了 5 次),`elapsed=60s` 满足 → fire。但 `count=5 < 10`,**用户期望的"60s 内累计 10 次"语义被破坏**。

**修复建议**:
- 如设计是 AND:`count >= times && elapsed >= duration` 才 fire
- 如设计是 OR:在文档中显式说明,避免运营误配

---

### Bug-6: 扫描器触发的告警丢失日志详情,value=0.0

**位置**:`alerter/AlertEngine.java:314-324`(`fireFromScanner`)

**问题**:
```java
MetricEvent synthetic = MetricEvent.builder()
        .appid(appid)
        .metricName(rule.getRuleType())
        .value(0.0)   // ← 0.0,不是真实指标值
        .timestamp(now)
        .build();     // ← 无 tags,无 level/logger/traceId/message
```
`AlertNotifier.renderLogDetail` 依赖 `event.getTags()` 渲染日志详情,扫描器路径 tags=null → 邮件里"日志详情"段为空。`value=0.0` 也会让"指标 X = 0.00"显示在告警邮件中,误导运维。

**触发场景**:
- 事件流短暂中断,但 PENDING 状态仍在 Redis
- 扫描器兜底触发,运维收到一封 "metric=log_keyword value=0.00" 的邮件,无任何有用信息

**修复建议**:
在 `AlertStateStore` 中多存一栏"最近一次事件快照"(tags + value),`fireFromScanner` 拼装时填回去。

---

### Bug-7: `AlertRuleCache.refresh` 静默丢弃 app 关联异常的规则

**位置**:`alerter/AlertRuleCache.java:42-47`

**问题**:
```java
.filter(r -> r.getApp() != null && r.getApp().getAppid() != null)
```
`app` 或 `app.appid` 为 null 的 enabled 规则被静默过滤掉,**无任何日志**。运维侧无法察觉"我配的规则为什么没生效"。

**修复建议**:
```java
long total = all.size();
long kept = all.stream().filter(r -> r.getApp() != null && r.getApp().getAppid() != null).count();
if (total != kept) {
    log.warn("[Alerter] 规则缓存过滤了 app/appid 为空的规则 - total={}, kept={}, dropped={}",
            total, kept, total - kept);
}
```

---

### Bug-8: `LogAlertScheduler` 用 `fixedDelay` + 60s 窗口,可能漏算数据

**位置**:`analysis/LogAlertScheduler.java:31-47`

**问题**:
```java
@Scheduled(fixedDelayString = "...interval-ms:60000")  // ← 任务结束后等 60s
public void evaluateErrorRateRules() {
    ...
    Instant from = now.minusSeconds(windowSeconds);  // 60s 滑窗
```
`fixedDelay` 是"上次结束 → 下次开始"的间隔。如果 InfluxDB 查询耗时 1s,实际间隔 61s,**查询窗口 = [now-60s, now] 与上一次窗口 = [now-121s, now-61s] 存在 1s 间隙的边缘数据**(InfluxDB 数据按写入时间,不是按查询时间)。

**修复建议**:
- 改用 `fixedRate`(固定频率,允许并行,需加锁或调度单线程)
- 或将 `windowSeconds` 默认值设为 `intervalMs/1000 + 安全余量`(如 65s)
- 或用 `now.minusSeconds(intervalMs/1000 + 1)` 强制窗口重叠

---

## 轻微 Bug(P2 可修)

### Bug-9: `AlertStateStore.setState` 非原子操作,极端并发下 TTL 错设

**位置**:`alerter/AlertStateStore.java:70-90`

**问题**:
`setState` 内部连续执行:
```java
if (firstBreachAt == null) redis.delete(field);   // 调用 1
if (lastFiredAt == null)   redis.delete(field);   // 调用 2
redis.opsForHash().putAll(key, map);              // 调用 3
redis.expire(key, Duration.ofHours(ttlHours));    // 调用 4
```
四次独立 Redis 调用,无事务/无 Lua。极端并发场景:
- 调用 1 删了 firstBreachAt,调用 3 还没 put,另一线程 `setState(PENDING, firstBreachAt=now)` 又 putAll
- 调用 4 的 expire 基于"新 key",但中间窗口 TTL 不一致

实际生产中发生概率极低,但代码层不正确。

**修复建议**:用 Redis 事务 (`MULTI/EXEC`) 或 Lua 脚本;或换 `redis.expire` 为 `boundSetOps().expire`。

---

### Bug-10: `AlertState.RESOLVED` 状态实际不可观察,设计冗余

**位置**:`alerter/AlertEngine.java:236-240`

**问题**:
```java
if (current == AlertState.FIRING) {
    stateStore.setState(ruleId, appid, AlertState.RESOLVED, null, null);
    resolve(rule, event, now);
    stateStore.clear(ruleId, appid);   // ← 立刻 clear
}
```
`setState(RESOLVED)` 与 `clear` 之间只有同步代码,**任何其他线程都不可能在这个窗口读到 RESOLVED**。`RESOLVED` 状态从枚举中可以去掉,简化为 `setState(RESOLVED)` → `resolve` → `clear`,中间不存盘。

**修复建议**:
- 选项 A:删除 `RESOLVED` 状态,`handleRecover` 走 `FIRING → clear`
- 选项 B:保留 RESOLVED 但延长生命周期(比如保留 1 分钟做"刚恢复"展示)

---

## 附:扫描覆盖清单

| 类 | 是否扫描 | 备注 |
|---|---|---|
| `AlertEngine` | ✅ | 状态机主控,Bug-2/3/5/6 |
| `AlertEvaluator` | ✅ | 表达式求值,未发现严重bug |
| `AlertStateStore` | ✅ | Redis 状态存取,Bug-9/10 |
| `AlertRuleCache` | ✅ | 规则缓存,Bug-7 |
| `AlertNotifier` | ✅ | SMTP 通知,Bug-1(测试盲点) |
| `AsyncAlertExecutor` | ✅ | 异步线程池,无严重bug |
| `PendingStateScanner` | ✅ | 5s 周期扫描,无严重bug |
| `AlertState` | ✅ | 枚举,Bug-10 |
| `JexlExprEvaluator` | ✅ | JEXL 沙箱,无严重bug |
| `LogAlertScheduler` | ✅ | log_error_rate 调度,Bug-8 |
| `LogAnomalyDetector` | ✅ | 日志异常检测,无严重bug |
| `LogAggregator` | ✅ | InfluxDB 聚合,无严重bug |
| `BatchAlertConsumer` | ✅ | Kafka 消费,无严重bug |
| `AlertRuleService` | ✅ | 规则 CRUD,Bug-4 |

## 修复优先级

| 优先级 | Bug | 预计工作量 |
|---|---|---|
| P0 | Bug-1 邮件多收件人 | 0.5h(含测试修正) |
| P0 | Bug-2 事务不生效 | 1h(抽 Service 或 self-inject) |
| P0 | Bug-3 日志告警不恢复 | 2h(状态机扩展) |
| P0 | Bug-4 缓存不失效 | 0.5h(注入 + 三处调用) |
| P1 | Bug-5 PENDING 语义 | 0.5h(与产品确认) |
| P1 | Bug-6 扫描器丢详情 | 1h(状态扩字段) |
| P1 | Bug-7 静默丢弃 | 0.2h |
| P1 | Bug-8 窗口与间隔 | 0.2h(改 fixedRate) |
| P2 | Bug-9 非原子 | 1h(改 Lua) |
| P2 | Bug-10 RESOLVED 冗余 | 0.2h |
