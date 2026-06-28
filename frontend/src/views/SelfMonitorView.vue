<script setup lang="ts">
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { api } from '@/api/client'
import { formatBytes, formatPercent, formatNumber } from '@/utils/format'
import { labelOf } from '@/utils/metricLabels'
import Chart from '@/charts/Chart.vue'
import EmptyState from '@/components/EmptyState.vue'
import MetricCard from '@/components/MetricCard.vue'
import InfluxDbPane from '@/views/selfmonitor/InfluxDbPane.vue'
import KafkaPane from '@/views/selfmonitor/KafkaPane.vue'
import type { LineSeriesItem, BarSeriesItem } from '@/charts/types'

type Tab = 'overview' | 'collect' | 'jvm' | 'process' | 'meters' | 'influxdb' | 'kafka'
const tabs: { key: Tab; label: string }[] = [
  { key: 'overview', label: '总览' },
  { key: 'collect',  label: '采集' },
  { key: 'jvm',      label: 'JVM' },
  { key: 'process',  label: '进程' },
  { key: 'meters',   label: '指标库' },
  { key: 'influxdb', label: 'InfluxDB 自身' },
  { key: 'kafka',    label: 'Kafka 集群' }
]
const activeTab = ref<Tab>('overview')

type Range = '5m' | '15m' | '1h' | '6h' | '24h'
type Agg = 'mean' | 'max' | 'min' | 'sum' | 'last' | 'rate'
type Point = [number, number | null]
type FetchOpts = { category: 'jvm' | 'process' | 'meter'; metric: string; meterType?: string; gcName?: string; agg: Agg; every?: string; field?: string }

const realtime = ref<any>(null)
const appCount = ref<number | null>(null)
const rangeLoading = ref(false)
const rangeError = ref<string | null>(null)

/** 时间范围：5m/15m/1h/6h/24h 统一走 InfluxDB series 接口（自监控已落 self_metrics 桶，保留 25h） */
const range = ref<Range>('5m')
const rangeOptions: Range[] = ['5m', '15m', '1h', '6h', '24h']
const rangeStartTs = computed(() => Date.now() - rangeMs())
const rangeEndTs = computed(() => Date.now())

/** 刷新挡位：30s / 60s；图表、顶部 cards、底部 meter 表统一这一个节奏 */
const pollSec = ref<number>(30)
const pollOptions = [30, 60] as const
function rangeMs() {
  if (range.value === '5m') return 5 * 60 * 1000
  if (range.value === '15m') return 15 * 60 * 1000
  if (range.value === '6h') return 6 * 3600 * 1000
  if (range.value === '24h') return 24 * 3600 * 1000
  return 3600 * 1000
}
/** 自适应步长：与服务端 defaultEvery 保持一致 */
function everyForRange() {
  if (range.value === '6h') return '30s'
  if (range.value === '24h') return '1m'
  return '10s'
}

/**
 * 拉单个 metric 的时序，返回 [[tsMs, value], ...]
 * 失败 / 无数据时返回空数组，前端走 EmptyState。
 * field 默认为 "value"；timer 想看 count / total_ms 速率时传 "count" / "total_ms"。
 */
async function fetchSeries(opts: FetchOpts): Promise<Point[]> {
  const params: Record<string, unknown> = {
    category: opts.category,
    metric: opts.metric,
    from: new Date(rangeStartTs.value).toISOString(),
    to: new Date(rangeEndTs.value).toISOString(),
    agg: opts.agg,
    every: opts.every ?? everyForRange()
  }
  if (opts.meterType) params.meterType = opts.meterType
  if (opts.gcName) params.gcName = opts.gcName
  if (opts.field) params.field = opts.field
  try {
    const resp: any = await api.get('/api/self/series', params)
    const series = resp?.series || []
    if (!series.length) return []
    return (series[0].points || []).map((p: any) => [
      new Date(p.t).getTime(),
      p.v == null ? null : Number(p.v)
    ])
  } catch {
    return []
  }
}

async function fetchSeriesMulti(opts: FetchOpts): Promise<LineSeriesItem[]> {
  const params: Record<string, unknown> = {
    category: opts.category,
    metric: opts.metric,
    from: new Date(rangeStartTs.value).toISOString(),
    to: new Date(rangeEndTs.value).toISOString(),
    agg: opts.agg,
    every: opts.every ?? everyForRange()
  }
  if (opts.meterType) params.meterType = opts.meterType
  if (opts.gcName) params.gcName = opts.gcName
  try {
    const resp: any = await api.get('/api/self/series', params)
    const series = resp?.series || []
    return series.map((s: any) => ({
      name: s.name || opts.metric,
      points: (s.points || []).map((p: any) => [new Date(p.t).getTime(), p.v == null ? null : Number(p.v)])
    }))
  } catch {
    return []
  }
}

const healthPill = ref({ cls: 'warn', text: '采样中' })

const kv = reactive({
  sysCpu: '-',
  sysTotal: '-',
  sysFree: '-',
  disk: '-',
  cores: '-',
  uptime: '-',
  meta: '--',
  heapSub: '-',
  cpuSub: '-',
  uptimeSub: '-',
  threadsSub: '-',
  appsSub: '加载中…',
  retrySub: '-',
  procRss: '-',
  procRssSub: '-'
})

