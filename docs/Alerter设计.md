# spring-watch Alerter 设计指南

> 现状:`AlertEvaluator`、`AlertWindowManager`、`AlertNotifier` 类已存在,但 `AlertConsumer` 中 `alertEvaluator.evaluate(event)` 被注释,**告警链路未跑通**。
> 目标:给出"够用、可演进"的 Alerter 设计,先打通链路,再补细节。

---

## 一、先建立心智模型:告警生命周期

监控告警不是"超阈值就发一条",而是**状态机**:

```
                  条件首次满足
         ┌───────────────────────────┐
         ▼                           │
   ┌──────────┐  持续N秒(duration)  ┌─────────┐
   │  IDLE    │ ──────────────────► │ PENDING │ ──┐
   │ (无事件)  │                    │(待确认) │   │ 条件首次不满足
   └────▲─────┘                    └────┬────┘   │
        │                              │        │
        │           ┌──────────────────┘        │
        │ 恢复      ▼                            │
        │    ┌──────────┐                       │
        └────│ RESOLVED │◄──────────────────────-┘
             │ (已恢复)  │ 条件不再满足
             └──────────┘
                  │
                  ▼  恢复通知(可选)
             状态回到 IDLE

  条件满足且持续 ≥ duration:触发告警 + 发通知 → 状态 PENDING → FIRING
  条件不再满足:发恢复通知 → 状态 RESOLVED → IDLE
```

**关键概念**:
- **Pending 窗口**: 防止"瞬时抖动"误报(GC 一下、一次慢查询)
- **Firing 状态**: 告警活跃期间,不再重复发(避免轰炸)
- **Resolved 通知**: 故障恢复也要通知(用户最想知道的是"修好了")

---

## 二、整体数据流

```
Kafka [monitor-metrics]
        │ MetricEvent
        ▼
BatchAlertConsumer (concurrency=2, batch=500)
        │
        │ List<MetricEvent> 拆成单个 event
        ▼
AsyncAlertExecutor (独立线程池, CPU×2)
        │
        ▼
AlertEngine.evaluate(event)
        │
        │ 1. 查 RuleCache (appid → List<Rule>)  ← 30s 刷新一次,避免每条查 DB
        │ 2. 对每条 Rule:
        │    a. 表达式匹配 (value > threshold?)
        │    b. 状态机推进 (IDLE→PENDING→FIRING→RESOLVED)
        │    c. 触发动作:
        │       - 进入 FIRING  → AlertNotifier + 写 AlertHistory
        │       - 进入 RESOLVED → AlertNotifier (恢复) + 更新 AlertHistory.resolvedAt
        │
        ▼
AlertNotifier (Webhook/钉钉/飞书/邮件)
        │
        ▼
AlertHistory 持久化 (PostgreSQL)
```

---

## 三、关键类与最小实现

### 3.1 状态机实现 (新增)

```java
// AlertState.java —— 纯枚举 + 状态机转移表
public enum AlertState {
    IDLE, PENDING, FIRING, RESOLVED;

    public static AlertState nextOnBreach(AlertState current) {
        return switch (current) {
            case IDLE, RESOLVED -> PENDING;
            case PENDING -> FIRING;        // 仅在 duration 满足时由调用方转移
            case FIRING -> FIRING;          // 已激活,保持
        };
    }

    public static AlertState nextOnRecover(AlertState current) {
        return switch (current) {
            case PENDING -> IDLE;          // 还在 pending 就恢复了,回 IDLE,不通知
            case FIRING -> RESOLVED;        // 触发恢复通知
            case IDLE, RESOLVED -> current;
        };
    }
}
```

### 3.2 状态持久化 (新增)

```java
// AlertStateStore.java —— 用 Redis Hash 存状态
@Component
@RequiredArgsConstructor
public class AlertStateStore {

    private final StringRedisTemplate redis;

    private static final String KEY = "alert:state:";
    private static final long TTL_HOURS = 24;

    public AlertState get(Long ruleId, Long appid) {
        String v = redis.opsForValue().get(key(ruleId, appid));
        return v == null ? AlertState.IDLE : AlertState.valueOf(v);
    }

    public void set(Long ruleId, Long appid, AlertState state, Instant firstBreachAt) {
        Map<String, String> map = new HashMap<>();
        map.put("state", state.name());
        if (firstBreachAt != null) {
            map.put("firstBreachAt", String.valueOf(firstBreachAt.toEpochMilli()));
        }
        redis.opsForHash().putAll(key(ruleId, appid), map);
        redis.expire(key(ruleId, appid), Duration.ofHours(TTL_HOURS));
    }

    public Instant getFirstBreachAt(Long ruleId, Long appid) {
        Object v = redis.opsForHash().get(key(ruleId, appid), "firstBreachAt");
        return v == null ? null : Instant.ofEpochMilli(Long.parseLong(v.toString()));
    }

    public void clear(Long ruleId, Long appid) {
        redis.delete(key(ruleId, appid));
    }

    private String key(Long ruleId, Long appid) {
        return KEY + ruleId + ":" + appid;
    }
}
```

