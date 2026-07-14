# 应用指标渲染层正确性检查报告(JDBC / HTTP / JVM / OS)

承接 `metric-display-principle.md` 的"单位自查清单",把同一套方法套到应用详情页的
四个 pane 上。结果分两块:**链路审计(逐指标)** + **修复清单**。

## 0. 链路总览

应用侧指标和自监控不一样,数据流是:

```
被监控 App 暴露 Prometheus 文本协议
  → AgentMetricsCollector 解析 (后端)
  → Kafka (monitor-metrics)
  → BatchMetricConsumer 写入 InfluxDB (springboot_metrics)
  → MetricController 读 InfluxDB (Flux 查询)
  → 前端 fetch + 格式化
```

OTel/Prometheus 命名里**单位信息直接编码到指标名**,这是这次审计最大的护栏:
- `..._seconds_*` → 秒
- `..._milliseconds_*` → 毫秒
- `..._bytes` → 字节
- `..._total` → 累计 counter(必须 derivative 才能变速率)
- `...utilization` → 0~1 比率

只要前后端**有一处把名字和单位对应错**,渲染就错。

## 1. JDBC Pane

### 1.1 指标审计

| 指标 | OTel 指标名 | 原始单位 | 前端处理 | 状态 |
|---|---|---|---|---|
| 最大连接数 | db_client_connections_max | count | 直接 value | ✓ |
| 最小空闲 | db_client_connections_idle_min | count | 直接 value | ✓ |
| 空闲连接 | db_client_connections_usage{state=idle} | count | 直接 value | ✓ |
| 使用连接 | db_client_connections_usage{state=used} | count | 直接 value | ✓ |
| 使用率 | - | ratio | used/max | ✓ |
| 等待请求 | db_client_connections_pending_requests | count | 直接 value | ✓ |
| 平均使用耗时 | use_time_milliseconds_sum / count | **ms** | formatMs | ✓ |
| 平均等待耗时 | wait_time_milliseconds_sum / count | **ms** | formatMs | ✓ |
| 平均创建耗时 | create_time_milliseconds_sum / count | **ms** | formatMs | ✓ |
| 连接池面积图 | usage{state=idle/used} | count | raw | ✓ |
| 使用率图 | used/max | % | × 100 | ✓ |
| 等待请求时序 | pending_requests | count | raw | ✓ |
| **获取连接 QPS** | **use_time_milliseconds_count** | **count(累计)** | **label=QPS 但拿累计值** | **✗** |
| P50/P95/P99 | use/wait/create_time_milliseconds(histogram) | ms | 直读 | ✓ |
| **"分布"图 × 3** | **use/wait/create_time_milliseconds_count** | **count(累计)** | **label=分布(le 桶) 但拿累计 count** | **✗** |

### 1.2 Bug 分析

**Bug-J1 获取连接 QPS 图表(强)**
`JdbcPane.vue:132` 取 `db_client_connections_use_time_milliseconds_count` 累计值
直接画线,panel-head 写 "QPS / req·s"。Counter 画线只有一条单调上升的曲线,跟
"每秒请求数"完全不是一回事。

**Bug-J2 "分布"图 × 3(弱,误导)**
`JdbcPane.vue:282/298/316` 调 `renderHistogram` 取 `*_time_milliseconds_count`(总
调用次数),panel-head 写"使用时长分布(le 桶)"。但 `_count` 不是 histogram 的
桶,真正的 le 桶分布在 `*_time_milliseconds_bucket{le=...}` 上。

### 1.3 修复

- `JdbcPane.vue:133`:`{ agg: 'rate' }` 显式取速率(后端 `derivative` 支持见 5)
- `JdbcPane.vue:282/298/316`:panel-head 改"使用次数趋势(累计 count)",与 legend
  "调用次数(累计)" 一致;真正的 le 桶分布图作为后续工作

## 2. HTTP Pane

### 2.1 指标审计