const cardHeap = ref<number | null>(null)
const cardHeapCls = ref('')
const cardCpu = ref<number | null>(null)
const cardCpuCls = ref('')
const cardUptime = ref('-')
const cardActive = ref<number | null>(null)
const cardRetry = ref<number | null>(null)
const cardKafka = ref<number | null>(null)
const cardThreads = ref<number | null>(null)
const cardApps = ref<number | null>(null)
/** M4-4: 新增两张失败/降级卡 */
const cardBodyRejected = ref<number | null>(null)
const cardKafkaRejected = ref<number | null>(null)

const trafficChart = ref<LineSeriesItem[]>([])
const dedupChart = ref<LineSeriesItem[]>([])
const failChart = ref<LineSeriesItem[]>([])
const writeChart = ref<LineSeriesItem[]>([])
const dedupOpChart = ref<LineSeriesItem[]>([])
const metricQBar = ref<{ categories: string[]; series: BarSeriesItem[] }>({ categories: [], series: [] })
const logQBar = ref<{ categories: string[]; series: BarSeriesItem[] }>({ categories: [], series: [] })
const httpOutcomeChart = ref<LineSeriesItem[]>([])
const httpLatChart = ref<LineSeriesItem[]>([])
const jvmMemChart = ref<LineSeriesItem[]>([])
const jvmPoolChart = ref<LineSeriesItem[]>([])
const jvmThreadsChart = ref<LineSeriesItem[]>([])
const jvmGcChart = ref<LineSeriesItem[]>([])
const procCpuChart = ref<LineSeriesItem[]>([])
const procRssChart = ref<LineSeriesItem[]>([])

const meterRows = ref<any[]>([])
const meterCount = ref(0)

const meterFlat = computed(() => {
  const result: any[] = []
  let lastGroup: string | null = null
  for (const r of meterRows.value) {
    if (r.group !== lastGroup) {
      result.push({ kind: 'group', group: r.group })
      lastGroup = r.group
    }
    result.push({ kind: 'row', ...r })
  }
  return result
})

function fmtUptime(ms: number | null | undefined) {
  if (ms == null) return '-'
  const sec = Math.floor(ms / 1000)
  const d = Math.floor(sec / 86400)
  const h = Math.floor((sec % 86400) / 3600)
  const m = Math.floor((sec % 3600) / 60)
  if (d > 0) return d + 'd ' + h + 'h'
  if (h > 0) return h + 'h ' + m + 'm'
  return m + 'm ' + (sec % 60) + 's'
}

