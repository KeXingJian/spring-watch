# 指标显示原理(以自监控页为例)

本文档说明 spring-watch 自监控页上**每一个数字是怎么从最原始的数据**一步步变到屏幕上
的。目的是给「渲染层正确性检查」建立一条可验证链路——只要这条链路上任何一环的**单位
不匹配**,最终的展示就会出错。`启动时长` 显示 `16d 17h` 就是这条链路上最经典的反例。

## 0. 总览:三段链路

```
原始字节/事件 ──① 采集(后端)──> Sample 内存对象 ──② 序列化(Controller)──> JSON ──③ 渲染(前端)──> 屏幕
```

每段都有它**固有的单位约定**,任意一段换单位,后面的所有换算都失效。

| 段 | 位置 | 单位约定 | 这次踩的坑 |
|---|---|---|---|
| ① 采集 | `SelfMonitorCollector` | 与 JDK/Micrometer 一致,**不擅自换算** | `getUptime()` 拿到的就是**毫秒**,直接塞进字段 |
| ② 序列化 | `SelfMonitorController` | 仅做结构包装,不做单位换算 | 字段名写成 `uptimeSec`,**名字撒谎** |
| ③ 渲染 | `SelfMonitorView.vue` / 静态页 | 按字段名约定的单位做格式化 | `fmtUptime(sec)` 拿到毫秒当秒用,`/86400` 算天数 |

**根因**:段②字段名跟段①实际单位不一致,段③照着错误名字做除法,数值被放大 ~1000×。

## 1. 段① 采集:从 JDK/Micrometer 读原始数

`SelfMonitorCollector.sample()` 每 10s 触发一次,产出 `Sample(ts, iso, jvm, process, meters)`
放进 60 槽的 ring buffer。**这一段不改单位,只做"读"和"裁剪"**:

| 字段 | API | 返回单位 | 在 ring 里的单位 |
|---|---|---|---|
| `jvm.heap.used/max` | `MemoryMXBean.getHeapMemoryUsage()` | bytes | bytes |
| `jvm.uptimeMs` | `RuntimeMXBean.getUptime()` | **ms** | ms |
| `process.cpuLoad` | `OS.getProcessCpuLoad()` | 0~1 | 0~1 |
| `process.systemCpuLoad` | `OS.getCpuLoad()` | 0~1 | 0~1 |
| `process.rssBytes` | `OS.getCommittedVirtualMemorySize` | bytes | bytes |
| `process.systemTotalBytes` | `OS.getTotalMemorySize` | bytes | bytes |
| `process.diskFreeBytes` | `File.getUsableSpace()` | bytes | bytes |
| `meters.counters.*` | `Counter.count()` | 累计次数 | 累计次数 |
| `meters.timers.*` | `Timer.totalTime(MILLISECONDS)` | **ms** | ms |
| `meters.gauges.*` | `Gauge.value()` | 与注册时一致 | 与注册时一致 |

> 注意:`meters` 里的 key 名是**白名单**过滤后的,基数可控
> (`SelfMonitorCollector.METER_WHITELIST_PREFIXES`)。

**修复点**:`JvmSnap.uptimeSec` → `uptimeMs`,字段名终于跟实际单位对上了。

## 2. 段② 序列化:把 record 直接吐成 JSON

`SelfMonitorController.realtime()` / `timeseries()` 几乎不做事,只把 `Sample` 包装成
`{ready, size, sample: {...}}` 返回。**这一段最容易埋雷**:字段名跟段①单位必须严丝合缝。

错误示范(已修):
```java
public record JvmSnap(..., long uptimeSec, ...) {}  // 名字写 sec,实际是 ms
```

正确写法(已修):
```java
public record JvmSnap(..., long uptimeMs, ...) {}  // 名字 = 单位
```

> 经验:**字段名后缀强制带上单位**(`Ms`/`Sec`/`Bytes`/`Pct`),grep 一眼就能看出不一致。

## 3. 段③ 渲染:前端按"字段名声明的单位"格式化

`SelfMonitorView.vue` 里有四类渲染,下面逐类走一遍。

### 3.1 卡片(直接读字段,简单格式化)

| 卡片 | 取值 | 格式化 | 公式 |
|---|---|---|---|
| 在线应用 | `appCount` | 原值 | `/api/apps/active` 长度 |
| JVM 堆使用率 | `heap.used / heap.max` | `formatPercent(_, 1)` | 同单位(bytes)除 → 0~1 → % |
| 进程 CPU | `proc.cpuLoad` | `formatPercent(_, 1)` | 直接 0~1 → % |
| **启动时长** | `jvm.uptimeMs` | `fmtUptime(ms)` | **ms → /1000 → /86400/3600/60** |
| 活跃 HTTP/重试/Kafka | Micrometer Gauge | `fmtNum` | 原值 |
| 线程数 | `jvm.threads.current` | `fmtNum` | 原值 |

`fmtUptime` 修复后(关键就是最前面那行 `sec = ms / 1000`):
```ts
function fmtUptime(ms) {
  if (ms == null) return '-'
  const sec = Math.floor(ms / 1000)   // ← ms → s 的关口
  const d = Math.floor(sec / 86400)
  ...
}
```

### 3.2 折线/柱图(必须有"上一采样点")

图表数据不是直接读字段,而是**跨两个采样点算差值/均值**。这是第二类最容易出错的
地方——除数 `dt` 必须用**正确的秒数**。