### 3.3 规则缓存 (新增)

```java
// AlertRuleCache.java —— 避免每条 event 都查 DB
@Component
@RequiredArgsConstructor
public class AlertRuleCache {

    private final AlertRuleRepository repository;
    private final AtomicReference<Map<Long, List<AlertRule>>> cache = new AtomicReference<>(Map.of());

    @PostConstruct
    void init() {
        refresh();
    }

    @Scheduled(fixedDelay = 30_000)  // 30s 刷新
    public void refresh() {
        List<AlertRule> all = repository.findByStatus("enabled");
        Map<Long, List<AlertRule>> grouped = all.stream()
                .filter(r -> r.getApp() != null && r.getApp().getAppid() != null)
                .collect(Collectors.groupingBy(r -> r.getApp().getAppid()));
        cache.set(grouped);
        log.info("[Alerter] 规则缓存刷新 - rules={}, apps={}", all.size(), grouped.size());
    }

    public List<AlertRule> rulesFor(Long appid) {
        return cache.get().getOrDefault(appid, List.of());
    }

    public void invalidate() {
        // 规则增删改后由 Controller 调用
        refresh();
    }
}
```

### 3.4 表达式评估器 (改造 `AlertEvaluator`)

```java
// AlertEvaluator.java —— 支持简单表达式 "metricName > 80" 或 "metricName >= 0.95"
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertEvaluator {

    private static final Pattern EXPR = Pattern.compile(
            "^(\\w[\\w.]*)\\s*(>=|<=|>|<|==|!=)\\s*([\\d.]+)$");

    /**
     * 评估单条事件是否触发规则条件(纯函数,不维护状态)
     */
    public boolean isBreached(AlertRule rule, MetricEvent event) {
        // ruleType 必须为 metric
        if (!"metric".equals(rule.getRuleType())) {
            return false;
        }

        // metricName 必须匹配
        if (rule.getExpression() == null || rule.getExpression().isBlank()) {
            return false;
        }

        Matcher m = EXPR.matcher(rule.getExpression().trim());
        if (!m.matches()) {
            log.debug("[Alerter] 规则表达式无法解析 - ruleId={}, expr={}", rule.getId(), rule.getExpression());
            return false;
        }

        String metricName = m.group(1);
        String op = m.group(2);
        double threshold = Double.parseDouble(m.group(3));

        if (!metricName.equals(event.getMetricName())) {
            return false;
        }

        Double value = event.getValue();
        if (value == null) return false;

        return switch (op) {
            case ">"  -> value >  threshold;
            case "<"  -> value <  threshold;
            case ">=" -> value >= threshold;
            case "<=" -> value <= threshold;
            case "==" -> value == threshold;
            case "!=" -> value != threshold;
            default   -> false;
        };
    }
}
```

### 3.5 告警引擎 (新增,核心)