| 指标 | OTel 指标名 | 原始单位 | 前端处理 | 状态 |
|---|---|---|---|---|
| 总请求数 | duration_seconds_count | count | data.count | ✓ |
| 不同路由数 | duration_seconds_count grouped by route | count | length | ✓ |
| 不同状态码数 | duration_seconds_count grouped by status | count | length | ✓ |
| 错误占比 | 4xx+5xx / total | ratio | formatPercent | ✓ |
| 状态码/方法饼 | duration_seconds_count grouped | count | pie | ✓ |
| Top10 路由(请求数) | duration_seconds_count by route | count | bar | ✓ |
| Top10 路由(累计耗时) | duration_seconds_sum by route | **seconds** | bar yAxisName="s" | ✓ |
| 全站 P50/P95/P99 | duration_seconds(histogram) | **seconds** | histogram-quantile 直接读 | ✓ |
| 按接口 - count | duration_seconds_count | count | raw | ✓ |
| 按接口 - avgMs | (sum / count) × 1000 | **ms** | sum 是 seconds,×1000 转 ms | ✓ |
| **按接口 - lastQps** | **duration_seconds_count agg=rate** | **rate/s** | **后端 agg=rate 静默回退到 mean,值不是 rate** | **✗** |
| **按接口 - QPS 图** | **同 lastQps** | **rate/s** | **同上** | **✗** |
| 按接口 - P50/P95/P99 | duration_seconds(histogram) | **seconds** | **×1000 转 ms** | ✓ |
| 按接口 - QPS 图 | duration_seconds_count agg=rate | rate/s | 同 lastQps | ✗(同上) |

### 2.2 Bug 分析

**Bug-H1 `agg: 'rate'` 静默回退(强)**
`HttpPane.vue:196` 调 `/api/metrics/series?...&agg=rate`,后端 `MetricController` 的
`series` 只识别 `max/min/sum/last`,其余落到 `default → mean`。对一个单调递增的
counter,`mean(30s 窗口)` 等于该窗口内的**累计值**,不是速率。**所以
`lastQps`、路由的 QPS 折线图全错**——它们显示的是累计请求数,不是 QPS。

**Bug-H2 `Top10 路由(累计耗时)` 标签 vs 含义(弱)**
`yAxisName="s"` 和 series 名 "累计耗时(s)" 都明确说是"累计"——单位正确,但是用户
**很可能误以为是平均/最大耗时**。建议改 label 为"总耗时(s)"或"耗时求和(s)"。
本次先不改,留给产品层决定。

### 2.3 修复

- 不改前端代码(本来就在传 `agg=rate`),等后端支持到位后自动生效。

## 3. JVM Pane

### 3.1 指标审计

| 指标 | OTel 指标名 | 原始单位 | 前端处理 | 状态 |
|---|---|---|---|---|
| CPU 使用率 | jvm_cpu_recent_utilization | 0~1 | formatPercent | ✓ |
| 当前加载类 | jvm_class_count | count | raw | ✓ |
| 累计已加载类 | jvm_class_loaded_total | count | raw | ✓ |
| 累计已卸载类 | jvm_class_unloaded_total | count | raw | ✓ |
| 总线程数 | jvm_thread_count | count | raw(sum by tag) | ✓ |
| JVM CPU 时间累计 | jvm_cpu_time_seconds_total | **seconds** | toFixed + "s" | ✓ |
| 堆/非堆内存图 | jvm_memory_used_bytes | **bytes** | ÷ 1048576 → MB | ✓ |
| 各 pool 已用 vs 上限 | used/limit | % | × 100 | ✓ |
| 堆 committed | jvm_memory_committed_bytes | bytes | ÷ 1048576 | ✓ |
| GC 累计次数 | jvm_gc_duration_seconds_count | count | raw(注意:这是 histogram 的 count) | ✓ |
| GC 平均耗时 | sum / count | **seconds** | **× 1000 → ms** | ✓ |
| GC 耗时分位 | jvm_gc_duration_seconds(histogram) | **seconds** | **× 1000 → ms** | ✓ |
| GC 后剩余内存 | jvm_memory_used_after_last_gc_bytes | bytes | ÷ 1048576 | ✓ |
| 线程分布 | jvm_thread_count grouped by state,daemon | count | bar | ✓ |
| **类加载/卸载速率** | **jvm_class_loaded/unloaded_total agg=rate** | **rate/s** | **后端 rate 静默回退** | **✗** |

