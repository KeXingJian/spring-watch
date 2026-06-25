# 日志分析页渲染层正确性检查报告

承接 `metric-display-principle.md` 和 `app-pane-correctness-audit.md`,同一套单位/链路
审计方法套到 LogsView 上。

## 0. 日志链路总览

日志跟指标**完全不一样**,数据流分两路:

```
被监控 App /api/agent/logs?since=...
  → AgentLogCollector 解析(后端)
  → Kafka(monitor-logs)
  → BatchLogConsumer
      ├─ LogParser.enrich
      ├─ LogSanitizer.mask
      ├─ LogFingerprinter.fingerprint / patternName
      ├─ LogDedupService.shouldKeep  ←─ 滑动窗口去重,60s 内同 fp 只留首条
      │     └─ 首条 → InfluxDB app_log
      │     └─ 重复 → Redis 累加 → flushDirtyCounts 30s 一次双写 PG log_dedup_count
      └─ AsyncAlertExecutor(告警候选)
```

两条存储路径:
- **InfluxDB `app_log`**:只存去重后的"首条",字段 `level/logger/threadName/message/throwable/fingerprint/pattern/traceId/...`
- **PG `log_dedup_count`**:存被丢弃的重复条数 `dedupCount`,**自然时间窗 = 近 1h**(Redis `countTtlSeconds:3600`)

**关键陷阱**:用户看到的"次数"在不同接口里含义完全不同,这是这次审计的核心:
- `topFingerprints.count` = InfluxDB 保留的首条数 ≠ 真实总次数
- `topDedup.dedupCount` = 近 1h 被丢弃的重复数
- `errorRate` = error / total(都是窗口内 InfluxDB count)

## 1. 卡片 / 图表审计

| 模块 | 接口 | 数据源 | 含义 | 前端展示 | 状态 |
|---|---|---|---|---|---|
| 总日志(全级别) | `/api/logs/stats/error-rate` | InfluxDB app_log message 字段数 | 窗口内总条数 | cardTotal | ✓(但 sub-text 写死) |
| ERROR 数 | 同上 | level=ERROR 数 | 窗口内 ERROR 数 | cardError | ✓ |
| WARN 数 | 同上 | level=WARN 数 | 窗口内 WARN 数 | cardWarn | ✓ |
| 错误率突增 | `/api/logs/anomaly` | Redis 上一窗口 errorRate | boolean | cardSpike | **✗** |
| 错误率时序 | `/api/logs/stats/error-rate-series` | InfluxDB 1m 桶 | 桶内 count | 折线 | ✓(但 yAxis "条" 含糊) |
| 级别分布 | `/api/logs/levels` | InfluxDB group by level | 桶内 count | 饼图 | ✓(但 sub-text 写死) |
| 日志检索 | `/api/logs/search` | InfluxDB pivot | limit 截断后的行 | 表格 | ✓(但 "X 条" 误导) |
| 上下文 | `/api/logs/context` | InfluxDB ±N 秒 | limit 截断后的行 | 模态 | ✓ |
| Top 异常模式 | `/api/logs/patterns` | **仅 InfluxDB 首条 count** | 假"次数" | 条形图 | **✗** |
| 高频去重模式 | `/api/logs/dedup/top` | PG dedup_count | 近 1h 丢弃数 | 条形图 | ✓(但 tag 误导) |

## 2. Bug 详细分析

### 🔴 Bug L2(强):错误率突增卡片"基线"是前端瞎算的

**原代码 (LogsView.vue:88-90)**:
```ts
const cur = a?.stats?.errorRate || 0
const last = cur / 3.0   // ← 假设倍数恰好 3.0
cardSpikeSub.value = `当前 ${(cur * 100).toFixed(2)}%(基线 ≈ ${(last * 100).toFixed(2)}%)`
```

**问题**:`LogAnomalyDetector.isErrorRateSpiking` 用 Redis 存了真实的上一窗口 rate,
但响应里**没返回**。前端只能猜 `last = cur / 3.0` —— 当倍数是 4× / 5× 时,这个
"基线"完全是错的,会把 2% 显示成 0.67%、把 20% 显示成 6.67%,误导用户。

**修复**:
- `LogAnomalyDetector.isErrorRateSpiking` 改返回 `SpikingResult(spiking, lastRate)` record
- `LogController.anomaly` 把 `lastRate` 放进响应
- 前端用真实 `a.lastRate`,无基线时显式提示"需 ≥ 2 个连续窗口"

### 🔴 Bug L3(强):Top 异常模式"次数"是去重后的首条数

**链路证据**:
- `BatchLogConsumer:114` 走 `LogDedupService.shouldKeep`,首条入库、重复累加到 Redis
- `LogQueryService.topFingerprints` 只查 InfluxDB 算 `count()`,**丢了 PG `log_dedup_count`**

**后果**:某 fingerprint 1 分钟内出现 1000 次,InfluxDB 里只存 1 条,Top 把它显示为
"1 次"——**用户看到 "Top 1" 以为这个 pattern 只出现 1 次**,实际出现 1000 次。

**修复**:
- `PatternTop` record 把无用的 `level` 字段换成 `dedupCount`
- `LogDedupCountRepository` 加 `sumDedupByFingerprints(appid, fps)` 批量查
- `LogQueryService.topFingerprints` 在拿到 InfluxDB topN 之后,合并 PG dedup 计数
- 前端 bar 显示"总次数 = 首条 + 去重",panel-head 写明