```java
// AlertEngine.java —— 串联 评估+状态机+通知+持久化
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertEngine {

    private final AlertEvaluator evaluator;
    private final AlertStateStore stateStore;
    private final AlertRuleCache ruleCache;
    private final AlertNotifier notifier;
    private final AlertHistoryRepository historyRepository;

    public void process(MetricEvent event) {
        List<AlertRule> rules = ruleCache.rulesFor(event.getAppid());
        if (rules.isEmpty()) return;

        for (AlertRule rule : rules) {
            evaluateRule(rule, event);
        }
    }

    private void evaluateRule(AlertRule rule, MetricEvent event) {
        boolean breached = evaluator.isBreached(rule, event);
        AlertState current = stateStore.get(rule.getId(), event.getAppid());
        Instant now = Instant.now();

        if (breached) {
            handleBreach(rule, event, current, now);
        } else {
            handleRecover(rule, event, current, now);
        }
    }

    private void handleBreach(AlertRule rule, MetricEvent event, 
                               AlertState current, Instant now) {
        if (current == AlertState.FIRING) {
            // 已激活,只更新 lastFiredTime,不重复通知
            log.trace("[Alerter] 告警持续中 - ruleId={}, appid={}", rule.getId(), event.getAppid());
            return;
        }

        if (current == AlertState.IDLE || current == AlertState.RESOLVED) {
            // 首次触发,进入 PENDING
            stateStore.set(rule.getId(), event.getAppid(), AlertState.PENDING, now);
            log.debug("[Alerter] 条件首次满足 - ruleId={}, appid={}, state=PENDING",
                    rule.getId(), event.getAppid());
            return;
        }

        if (current == AlertState.PENDING) {
            // 在 PENDING 中,检查是否达到 duration
            Instant firstBreach = stateStore.getFirstBreachAt(rule.getId(), event.getAppid());
            long durationMs = rule.getDurationSeconds() == null ? 60_000L 
                    : rule.getDurationSeconds() * 1000L;

            if (firstBreach != null && Duration.between(firstBreach, now).toMillis() >= durationMs) {
                // 持续时间达标,正式触发
                stateStore.set(rule.getId(), event.getAppid(), AlertState.FIRING, firstBreach);
                fire(rule, event);  // 发通知 + 写历史
            }
        }
    }

    private void handleRecover(AlertRule rule, MetricEvent event,
                                AlertState current, Instant now) {
        if (current == AlertState.PENDING) {
            // 还没正式触发就恢复了,回 IDLE
            stateStore.clear(rule.getId(), event.getAppid());
            log.debug("[Alerter] pending 期间恢复 - ruleId={}, appid={}", rule.getId(), event.getAppid());
            return;
        }

        if (current == AlertState.FIRING) {
            // 已激活的告警恢复了
            stateStore.set(rule.getId(), event.getAppid(), AlertState.RESOLVED, null);
            resolve(rule, event, now);  // 发恢复通知 + 更新历史
            stateStore.clear(rule.getId(), event.getAppid());  // 清空回 IDLE
        }
    }

    private void fire(AlertRule rule, MetricEvent event) {
        log.info("[Alerter] 告警触发 - ruleId={}, appid={}, metric={}, value={}, threshold={}",
                rule.getId(), event.getAppid(), event.getMetricName(), event.getValue(), rule.getExpression());

        AlertHistory history = AlertHistory.builder()
                .rule(rule)
                .app(rule.getApp())
                .alertLevel(determineLevel(event, rule))
                .alertMessage(buildMessage(rule, event, "firing"))
                .build();
        AlertHistory saved = historyRepository.save(history);

        try {
            notifier.notify(rule, event, "firing", saved.getId());
            saved.setNotifyResult("{\"status\":\"ok\"}");
        } catch (Exception e) {
            log.warn("[Alerter] 通知发送失败 - historyId={}, error={}", saved.getId(), e.getMessage());
            saved.setNotifyResult("{\"status\":\"failed\",\"error\":\"" + e.getMessage() + "\"}");
        }
        historyRepository.save(saved);
    }

    private void resolve(AlertRule rule, MetricEvent event, Instant now) {
        log.info("[Alerter] 告警恢复 - ruleId={}, appid={}, metric={}",
                rule.getId(), event.getAppid(), event.getMetricName());

        // 找最近一条未恢复的历史,标记 resolvedAt
        List<AlertHistory> open = historyRepository
                .findByAppAppidAndRuleIdAndResolvedAtIsNullOrderByCreatedAtDesc(
                        event.getAppid(), rule.getId());
        if (!open.isEmpty()) {
            AlertHistory latest = open.get(0);
            latest.setResolvedAt(now);
            historyRepository.save(latest);
        }

        try {
            notifier.notify(rule, event, "resolved", null);
        } catch (Exception e) {
            log.warn("[Alerter] 恢复通知发送失败 - error={}", e.getMessage());
        }
    }

    private String determineLevel(MetricEvent event, AlertRule rule) {
        // 简单分级:超阈值 1.5 倍 = critical,否则 warning
        if (rule.getThresholdValue() != null && event.getValue() != null
                && event.getValue() / rule.getThresholdValue() >= 1.5) {
            return "critical";
        }
        return "warning";
    }

    private String buildMessage(AlertRule rule, MetricEvent event, String type) {
        return String.format("[%s] appid=%s 指标 %s 当前值=%.2f 规则=%s 时间=%s",
                type.toUpperCase(), event.getAppid(), event.getMetricName(),
                event.getValue(), rule.getExpression(), Instant.now());
    }
}
```

### 3.6 改造后的 AlertConsumer (接入 Kafka)