### 3.2 Bug 分析

**Bug-V1 类加载/卸载速率(强)**
`JvmPane.vue:330-331` 传 `agg: 'rate'`,后端落到 `mean`。
- 实际显示:每个 30s 窗口内 jvm_class_loaded 的**累计值**(始终在涨)
- 应该显示:classes/s(类加载/卸载速率)

### 3.3 修复

- 不改前端,等后端支持到位后自动生效。

## 4. OS Pane

### 4.1 指标审计

| 指标 | OTel 指标名 | 原始单位 | 前端处理 | 状态 |
|---|---|---|---|---|
| CPU 核心数 | jvm_cpu_count | count | raw | ✓ |
| 系统内存使用率 | system_memory_utilization | 0~1 | formatPercent | ✓ |
| Java 进程 RSS | runtime_java_memory_bytes{type=rss} | bytes | formatMB | ✓ |
| Java 进程 VMS | runtime_java_memory_bytes{type=vms} | bytes | formatMB | ✓ |
| 系统内存图 | system_memory_usage_bytes{state=used/free} | bytes | ÷ 1048576 | ✓ |
| Java CPU 时间 | runtime_java_cpu_time_milliseconds{type=user/system} | **ms** | raw,yAxisName="ms" | ✓ |
| 系统内存饼 | system_memory_usage_bytes | bytes | raw(pie 不需单位) | ✓ |
| **磁盘 IO 字节** | **system_disk_io_bytes_total** | **bytes(累计)** | **÷ 1048576 显示 "MB",值是 5m 窗口末的累计** | **✗** |
| **磁盘 IOPS** | **system_disk_operations_total** | **ops(累计)** | **label=IOPS(暗示每秒),值是累计** | **✗** |
| **网络 IO 字节** | **system_network_io_bytes_total** | **bytes(累计)** | **÷ 1048576 显示 "MB"** | **✗** |
| **网络收发包** | **system_network_packets_total** | **pkts(累计)** | **label=收发包(暗示速率)** | **✗** |
| **网络错误数** | **system_network_errors_total** | **errs(累计)** | **label=错误数(暗示速率)** | **✗** |

### 4.2 Bug 分析

**Bug-O1 5 个 counter bar 全是累计值(强)**
`OsPane.vue:95-99` 走 `/api/metrics/grouped`,后端 Flux 是 `range(-5m) |> ... |> last()`,
对 counter 来说就是"过去 5 分钟结束时这个指标的总累计"。**这 5 个图画的全是
"总累计 IO/包/错"**,不是用户期望的"瞬时速率"。这是和 2/3 同一类问题,但
这里走的是 `grouped` 端点,之前的 `queryGrouped` 方法没暴露 `agg` 参数。

**Bug-O2 Java CPU 时间图(弱,信息量问题)**
`runtime_java_cpu_time_milliseconds{type=user/system}` 是累计 ms,折线图基本
一条单调上升线,看不出"哪个时段 CPU 忙"。**这是设计问题不是单位问题**——
理论上应该 derivative 算速率,或干脆改成 gauge 显示瞬时 CPU 时间。本次先不动。

### 4.3 修复

- `OsPane.vue:95-99`:全部加 `agg: 'rate'`,把 yAxisName 改成 `MB/s` / `ops/s`
  / `pkts/s` / `errs/s`,语义和单位同时对齐
- `OsPane.vue:131`:`renderGroupedBar` 加 `agg` 参数透传
- 后端 `queryGrouped` 暴露 `agg` 入参(见 5)

## 5. 后端修复(根因,影响最大)

**根因**:`MetricQueryService.querySeries` / `queryGrouped` 不识别 `agg=rate`,
前端老老实实传 `agg=rate` 却被静默吞掉,回退到 `mean`。