**遗留语义风险**:PG `dedup_count` 的时间窗 = Redis TTL(1h),跟 InfluxDB 查询的
`[from, to]` 并不严格对齐。当用户选 15m 范围时,PG 那个数可能跨 1h,会高估总次数。
代码里加了注释提醒。

### 🟡 Bug L1(弱):错误率时序 y-axis "条" 含义不清

`errorRateSeries` 的 every 写死 `1m`,所以每条数据是 1m 桶内 count。y-axis 写
"条"是单位,但用户得自己看 panel-head 的"1m 窗口"才能知道这是"条/分"。

**修复**:yAxisName 改 "条/分"。

### 🟡 Bug L4(弱):高频去重模式 tag "滑动窗口 1m 内重复" 错

`LogDedupService` 的 `windowSeconds=60`(1m)只是**判定"是不是重复"** 的窗口,真正
累加 `dedupCount` 的生命周期是 `countTtlSeconds=3600`(1h)。所以条形图里的数字
是"近 1h 内的累计丢弃条数",不是"1m 内的重复"。

**修复**:tag 改 "近 1h 滑动窗口内被丢弃的重复条数"。

### 🟡 Bug L5(弱):日志检索 count "X 条" 误导

`searchCount = searchResults.length`,`search()` 接口 `limit=10` 写死。
所以 "10 条" 其实是"返回 10 条",**不**是"匹配 10 条"。

**修复**:tag 改 "显示 X 条(最近 Y 内,按 limit 截断)"。

### 🟡 Bug L6(弱):总日志/级别分布 sub-text 写死 "最近 1h"

`rangeSec` 默认 3600(1h),用户可以切到 15m/6h/24h,但 sub-text 永远写"最近 1h"。

**修复**:`rangeLabel` computed 属性,sub-text 跟 `rangeSec` 联动(Vue 用 computed,
静态页用 `getRangeLabel()` + `syncRangeLabels()` 切按钮时刷新)。

## 3. 修复文件清单

| 文件 | 改动 |
|---|---|
| `src/main/java/.../analysis/LogAnomalyDetector.java` | `isErrorRateSpiking` 改返回 `SpikingResult(spiking, lastRate)` |
| `src/main/java/.../web/LogController.java` | `/anomaly` 响应加 `lastRate` 字段 |
| `src/main/java/.../repository/LogDedupCountRepository.java` | 加 `sumDedupByFingerprints` + `FpCount` projection |
| `src/main/java/.../service/LogQueryService.java` | `topFingerprints` 合并 PG dedup;`PatternTop` 字段 `level` → `dedupCount` |
| `frontend/src/views/LogsView.vue` | cardSpike 用真实 lastRate;patternsBar 显示"总次数 = 首条 + 去重";错误率时序 yAxis "条/分";sub-text 联动 rangeLabel;检索 count 改"显示 X 条(按 limit 截断)" |
| `src/main/resources/static/pages/logs.html` | 同步所有上面的修复 |

## 4. 确认无问题的模块

- `LogEvent` 模型字段(都是 String / Instant,无单位)
- `LogFingerprinter` SHA-1(无单位语义)
- `LogSanitizer`(无单位)
- `errorRateSeries` 桶内 count 计算正确(全级别 + ERROR + WARN 三类桶聚合)
- `errorRate` 当前窗口 = error/total,符合"错误率"定义
- `LogRow` 全部 String 字段,无歧义
- `levelDistribution` Flux group by level + count,正确
- `context` ±N 秒 + 过滤 thread/logger/host,正确
- `dedup` top 数字本身正确(就是近 1h 累加的 dedupCount),只是 label 错(已修)
- 时间显示 `formatTime(r.time)` 正确(ISO → 本地时区)

## 5. 没动的项目(留给后续)

1. **错误率时序 vs 范围**:15m/1h/6h/24h 都用 `every=1m` 出 15~1440 个桶,6h/24h 渲染
   会有点拥挤。后续可以让 `every` 跟 `rangeSec` 联动(>= 6h 用 5m 桶,>= 24h 用 30m 桶)。
2. **LogFingerprinter SHA-1 fingerprint** 是 hex 40 字符,前端用 `shortMsg(fp, 12)` 截断后
   几乎无法识别"是同一个 fp 吗"。后续可以加个"短 fp"(取前 8 字符 + 末 4 字符)展示。
3. **Top 异常模式的 tooltip**:现在只有"总次数"bar,但 rawCount/rawDedup 已经在 data
   里。BarChart.vue 加个 `tooltip.formatter` 把这两个值显示出来,就能区分"高频真实"和
   "高频去重",UX 更好。
4. **PatternsBar 的 IN 查询**:当 topN=10 时,`sumDedupByFingerprints` 查 10 个 fp,
   1 次 SQL,可接受。但如果未来 topN 涨到 100+,IN 列表会变长,需要分批。
5. **PG dedup_count 时间语义**:当前实现是 Redis TTL 累加,与 InfluxDB 查询的时间窗
   不严格对齐。要彻底修,需要把 dedup 改成"按时间桶",但改动较大,留给后续。