```java
// BatchAlertConsumer.java —— 接 Kafka + 异步执行
@Slf4j
@Component
public class BatchAlertConsumer {

    private final ObjectMapper objectMapper;
    private final AlertEngine engine;
    private final ExecutorService executor;

    public BatchAlertConsumer(ObjectMapper objectMapper, AlertEngine engine) {
        this.objectMapper = objectMapper;
        this.engine = engine;
        this.executor = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                Thread.ofVirtual().name("alert-eval-", 0).factory());
    }

    @KafkaListener(topics = "monitor-metrics",
                   groupId = "spring-watch-alert-evaluator",
                   containerFactory = "batchFactory")
    public void onBatch(List<String> messages) {
        for (String m : messages) {
            MetricEvent event;
            try {
                event = objectMapper.readValue(m, MetricEvent.class);
            } catch (Exception e) {
                log.warn("[Alerter] 反序列化失败 - error={}", e.getMessage());
                continue;
            }
            executor.submit(() -> {
                try {
                    engine.process(event);
                } catch (Throwable t) {
                    log.error("[Alerter] 处理异常 - appid={}, metric={}, error={}",
                            event.getAppid(), event.getMetricName(), t.getMessage(), t);
                }
            });
        }
    }
}
```

### 3.7 改造后的 AlertNotifier (真实通知)

```java
// AlertNotifier.java —— 解析 channels JSON,发 Webhook
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertNotifier {

    private final RestTemplate restTemplate;  // 或 WebClient
    private final ObjectMapper objectMapper;

    public void notify(AlertRule rule, MetricEvent event, String type, Long historyId) {
        if (rule.getNotifyChannels() == null || rule.getNotifyChannels().isBlank()) {
            log.debug("[Alerter] 无通知渠道 - ruleId={}", rule.getId());
            return;
        }

        // notifyChannels 格式: {"webhook":"http://...","dingtalk":"https://..."}
        Map<String, String> channels;
        try {
            channels = objectMapper.readValue(rule.getNotifyChannels(), 
                    new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("[Alerter] 通知渠道配置解析失败 - ruleId={}, raw={}", 
                    rule.getId(), rule.getNotifyChannels());
            return;
        }

        Map<String, Object> payload = Map.of(
                "type", type,
                "ruleId", rule.getId(),
                "ruleName", rule.getRuleName(),
                "appid", event.getAppid(),
                "metric", event.getMetricName(),
                "value", event.getValue(),
                "expression", rule.getExpression(),
                "time", Instant.now().toString(),
                "historyId", historyId == null ? -1 : historyId
        );

        // Webhook
        String webhook = channels.get("webhook");
        if (webhook != null && !webhook.isBlank()) {
            sendWebhook(webhook, payload);
        }

        // 钉钉
        String dingtalk = channels.get("dingtalk");
        if (dingtalk != null && !dingtalk.isBlank()) {
            sendDingtalk(dingtalk, payload);
        }

        // 飞书
        String feishu = channels.get("feishu");
        if (feishu != null && !feishu.isBlank()) {
            sendFeishu(feishu, payload);
        }
    }

    private void sendWebhook(String url, Map<String, Object> payload) {
        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(url, payload, String.class);
            log.info("[Alerter] Webhook 发送成功 - url={}, status={}", url, resp.getStatusCode());
        } catch (Exception e) {
            log.warn("[Alerter] Webhook 发送失败 - url={}, error={}", url, e.getMessage());
            throw new RuntimeException(e);  // 让外层记录 notifyResult
        }
    }

    private void sendDingtalk(String webhook, Map<String, Object> payload) {
        // 钉钉机器人 Markdown 格式
        Map<String, Object> body = Map.of(
                "msgtype", "markdown",
                "markdown", Map.of(
                        "title", "[" + payload.get("type") + "] " + payload.get("ruleName"),
                        "text", String.format(
                                "### %s\n\n" +
                                "- 应用: appid=%s\n" +
                                "- 指标: %s = %s\n" +
                                "- 规则: %s\n" +
                                "- 时间: %s",
                                payload.get("type"), payload.get("appid"), 
                                payload.get("metric"), payload.get("value"),
                                payload.get("expression"), payload.get("time"))
                )
        );
        try {
            restTemplate.postForEntity(webhook, body, String.class);
        } catch (Exception e) {
            log.warn("[Alerter] 钉钉发送失败 - error={}", e.getMessage());
        }
    }

    private void sendFeishu(String webhook, Map<String, Object> payload) {
        // 飞书机器人消息格式
        // ...
    }
}
```

### 3.8 仓库扩展 (新增按 ruleId 查未恢复历史)

```java
// AlertHistoryRepository.java
public interface AlertHistoryRepository extends JpaRepository<AlertHistory, Long> {
    List<AlertHistory> findByAppAppidOrderByCreatedAtDesc(Long appid);
    
    // 新增:查某个 app + rule 的未恢复历史
    List<AlertHistory> findByAppAppidAndRuleIdAndResolvedAtIsNullOrderByCreatedAtDesc(
            Long appid, Long ruleId);
}
```