```ts
// 速率:events/秒
const dt = (samples[i].ts - samples[i-1].ts) / 1000   // ms → s
const rate = (cur - prev) / dt                        // 累计计数差 / 秒

// 平均延迟:ms/次
const dc = cur.count - prev.count                     // 次
const dt = cur.totalMs - prev.totalMs                 // ms
const mean = dc > 0 ? dt / dc : null                  // ms / 次
```

> 经验:rate 的分母永远要是**秒**(`/1000`),latency 的分子永远要是**毫秒**(`Timer.totalTime(MILLISECONDS)`)。
> 一旦改用 `NANOSECONDS`,整个图表单位会偏 1e6 倍。

### 3.3 系统资源 KV 网格

直接读 `process.*` 字段,`formatBytes` 处理 bytes 家族,`formatPercent` 处理 0~1
家族。无中间换算,但 `formatBytes` 内部会自动选 B/KB/MB/GB,没有歧义。

### 3.4 原始 Micrometer 表格

最后那张表只展示**最近一次采样**的 meter 快照,不做任何跨点计算:

| 列 | 来源 | 含义 |
|---|---|---|
| 值 | Counter `count()` / Gauge `value()` | 原始数值 |
| count | Timer `count()` | 累计触发次数 |
| total ms | Timer `totalTime(MILLISECONDS)` | 累计耗时(ms) |
| max ms | Timer `max(MILLISECONDS)` | 单次最大(ms) |

`fmtNum` 给数字加千分位,`toFixed(1/2)` 控制小数位,没有单位换算。

## 4. 单位换算自查清单

每加一个新指标,过一遍这张表:

1. **API 返回什么单位?** 查 JDK/Javadoc / Micrometer 文档,**别凭印象**。
   - 这次错的根因就是以为 `getUptime()` 是秒。
2. **字段名跟单位一致吗?** 字段后缀必须是真实单位(`Ms` / `Sec` / `Bytes` / `Pct`)。
3. **跨采样点的除法分母用对了吗?** 速率用秒,延迟用毫秒,吞吐量用字节。
4. **前端格式化函数入参声明的单位跟字段一致吗?** `fmtUptime(ms)` 必须配 `uptimeMs`。

## 5. 这次修复涉及的文件

| 文件 | 改动 |
|---|---|
| `src/main/java/.../monitor/SelfMonitorCollector.java:277` | `uptimeSec` → `uptimeMs` |
| `frontend/src/views/SelfMonitorView.vue:75-82, 148, 165` | `fmtUptime` 入参改 ms,加 `/1000`;调用点改 `uptimeMs` |
| `src/main/resources/static/pages/self-monitor.html:132-140, 201, 218` | 同上(静态页兜底) |

## 6. 同型 bug 第 4 个:`ProcessSnap.rssBytes` 实际是 `heapUsed`

用户问"为什么任务管理器显示 java 进程 350 MB,但自监控只显示堆 127 MB",排查发现:

`captureProcess` 第 3 个参数 `heapUsed` 被塞进 `rssBytes` 字段——和 `uptimeSec` 同型。
所以**真正的进程 RSS 根本没采**,前端看不到任务管理器那个数。

修复:
- 加 `readRssBytes()` 按平台分派:Linux 读 `/proc/self/status` 的 `VmRSS`,Windows 起 `powershell.exe` 查 `(Get-Process -Id <pid>).WorkingSet64`,其它返回 -1
- `ProcessSnap` 增 `heapUsed` / `nonHeapUsed` 字段,把 `rssBytes` 真正填 RSS
- 自监控加"进程内存 (RSS)"卡片,跟任务管理器同源
- 第 7 节"进程 RSS"图改 4 条线:RSS / JVM 堆 / JVM 非堆 / 虚拟内存

**为什么不用 JNA**:Micrometer 自身用 `com.sun.management.OperatingSystemMXBean`(JDK 自带),但
这 API **没有 RSS 字段**——只有 `getCommittedVirtualMemorySize()`(虚拟内存)和 `getProcessCpuLoad()`。
要拿真 RSS 只有两条路:
1. JNA 调 `Kernel32.GetProcessMemoryInfo`(Windows) / 读 `/proc/<pid>/status`(Linux):增依赖 + 跨平台
2. 标准库:Linux 读 procfs,Windows 起 powershell 拿 `WorkingSet64`

我选第 2 条,无新依赖,代价是 Windows 每次采样多 ~200ms 起的 powershell 进程(10s 周期可接受)。

`docs/self-monitor-process-memory-fix.md` 单独文档化了这次修复。

## 7. JVM 内存全景对照表(给同事科普用)

| 区域 | 含义 | 任务管理器能看到吗 | JVM 堆使用率能看到吗 | Spring Boot 大致占用 |
|---|---|---|---|---|
| Young + Old gen | 对象实例 | 部分(RSS 一部分) | ✓ | 100~500 MB |
| Metaspace | 类元数据 | ✓ | ✗ | 50~150 MB |
| CodeCache | JIT 编译产物 | ✓ | ✗ | 30~80 MB |
| Thread stacks | 线程调用栈 | ✓ | ✗ | N × 1MB(线程数) |
| DirectByteBuffer | NIO 直接内存 | ✓ | ✗ | 0~几百 MB(看 NIO 用量) |
| GC internal | G1 region / card table | ✓ | ✗ | 30~100 MB |
| mmap jar/.so | 映射文件 | ✓ | ✗ | 几十 MB |

所以"350 vs 127"完全正常——堆只是进程总内存的一部分。