`MetricController` 的 switch 漏了 `rate` case;Flux 也没注入 `derivative()`。

### 5.1 `MetricQueryService.querySeries` (MetricQueryService.java:155-176)

```java
String aggLower = agg == null ? "mean" : agg.toLowerCase();
String aggFn = switch (aggLower) {
    case "max" -> "max";
    case "min" -> "min";
    case "sum" -> "sum";
    case "last" -> "last";
    case "rate" -> "mean";   // ← 仍走 mean,rate 含义由 derivative 提供
    default -> "mean";
};
String rateStep = "rate".equals(aggLower)
        ? "|> derivative(unit: 1s, nonNegative: true)\n"
        : "";
```

生成的 Flux:

```
from(bucket: ...)
  |> range(start: ..., stop: ...)
  |> filter(...)
  |> derivative(unit: 1s, nonNegative: true)   // 只在 agg=rate 时插入
  |> aggregateWindow(every: 30s, fn: mean, createEmpty: false)
  |> yield(name: "series")
```

`derivative(unit: 1s)` 把 counter 转成"每秒增量",`nonNegative: true` 把可能的负
值(重启/重置)截到 0,再按 30s 窗口求平均就拿到稳定速率。

### 5.2 `MetricQueryService.queryGrouped` (MetricQueryService.java:220-242)

同上,加 `agg` 入参 + rateStep。

### 5.3 `MetricController.grouped` (MetricController.java:104-111)

```java
public ApiResponse<Map<String, Object>> grouped(...
    @RequestParam(required = false, defaultValue = "last") String agg) {
    ...
    return ApiResponse.ok(metricQueryService.queryGrouped(appid, metric, groupBy, agg));
}
```

新加 `agg` 查询参数,默认 `last` 保持向后兼容。

## 6. 修复文件清单

| 文件 | 改动 |
|---|---|
| `src/main/java/.../service/MetricQueryService.java` | querySeries 加 rate 分支 + derivative 注入;queryGrouped 加 agg 入参 |
| `src/main/java/.../web/MetricController.java` | grouped 端点加 `agg` 参数 |
| `frontend/src/views/appdetail/JdbcPane.vue:133` | QPS 图改用 `agg: 'rate'` |
| `frontend/src/views/appdetail/JdbcPane.vue:282/298/316` | 三个"分布"图改名"使用次数趋势(累计 count)" |
| `frontend/src/views/appdetail/OsPane.vue:95-99` | 5 个 counter bar 加 `agg: 'rate'`,yAxisName 改 /s |
| `frontend/src/views/appdetail/OsPane.vue:131` | renderGroupedBar 加 `agg` 透传 |
| `src/main/resources/static/pages/app-detail.html:82/92/102` | 同 JdbcPane 标题改名 |
| `src/main/resources/static/pages/app-detail.html:410` | QPS 改 `agg: "rate"` |
| `src/main/resources/static/pages/app-detail.html:958-967` | 同 OsPane 加 `agg: "rate"` |
| `src/main/resources/static/pages/app-detail.html:991-992` | renderGroupedBar 加 agg 透传 |

## 7. 没动的项目(留给后续)

1. **JdbcPane 真 le 桶分布**:把 `renderHistogram` 改成读 `*_time_milliseconds_bucket{le=...}`,
   group by le 画 bar。需要后端支持 histogram bucket 拉取接口(目前 `series` 不带
   le 维度聚合),工作量大,留给后续。
2. **OS Pane CPU 时间图**:改成 derivative 算瞬时 CPU 时间,或者替换成 Micrometer
   `process.cpu.time` 的速率视图。
3. **HttpPane Top10 路由(累计耗时)**:label 改"总耗时(s)"避免歧义,等业务确认
   期望语义。
4. **统一 fetchSeries**:现在 `fetchSeries(tagFilters = { agg: 'rate' })` 这个用
   法有点 hack,建议加个 `agg` 独立参数,跟 `every` 同级。