### 3.9 规则增删改后失效缓存

```java
// AlertController.java (现有) —— 在增删改时调用
@RestController
@RequestMapping("/api/alerts/rules")
@RequiredArgsConstructor
public class AlertController {
    private final AlertRuleRepository ruleRepository;
    private final AlertRuleCache ruleCache;
    
    @PostMapping
    public AlertRule create(@RequestBody AlertRule rule) {
        AlertRule saved = ruleRepository.save(rule);
        ruleCache.invalidate();
        return saved;
    }
    
    @PutMapping("/{id}")
    public AlertRule update(@PathVariable Long id, @RequestBody AlertRule rule) {
        // ...
        ruleCache.invalidate();
        return saved;
    }
    
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        ruleRepository.deleteById(id);
        ruleCache.invalidate();
    }
}
```

---

## 四、最小可上线版本(MVP)清单

按"先打通,后补强"排序:

| 优先级 | 改动 | 说明 |
|---|---|---|
| **P0** | 新增 `AlertEngine` | 串联评估+状态机+通知+持久化 |
| **P0** | 新增 `AlertStateStore` | Redis 存状态机,5 行代码 |
| **P0** | 新增 `AlertRuleCache` | 30s 刷新,避免 DB 热点 |
| **P0** | 改造 `AlertEvaluator` | 把 `evaluate()` 拆成 `isBreached()` 纯函数 |
| **P0** | 改造 `AlertConsumer` | 启用 `alertEvaluator.evaluate(event)`,加异步执行 |
| **P1** | 改造 `AlertNotifier` | 真实 Webhook,解析 `notifyChannels` JSON |
| **P1** | `AlertHistoryRepository` 增方法 | 按 ruleId 查未恢复历史 |
| **P1** | `AlertController` 增失效缓存 | 增删改后调 `invalidate()` |
| **P2** | 恢复通知 (resolve) | 加 `resolvedAt` 字段填充 + 通知 |
| **P2** | 通知失败重试 | 异步重试 3 次,失败入 `notifyResult` |
| **P3** | 多条件规则 (AND/OR) | `expression` 改 JSON,解析器升级 |
| **P3** | 钉钉/飞书/邮件 | 复用 `AlertNotifier` 扩展 |
| **P3** | 告警抑制 (silence) | 维护期/夜班关闭告警 |

---

## 五、当前代码的 Gap 总结

| 类 | 现状 | 缺什么 |
|---|---|---|
| `AlertEvaluator` | 有 `evaluate()` 但耦合状态/通知 | 拆成纯函数 `isBreached(rule, event)` |
| `AlertWindowManager` | 10 分钟简单去重 | 缺真正的状态机 (IDLE/PENDING/FIRING/RESOLVED) |
| `AlertNotifier` | 只 log,不真发 | 真实 HTTP 客户端 + 多渠道解析 |
| `AlertConsumer` | `evaluate(event)` 被注释 | 启用 + 异步执行 + 批量 |
| `AlertRule.durationSeconds` | 字段有,未用 | 接入状态机 (PENDING 持续时长) |
| `AlertHistory.resolvedAt` | 字段有,未用 | 恢复时填充 + 通知 |
| `AlertRuleCache` | 不存在 | 新增,30s 刷新 |
| `AlertStateStore` | 不存在 | 新增,Redis Hash 存状态 |

---

## 六、验证方法(怎么确认告警跑通了)

1. **写一个简单规则**: 监控 `jvm_memory_used_bytes` > 100000000 (100MB)
2. **手动灌测试数据**: 通过 `KafkaProducerBridge` 发一个超阈值的 `MetricEvent`
3. **观察日志**:
   - 第 1 条: `[Alerter] 条件首次满足 - state=PENDING`
   - 第 N 条(duration 后): `[Alerter] 告警触发 - ruleId=1`
   - **Webhook 收到 POST 请求**(用 `webhook.site` 测)
   - **`alert_history` 表多一条记录**(`alertLevel=warning`, `resolvedAt=null`)
4. **发一个正常值**:
   - 日志: `[Alerter] 告警恢复 - ruleId=1`
   - Webhook 收到 `type=resolved`
   - `alert_history.resolvedAt` 被填充

---

## 七、一句话总结

Alerter 的核心不是"复杂表达式",而是**"状态机 + duration + 通知 + 恢复"**。
先把 `AlertEngine + AlertStateStore + AlertRuleCache` 这三件套加上,告警链路就通了。
复杂规则(AND/OR、窗口聚合)是后话,别在第一版就上。