function fmtNum(n: number | null | undefined) {
  if (n == null || isNaN(n)) return '-'
  return n.toFixed(0).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

function gaugeVal(name: string) {
  if (!realtime.value || !realtime.value.meters) return null
  return (realtime.value.meters.gauges || {})[name]
}

function counterVal(name: string) {
  if (!realtime.value || !realtime.value.meters) return null
  return (realtime.value.meters.counters || {})[name]
}

function groupOf(name: string) {
  if (name.startsWith('spring.watch.consumer.metric')) return '指标采集'
  if (name.startsWith('spring.watch.consumer.log')) return '日志采集'
  if (name.startsWith('spring.watch.consumer.dlq')) return 'DLQ'
  if (name.startsWith('spring.watch.metric.query')) return '指标查询'
  if (name.startsWith('spring.watch.log.query')) return '日志查询'
  if (name.startsWith('spring.watch.aggregator.log')) return '日志聚合'
  if (name.startsWith('spring.watch.ingest.log.dedup')) return '日志去重'
  if (name.startsWith('spring.watch.ingest.log.fingerprint')) return '日志指纹'
  if (name.startsWith('spring.watch.collector.http')) return 'HTTP 抓取'
  if (name.startsWith('spring.watch.collector.retry')) return '重投队列'
  if (name.startsWith('spring.watch.collector.kafka')) return 'Kafka 兜底'
  if (name.startsWith('spring.watch.collector.host')) return '主机限流'
  if (name.startsWith('spring.watch.alerter.jexl')) return '告警评估'
  if (name.startsWith('spring.watch.alert.history')) return '告警历史'
  return '其他'
}
function groupOrder() {
  return ['指标采集', '日志采集', 'DLQ', '指标查询', '日志查询', '日志聚合', '日志去重', '日志指纹', 'HTTP 抓取', '重投队列', 'Kafka 兜底', '主机限流', '告警评估', '告警历史', '其他']
}

function renderCards() {
  if (!realtime.value) {
    healthPill.value = { cls: 'warn', text: '未就绪' }
    return
  }
  const jvm = realtime.value.jvm || {}
  const proc = realtime.value.process || {}
  const heap = jvm.heap || {}
  const used = heap.used || 0
  const max = heap.max || -1
  const heapPct = max > 0 ? used / max : 0
  const cpu = proc.cpuLoad || 0
  cardHeap.value = heapPct
  cardHeapCls.value = heapPct > 0.85 ? 'danger' : heapPct > 0.7 ? 'warn' : 'success'
  kv.heapSub = formatBytes(used) + ' / ' + (max > 0 ? formatBytes(max) : '-')
  cardCpu.value = cpu
  cardCpuCls.value = cpu > 0.85 ? 'danger' : cpu > 0.7 ? 'warn' : 'success'
  kv.cpuSub = '系统 ' + formatPercent(proc.systemCpuLoad || 0, 1)
  cardUptime.value = fmtUptime(jvm.uptimeMs)
  kv.uptimeSub = '采样 ' + (realtime.value.iso || '-')
  cardActive.value = gaugeVal('spring.watch.collector.http.active')
  cardRetry.value = gaugeVal('spring.watch.collector.retry.queue.size')
  kv.retrySub = '已注册主机 ' + fmtNum(gaugeVal('spring.watch.collector.host_throttler.active'))
  cardKafka.value = gaugeVal('spring.watch.collector.kafka.fallback.size')
  /** M4-4: body.rejected 与 kafka.fallback.rejected 都是 Counter,realtime snapshot 给的是 .count() */
  cardBodyRejected.value = counterVal('spring.watch.collector.http.body.rejected')
  cardKafkaRejected.value = counterVal('spring.watch.kafka.fallback.rejected')
  const th = jvm.threads || {}
  cardThreads.value = th.current == null ? null : th.current
  kv.threadsSub = '守护 ' + (th.daemon || 0) + ' / 峰值 ' + (th.peak || 0)
  cardApps.value = appCount.value
  kv.appsSub = '已注册监控应用'

  kv.sysCpu = formatPercent(proc.systemCpuLoad || 0, 1)
  kv.sysTotal = formatBytes(proc.systemTotalBytes)
  kv.sysFree = formatBytes(proc.systemFreeBytes)
  kv.disk = formatBytes(proc.diskFreeBytes)
  kv.cores = (proc.cpuCores || 0) + ' 核'
  kv.uptime = fmtUptime(jvm.uptimeMs)
  // 进程 RSS:任务管理器看到的数(包含堆 + 非堆 + 堆外 + GC + mmap)
  if (proc.rssBytes != null && proc.rssBytes > 0) {
    kv.procRss = formatBytes(proc.rssBytes)
    const heap = proc.heapUsed || 0
    const nonHeap = proc.nonHeapUsed || 0
    kv.procRssSub = `堆 ${formatBytes(heap)} · 非堆 ${formatBytes(nonHeap)}`
  } else {
    kv.procRss = '-'
    kv.procRssSub = '不支持该平台'
  }

  if (heapPct > 0.9 || cpu > 0.9) healthPill.value = { cls: 'bad', text: '高负载' }
  else if (heapPct > 0.75 || cpu > 0.75) healthPill.value = { cls: 'warn', text: '负载偏高' }
  else healthPill.value = { cls: 'ok', text: '正常' }
  kv.meta = '采样间隔 10s · InfluxDB 25h 保留 · ' + (realtime.value.iso || '')
}

function renderMeterTable() {
  if (!realtime.value || !realtime.value.meters) {
    meterRows.value = []
    meterCount.value = 0
    return
  }
  const counters = realtime.value.meters.counters || {}
  const timers = realtime.value.meters.timers || {}
  const gauges = realtime.value.meters.gauges || {}
  const rows: any[] = []
  for (const k of Object.keys(counters).sort()) rows.push({ name: k, type: 'Counter', val: counters[k], count: null, total: null, max: null, group: groupOf(k) })
  for (const k of Object.keys(timers).sort()) {
    const t = timers[k]
    rows.push({ name: k, type: 'Timer', val: null, count: t.count, total: t.totalMs, max: t.maxMs, group: groupOf(k) })
  }
  for (const k of Object.keys(gauges).sort()) rows.push({ name: k, type: 'Gauge', val: gauges[k], count: null, total: null, max: null, group: groupOf(k) })
  const order = groupOrder()
  rows.sort((a, b) => {
    const ga = order.indexOf(a.group), gb = order.indexOf(b.group)
    if (ga !== gb) return ga - gb
    return a.name.localeCompare(b.name)
  })
  meterRows.value = rows
  meterCount.value = rows.length
}

/**
 * 5m/15m/1h/6h/24h 视图：从 InfluxDB 拉每个 chart 所需的 metric，填充到同一组 chart refs。
 * 用 Promise.all 并发拉取，减少切换时的等待感。
 * 失败 / 缺数据时该 chart 留空，前端走 EmptyState。
 * meter 表与 cards 由 pollRealtime() 单独驱动(5s 轮询 /realtime),不复用本函数的 InfluxDB 路径。
 */
async function renderAllRange() {
  // 兜底：先把所有 chart 引用重置为 []，确保任意分支出错也能显示 EmptyState 而不是"什么都没有"
  const empty: LineSeriesItem[] = []
  trafficChart.value = empty
  dedupChart.value = empty
  failChart.value = empty
  writeChart.value = empty
  dedupOpChart.value = empty
  httpOutcomeChart.value = empty
  httpLatChart.value = empty
  jvmMemChart.value = empty
  jvmPoolChart.value = empty
  jvmThreadsChart.value = empty
  jvmGcChart.value = empty
  procCpuChart.value = empty
  procRssChart.value = empty
  metricQBar.value = { categories: [], series: [] }
  logQBar.value = { categories: [], series: [] }

  const m = (category: 'jvm' | 'process' | 'meter', metric: string, agg: Agg, meterType?: string, gcName?: string) => {
    const o: { category: 'jvm' | 'process' | 'meter'; metric: string; agg: Agg; meterType?: string; gcName?: string } = { category, metric, agg }
    if (meterType) o.meterType = meterType
    if (gcName) o.gcName = gcName
    return o
  }

  const safe = async <T,>(p: Promise<T>, fallback: T): Promise<T> => {
    try { return await p } catch (e) { console.warn('[self-monitor series fetch fail]', e); return fallback }
  }

  const [
    recv_metric, recv_log, kept_log, persisted_dlq,
    deduped, alert_cand,
    parse_fail_m, write_fail_m, parse_fail_l, write_fail_l, persist_fail_dlq,
    keep, drop, flush, flush_fail,
    http_ok, http_fail, http_timeout, http_non2xx, http_body_rej,
    http_calls, retry_enq, retry_drop,
    write_calls_m, write_calls_l,
    kafka_rej,
    metric_q_latest, metric_q_series, metric_q_grouped, metric_q_histogram, metric_q_fail,
    log_q_search, log_q_patterns, log_q_levels, log_q_trace, log_q_context, log_q_dedup_top, log_q_fail,
    jvm_heap, jvm_meta, jvm_nonheap,
    jvm_pool_used,
    jvm_thr_cur, jvm_thr_daemon, jvm_cls_loaded,
    proc_cpu, proc_sys_cpu,
    proc_rss, proc_heap, proc_nonheap, proc_virt,
    gc_time_multi
  ] = await Promise.all([
    safe(fetchSeries(m('meter', 'spring.watch.consumer.metric.received', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.consumer.log.received', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.consumer.log.kept', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.consumer.dlq.persisted', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.consumer.log.deduped', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.consumer.log.alert_candidate', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.consumer.metric.parse_fail', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.consumer.metric.write_fail', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.consumer.log.parse_fail', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.consumer.log.write_fail', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.consumer.dlq.persist_fail', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.ingest.log.dedup.keep', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.ingest.log.dedup.drop', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.ingest.log.dedup.flush', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.ingest.log.dedup.flush_fail', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.collector.http.success', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.collector.http.failure', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.collector.http.timeout', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.collector.http.non2xx', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.collector.http.body.rejected', 'rate', 'counter')), []),
    safe(fetchSeries({ ...m('meter', 'spring.watch.collector.http.request', 'rate', 'timer'), field: 'count' }), []),
    safe(fetchSeries(m('meter', 'spring.watch.collector.retry.enqueued', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.collector.retry.dropped', 'rate', 'counter')), []),
    safe(fetchSeries({ ...m('meter', 'spring.watch.consumer.metric.write', 'rate', 'timer'), field: 'count' }), []),
    safe(fetchSeries({ ...m('meter', 'spring.watch.consumer.log.write', 'rate', 'timer'), field: 'count' }), []),
    safe(fetchSeries(m('meter', 'spring.watch.kafka.fallback.rejected', 'rate', 'counter')), []),
    safe(fetchSeries({ ...m('meter', 'spring.watch.metric.query.latest', 'rate', 'timer'), field: 'count' }), []),
    safe(fetchSeries({ ...m('meter', 'spring.watch.metric.query.series', 'rate', 'timer'), field: 'count' }), []),
    safe(fetchSeries({ ...m('meter', 'spring.watch.metric.query.grouped', 'rate', 'timer'), field: 'count' }), []),
    safe(fetchSeries({ ...m('meter', 'spring.watch.metric.query.histogram', 'rate', 'timer'), field: 'count' }), []),
    safe(fetchSeries(m('meter', 'spring.watch.metric.query.fail', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.log.query.search', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.log.query.patterns', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.log.query.levels', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.log.query.trace', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.log.query.context', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.log.query.dedup_top', 'rate', 'counter')), []),
    safe(fetchSeries(m('meter', 'spring.watch.log.query.fail', 'rate', 'counter')), []),
    safe(fetchSeries(m('jvm', 'heap.used', 'last')).then((p) => p.map(toMb)), []),
    safe(fetchSeries(m('jvm', 'metaspace.used', 'last')).then((p) => p.map(toMb)), []),
    safe(fetchSeries(m('jvm', 'nonHeap.used', 'last')).then((p) => p.map(toMb)), []),
    safe(
      fetchSeriesMulti(m('jvm', 'pool.used', 'last')).then((arr) =>
        arr.map((s) => ({
          name: s.name,
          points: (s.points as Point[]).map(toMb)
        }))
      ),
      []
    ),
    safe(fetchSeries(m('jvm', 'threads.current', 'last')), []),
    safe(fetchSeries(m('jvm', 'threads.daemon', 'last')), []),
    safe(fetchSeries(m('jvm', 'classes.loaded', 'last')), []),
    safe(fetchSeries(m('process', 'cpu_load', 'mean')).then((p) => p.map(toPercent)), []),
    safe(fetchSeries(m('process', 'system_cpu_load', 'mean')).then((p) => p.map(toPercent)), []),
    safe(fetchSeries(m('process', 'rss_bytes', 'last')).then((p) => p.map(toMb)), []),
    safe(fetchSeries(m('process', 'heap_used', 'last')).then((p) => p.map(toMb)), []),
    safe(fetchSeries(m('process', 'non_heap_used', 'last')).then((p) => p.map(toMb)), []),
    safe(fetchSeries(m('process', 'virtual_bytes', 'last')).then((p) => p.map(toMb)), []),
    safe(fetchSeriesMulti(m('jvm', 'gc.time_ms', 'rate')), [])
  ])

  trafficChart.value = [
    { name: '指标 received/s', points: pack(recv_metric) },
    { name: '日志 received/s', points: pack(recv_log) },
    { name: '日志 kept/s', points: pack(kept_log) },
    { name: 'DLQ persisted/s', points: pack(persisted_dlq) }
  ]
  dedupChart.value = [
    { name: '日志 deduped/s', points: pack(deduped) },
    { name: '告警候选/s', points: pack(alert_cand) }
  ]
  failChart.value = [
    { name: '指标 parse_fail', points: pack(parse_fail_m) },
    { name: '指标 write_fail', points: pack(write_fail_m) },
    { name: '日志 parse_fail', points: pack(parse_fail_l) },
    { name: '日志 write_fail', points: pack(write_fail_l) },
    { name: 'DLQ persist_fail', points: pack(persist_fail_dlq) },
    { name: 'HTTP body 超限', points: pack(http_body_rej) },
    { name: 'Kafka 兜底被拒', points: pack(kafka_rej) }
  ]
  // Timer mean latency 服务端难精确表达：用 count 速率（calls/s）当"忙碌度"占位。
  // 若要看 ms 级延迟,看 /api/self/realtime 的 realtime snapshot（卡里有 max/total/count）。
  writeChart.value = [
    { name: '指标 write calls/s', points: pack(write_calls_m) },
    { name: '日志 write calls/s', points: pack(write_calls_l) }
  ]
  dedupOpChart.value = [
    { name: 'keep/s', points: pack(keep) },
    { name: 'drop/s', points: pack(drop) },
    { name: 'flush/s', points: pack(flush) },
    { name: 'flush_fail/s', points: pack(flush_fail) }
  ]
  httpOutcomeChart.value = [
    { name: 'success/s', points: pack(http_ok) },
    { name: 'failure/s', points: pack(http_fail) },
    { name: 'timeout/s', points: pack(http_timeout) },
    { name: 'non2xx/s', points: pack(http_non2xx) }
  ]
  httpLatChart.value = [
    { name: 'HTTP 请求调用/s', points: pack(http_calls) },
    { name: '重投 enqueue/s', points: pack(retry_enq) },
    { name: '重投 dropped/s', points: pack(retry_drop) }
  ]
  jvmMemChart.value = [
    { name: '堆已用 MB', points: pack(jvm_heap), area: true },
    { name: 'Metaspace MB', points: pack(jvm_meta) },
    { name: '非堆 MB', points: pack(jvm_nonheap) }
  ]
  jvmPoolChart.value = (jvm_pool_used || []).map((s) => ({
    name: poolLabel(s.name),
    points: s.points
  }))
  jvmThreadsChart.value = [
    { name: '线程数', points: pack(jvm_thr_cur) },
    { name: '守护线程', points: pack(jvm_thr_daemon) },
    { name: '已加载类', points: pack(jvm_cls_loaded) }
  ]
  procCpuChart.value = [
    { name: '进程 CPU', points: pack(proc_cpu) },
    { name: '系统 CPU', points: pack(proc_sys_cpu) }
  ]
  procRssChart.value = [
    { name: '进程 RSS MB', points: pack(proc_rss) },
    { name: 'JVM 堆已用 MB', points: pack(proc_heap) },
    { name: 'JVM 非堆已用 MB', points: pack(proc_nonheap) },
    { name: '进程虚拟内存 MB', points: pack(proc_virt) }
  ]

  // GC: 多个 gc_name tag 分裂成多条 series，rate = derivative of time_ms
  jvmGcChart.value = (gc_time_multi || []).map((s) => ({
    name: gcLabel(s.name),
    points: s.points,
    area: true
  }))

  metricQBar.value = buildQpsBar(
    ['最新', '时序', '分组', '直方图', '失败'],
    [metric_q_latest, metric_q_series, metric_q_grouped, metric_q_histogram, metric_q_fail]
  )
  logQBar.value = buildQpsBar(
    ['搜索', '模式', '级别', '链路', '上下文', '去重 Top', '失败'],
    [log_q_search, log_q_patterns, log_q_levels, log_q_trace, log_q_context, log_q_dedup_top, log_q_fail]
  )
}

/** Timer 指标的 mean 延迟近似：服务端难精确表达（需要 ratio of derivatives），
 *  5m/15m/1h/6h/24h 视图统一用 count 速率（events/s）当"忙碌度"占位,
 *  要看 ms 级别延迟请看底部 Micrometer 原始指标表的 max/total/count（realtime snapshot）。 */

function pack(points: Point[]): [number, number | null][] {
  return points.map(([t, v]) => [t, v])
}

/**
 * 从 series 名（形如 `gc.time_ms{gc_name=G1 Young Generation}`）里抽出 gc_name 作为图例。
 * 抽不到就原样返回，避免把内部 metric 名直接抛到 UI 上。
 */
function gcLabel(seriesName: string): string {
  const m = /\{gc_name=([^}]*)\}/.exec(seriesName || '')
  return m ? m[1] : seriesName
}

/**
 * 从 series 名（形如 `pool.used{pool_name=Eden Space}`）里抽出 pool_name 作为图例。
 * 抽不到就原样返回。
 */
function poolLabel(seriesName: string): string {
  const m = /\{pool_name=([^}]*)\}/.exec(seriesName || '')
  return m ? m[1] : seriesName
}

function toMb([t, v]: Point): Point {
  return [t, v == null ? null : v / 1048576]
}

function toPercent([t, v]: Point): Point {
  return [t, v == null ? null : v * 100]
}

/**
 * 把一段 rate 时序压成"窗口平均 QPS"：取所有非 null 点的算术平均。
 * 空序列返回 0，方便 BarSeriesItem.data 全部为数值。
 */
function meanRate(points: Point[]): number {
  let sum = 0
  let n = 0
  for (const [, v] of points) {
    if (v == null || isNaN(v as number)) continue
    sum += v as number
    n++
  }
  return n === 0 ? 0 : sum / n
}

/**
 * 把多个 rate 时序列成"按调用类型分桶"的横向柱图数据。
 * 所有桶全为 0 时返回空结构,触发 EmptyState,避免空柱图占位。
 */
function buildQpsBar(labels: string[], series: Point[][]): { categories: string[]; series: BarSeriesItem[] } {
  const data = series.map(meanRate)
  if (data.every((v) => v === 0)) return { categories: [], series: [] }
  return {
    categories: labels,
    series: [{ name: 'QPS', data }]
  }
}

async function pollRealtime() {
  try {
    const resp = await api.get<any>('/api/self/realtime')
    realtime.value = resp?.sample || null
    if (appCount.value == null) {
      try {
        const res = await api.pageFull<any>('/api/apps/active', { size: 1 })
        appCount.value = res.total
      } catch {
        appCount.value = 0
      }
    }
    renderCards()
  } catch {
    /* 静默 */
  }
}

async function pollRange() {
  rangeLoading.value = true
  rangeError.value = null
  try {
    await renderAllRange()
  } catch (e: any) {
    rangeError.value = e?.message || String(e)
    console.error('[spring-watch: renderAllRange 出错]', e)
  } finally {
    rangeLoading.value = false
  }
  // meter 表基于 realtime snapshot,放在 chart 刷新之后,确保用最新一帧渲染。
  renderMeterTable()
}

let timer1: number | null = null
let timer2: number | null = null

/**
 * 图表、cards、meter 表统一按 pollSec 挡位刷新(30s / 60s)。
 */
function rangePollMs() {
  return pollSec.value * 1000
}

function setPoll(sec: number) {
  if (pollSec.value === sec) return
  pollSec.value = sec
  startPolling()
}

/** 手动刷新:立即拉一次,不动 timer。loading 态由 pollRange 自己维护。 */
function manualRefresh() {
  pollRealtime()
  pollRange()
}

function startPolling() {
  if (timer1) { clearInterval(timer1); timer1 = null }
  if (timer2) { clearInterval(timer2); timer2 = null }
  pollRealtime()
  pollRange()
  const ms = rangePollMs()
  timer1 = window.setInterval(pollRealtime, ms)
  timer2 = window.setInterval(pollRange, ms)
}

onMounted(() => {
  startPolling()
})
onBeforeUnmount(() => {
  if (timer1) clearInterval(timer1)
  if (timer2) clearInterval(timer2)
})

watch(range, () => {
  startPolling()
})
watch(pollSec, () => {
  startPolling()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2>自身监控</h2>
      <span class="meta">{{ kv.meta }} · 范围 {{ range }}</span>
      <span class="spacer" />
      <div class="range-toggle" role="group" aria-label="时间范围">
        <button v-for="r in rangeOptions" :key="r"
                :class="['range-btn', { active: range === r }]"
                @click="range = r">{{ r }}</button>
      </div>
      <div class="range-toggle" role="group" aria-label="刷新间隔">
        <button v-for="s in pollOptions" :key="s"
                :class="['range-btn', { active: pollSec === s }]"
                :title="`每 ${s} 秒自动刷新`"
                @click="setPoll(s)">{{ s }}s</button>
      </div>
      <button class="btn btn-ghost btn-sm" :disabled="rangeLoading" @click="manualRefresh" title="立即刷新一次(不影响定时器)">刷新</button>
      <span :class="['status-pill', healthPill.cls]">{{ healthPill.text }}</span>
    </div>

    <div class="tabs">
      <div v-for="t in tabs" :key="t.key" class="tab" :class="{ active: activeTab === t.key }" @click="activeTab = t.key">
        {{ t.label }}
      </div>
    </div>

    <div v-show="activeTab === 'overview'">
      <div class="metric-cards">
        <div class="metric-card"><div class="title">在线应用</div><div><span class="value">{{ cardApps ?? '-' }}</span><span class="unit">个</span></div><div class="sub">{{ kv.appsSub }}</div></div>
        <div class="metric-card"><div class="title">JVM 堆使用率</div><div><span :class="['value', cardHeapCls]">{{ cardHeap != null ? formatPercent(cardHeap, 1) : '-' }}</span></div><div class="sub">{{ kv.heapSub }}</div></div>
        <div class="metric-card"><div class="title">进程内存 (RSS)</div><div><span class="value">{{ kv.procRss }}</span></div><div class="sub">{{ kv.procRssSub }} · 任务管理器同源</div></div>
        <div class="metric-card"><div class="title">进程 CPU</div><div><span :class="['value', cardCpuCls]">{{ cardCpu != null ? formatPercent(cardCpu, 1) : '-' }}</span></div><div class="sub">{{ kv.cpuSub }}</div></div>
        <div class="metric-card"><div class="title">启动时长</div><div><span class="value">{{ cardUptime }}</span></div><div class="sub">{{ kv.uptimeSub }}</div></div>
        <div class="metric-card"><div class="title">活跃 HTTP 抓取</div><div><span class="value">{{ cardActive != null ? fmtNum(cardActive) : '-' }}</span><span class="unit">请求</span></div><div class="sub">实时并发数</div></div>
        <div class="metric-card"><div class="title">重试队列</div><div><span class="value">{{ cardRetry != null ? fmtNum(cardRetry) : '-' }}</span><span class="unit">条</span></div><div class="sub">{{ kv.retrySub }}</div></div>
        <div class="metric-card"><div class="title">Kafka 兜底队列</div><div><span class="value">{{ cardKafka != null ? fmtNum(cardKafka) : '-' }}</span><span class="unit">条</span></div><div class="sub">Kafka 发送失败时本地堆积</div></div>
        <div class="metric-card"><div class="title">Kafka 兜底被拒</div><div><span :class="['value', cardKafkaRejected && cardKafkaRejected > 0 ? 'danger' : '']">{{ cardKafkaRejected != null ? fmtNum(cardKafkaRejected) : '-' }}</span><span class="unit">次</span></div><div class="sub">队列满后丢弃的累计数</div></div>
        <div class="metric-card"><div class="title">Agent 响应体超限</div><div><span :class="['value', cardBodyRejected && cardBodyRejected > 0 ? 'danger' : '']">{{ cardBodyRejected != null ? fmtNum(cardBodyRejected) : '-' }}</span><span class="unit">次</span></div><div class="sub">&gt; 4MB 被拒收的累计数</div></div>
        <div class="metric-card"><div class="title">线程数</div><div><span class="value">{{ cardThreads != null ? fmtNum(cardThreads) : '-' }}</span></div><div class="sub">{{ kv.threadsSub }}</div></div>
      </div>

      <div class="section-title">查询服务与主动抓取<span v-if="rangeLoading" class="tag">加载中…</span><span v-if="rangeError" class="tag" style="color: oklch(var(--er))">异常: {{ rangeError }}</span></div>
      <div class="chart-row">
        <div class="chart-panel"><div class="panel-head">指标查询 QPS<span class="tag">最近 {{ range }}</span></div><div class="panel-body has-chart"><Chart v-if="metricQBar.categories.length" type="bar" :categories="metricQBar.categories" :series="metricQBar.series" :horizontal="true" y-axis-name="req/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
        <div class="chart-panel"><div class="panel-head">日志查询 QPS<span class="tag">最近 {{ range }}</span></div><div class="panel-body has-chart"><Chart v-if="logQBar.categories.length" type="bar" :categories="logQBar.categories" :series="logQBar.series" :horizontal="true" y-axis-name="req/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
      </div>
      <div class="chart-row">
        <div class="chart-panel"><div class="panel-head">抓取结果分布<span class="tag">req/s</span></div><div class="panel-body has-chart"><Chart v-if="httpOutcomeChart.length" type="line" :series="httpOutcomeChart" :area="true" y-axis-name="req/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
        <div class="chart-panel"><div class="panel-head">抓取调用速率<span class="tag">calls/s</span></div><div class="panel-body has-chart"><Chart v-if="httpLatChart.length" type="line" :series="httpLatChart" y-axis-name="calls/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
      </div>
    </div>

    <div v-show="activeTab === 'collect'">
      <div class="section-title">1 · 采集流量(事件/秒) <span v-if="rangeLoading" class="tag">加载中…</span><span v-if="rangeError" class="tag" style="color: oklch(var(--er))">异常: {{ rangeError }}</span></div>
      <div class="chart-row">
        <div class="chart-panel"><div class="panel-head">指标 / 日志 / DLQ 接收速率<span class="tag">InfluxDB series · {{ range }}</span></div><div class="panel-body has-chart"><Chart v-if="trafficChart.length" type="line" :series="trafficChart" :area="true" y-axis-name="evt/s" /><EmptyState v-else inline>{{ rangeError ? '查询异常' : '暂无数据' }}</EmptyState></div></div>
        <div class="chart-panel"><div class="panel-head">日志去重 & 告警候选<span class="tag">drop/s vs alert/s</span></div><div class="panel-body has-chart"><Chart v-if="dedupChart.length" type="line" :series="dedupChart" :area="true" y-axis-name="evt/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
      </div>

      <div class="section-title">2 · 采集失败(事件/秒)</div>
      <div class="chart-row full">
        <div class="chart-panel"><div class="panel-head">入库失败分类<span class="tag">堆积说明写入侧健康度</span></div><div class="panel-body has-chart"><Chart v-if="failChart.length" type="line" :series="failChart" :area="true" y-axis-name="fail/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
      </div>

      <div class="section-title">3 · 写入与去重耗时</div>
      <div class="chart-row">
        <div class="chart-panel"><div class="panel-head">指标 / 日志 写入调用速率<span class="tag">calls/s</span></div><div class="panel-body has-chart"><Chart v-if="writeChart.length" type="line" :series="writeChart" y-axis-name="calls/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
        <div class="chart-panel"><div class="panel-head">日志去重 keep/drop/flush 速率<span class="tag">op/s</span></div><div class="panel-body has-chart"><Chart v-if="dedupOpChart.length" type="line" :series="dedupOpChart" :area="true" y-axis-name="op/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
      </div>
    </div>

    <div v-show="activeTab === 'jvm'">
      <div class="section-title">6 · JVM 运行时</div>
      <div class="chart-row">
        <div class="chart-panel"><div class="panel-head">内存分布<span class="tag">MB</span></div><div class="panel-body has-chart"><Chart v-if="jvmMemChart.length" type="line" :series="jvmMemChart" :area="true" y-axis-name="MB" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
        <div class="chart-panel"><div class="panel-head">堆各区已用<span class="tag">按 pool_name 分线,MB</span></div><div class="panel-body has-chart"><Chart v-if="jvmPoolChart.length" type="line" :series="jvmPoolChart" y-axis-name="MB" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
        <div class="chart-panel"><div class="panel-head">线程数与类加载<span class="tag">个</span></div><div class="panel-body has-chart"><Chart v-if="jvmThreadsChart.length" type="line" :series="jvmThreadsChart" y-axis-name="个" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
      </div>
      <div class="chart-row full">
        <div class="chart-panel"><div class="panel-head">GC 暂停时间(各收集器,ms/采样周期)<span class="tag">越大说明 GC 压力越重</span></div><div class="panel-body has-chart"><Chart v-if="jvmGcChart.length" type="line" :series="jvmGcChart" :area="true" y-axis-name="ms/周期" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
      </div>
    </div>

    <div v-show="activeTab === 'process'">
      <div class="section-title">7 · 进程与主机</div>
      <div class="chart-row cols-3">
        <div class="chart-panel"><div class="panel-head">CPU 占用<span class="tag">0~100%</span></div><div class="panel-body has-chart"><Chart v-if="procCpuChart.length" type="line" :series="procCpuChart" y-axis-name="%" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
        <div class="chart-panel"><div class="panel-head">进程内存明细<span class="tag">RSS / JVM 堆 / JVM 非堆 / 虚拟内存,MB</span></div><div class="panel-body has-chart"><Chart v-if="procRssChart.length" type="line" :series="procRssChart" y-axis-name="MB" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
        <div class="chart-panel"><div class="panel-head">系统资源<span class="tag">MB / 核</span></div><div class="panel-body" style="padding: 12px 14px;"><div class="kv-grid">
          <div class="kv"><span class="k">系统 CPU 负载</span><span class="v">{{ kv.sysCpu }}</span></div>
          <div class="kv"><span class="k">系统总内存</span><span class="v">{{ kv.sysTotal }}</span></div>
          <div class="kv"><span class="k">系统可用内存</span><span class="v">{{ kv.sysFree }}</span></div>
          <div class="kv"><span class="k">磁盘可用</span><span class="v">{{ kv.disk }}</span></div>
          <div class="kv"><span class="k">CPU 核数</span><span class="v">{{ kv.cores }}</span></div>
          <div class="kv"><span class="k">JVM 启动时长</span><span class="v">{{ kv.uptime }}</span></div>
        </div></div></div>
      </div>
    </div>

    <div v-show="activeTab === 'meters'">
      <div class="section-title">8 · 原始 Micrometer 指标(只读)</div>
      <div class="card bg-base-100 border border-base-300 shadow-sm">
        <div class="card-body p-0">
          <div class="px-4 py-2.5 border-b border-base-300 flex items-center font-medium text-sm">
            <span>所有 spring.watch.* 指标</span>
            <span class="ml-auto text-xs text-muted font-normal">{{ meterCount }} 条</span>
          </div>
          <div class="overflow-auto" style="max-height: 420px;">
            <table class="table table-pin-rows table-sm table-zebra">
              <thead>
                <tr class="text-secondary">
                  <th>指标名词</th>
                  <th>指标</th>
                  <th>类型</th>
                  <th class="text-right">值</th>
                  <th class="text-right">count</th>
                  <th class="text-right">total ms</th>
                  <th class="text-right">max ms</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="meterRows.length === 0">
                  <td colspan="7" class="text-center text-muted">{{ realtime ? '暂无数据' : '加载中…' }}</td>
                </tr>
                <template v-else>
                  <tr v-for="(r, i) in meterFlat" :key="i">
                    <template v-if="r.kind === 'group'">
                      <td colspan="7" class="bg-base-200 font-medium text-secondary">{{ r.group }}</td>
                    </template>
                    <template v-else>
                      <td>{{ labelOf(r.name) }}</td>
                      <td>{{ r.name }}</td>
                      <td><span class="badge badge-sm badge-info">{{ r.type }}</span></td>
                      <td class="text-right font-mono">{{ r.val == null ? '-' : fmtNum(r.val) }}</td>
                      <td class="text-right font-mono">{{ r.count == null ? '-' : fmtNum(r.count) }}</td>
                      <td class="text-right font-mono">{{ r.total == null ? '-' : r.total.toFixed(1) }}</td>
                      <td class="text-right font-mono">{{ r.max == null ? '-' : r.max.toFixed(2) }}</td>
                    </template>
                  </tr>
                </template>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>

    <div v-show="activeTab === 'influxdb'">
      <div class="section-title">InfluxDB 自身 · {{ kv.meta }}</div>
      <InfluxDbPane />
    </div>

    <div v-show="activeTab === 'kafka'">
      <div class="section-title">Kafka 集群 · {{ kv.meta }}</div>
      <KafkaPane />
    </div>
  </div>
</template>
