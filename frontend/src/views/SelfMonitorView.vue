<script setup lang="ts">
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { api } from '@/api/client'
import { formatBytes, formatPercent, formatNumber } from '@/utils/format'
import Chart from '@/charts/Chart.vue'
import EmptyState from '@/components/EmptyState.vue'
import MetricCard from '@/components/MetricCard.vue'
import type { LineSeriesItem, BarSeriesItem } from '@/charts/types'

const realtime = ref<any>(null)
const timeseries = ref<any[]>([])
const appCount = ref<number | null>(null)

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
  retrySub: '-'
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

function fmtUptime(sec: number | null | undefined) {
  if (sec == null) return '-'
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

function rateAt(idx: number, key: string): number {
  if (idx <= 0) return 0
  const cur = timeseries.value[idx].meters.counters[key] || 0
  const prev = timeseries.value[idx - 1].meters.counters[key] || 0
  const dt = (timeseries.value[idx].ts - timeseries.value[idx - 1].ts) / 1000
  return dt > 0 ? (cur - prev) / dt : 0
}

function meanLatencyAt(idx: number, key: string): number | null {
  if (idx <= 0) return null
  const cur = (timeseries.value[idx].meters.timers || {})[key]
  const prev = (timeseries.value[idx - 1].meters.timers || {})[key]
  if (!cur || !prev) return null
  const dc = cur.count - prev.count
  const dt = cur.totalMs - prev.totalMs
  return dc > 0 ? dt / dc : null
}

function gaugeVal(name: string) {
  if (!realtime.value || !realtime.value.meters) return null
  return (realtime.value.meters.gauges || {})[name]
}

function groupOf(name: string) {
  if (name.startsWith('spring.watch.consumer.metric')) return '指标采集'
  if (name.startsWith('spring.watch.consumer.log')) return '日志采集'
  if (name.startsWith('spring.watch.consumer.dlq')) return 'DLQ'
  if (name.startsWith('spring.watch.metric.query')) return '指标查询'
  if (name.startsWith('spring.watch.log.query')) return '日志查询'
  if (name.startsWith('spring.watch.aggregator.log')) return '日志聚合'
  if (name.startsWith('spring.watch.ingest.log.dedup')) return '日志去重'
  if (name.startsWith('spring.watch.collector.http')) return 'HTTP 抓取'
  if (name.startsWith('spring.watch.collector.retry')) return '重投队列'
  if (name.startsWith('spring.watch.collector.kafka')) return 'Kafka 兜底'
  if (name.startsWith('spring.watch.collector.host')) return '主机限流'
  return '其他'
}
function groupOrder() {
  return ['指标采集', '日志采集', 'DLQ', '指标查询', '日志查询', '日志聚合', '日志去重', 'HTTP 抓取', '重投队列', 'Kafka 兜底', '主机限流', '其他']
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
  cardUptime.value = fmtUptime(jvm.uptimeSec)
  kv.uptimeSub = '采样 ' + (realtime.value.iso || '-')
  cardActive.value = gaugeVal('spring.watch.collector.http.active')
  cardRetry.value = gaugeVal('spring.watch.collector.retry.queue.size')
  kv.retrySub = '已注册主机 ' + fmtNum(gaugeVal('spring.watch.collector.host_throttler.active'))
  cardKafka.value = gaugeVal('spring.watch.collector.kafka.fallback.size')
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
  kv.uptime = fmtUptime(jvm.uptimeSec)

  if (heapPct > 0.9 || cpu > 0.9) healthPill.value = { cls: 'bad', text: '高负载' }
  else if (heapPct > 0.75 || cpu > 0.75) healthPill.value = { cls: 'warn', text: '负载偏高' }
  else healthPill.value = { cls: 'ok', text: '正常' }
  kv.meta = '采样间隔 10s · 环形缓冲 1h · ' + (realtime.value.iso || '')
}

function renderTraffic() {
  trafficChart.value = [
    { name: '指标 received/s', points: timeseries.value.map((s, i) => [s.ts, rateAt(i, 'spring.watch.consumer.metric.received')] as [string, number | null]), area: true },
    { name: '日志 received/s', points: timeseries.value.map((s, i) => [s.ts, rateAt(i, 'spring.watch.consumer.log.received')] as [string, number | null]) },
    { name: '日志 kept/s', points: timeseries.value.map((s, i) => [s.ts, rateAt(i, 'spring.watch.consumer.log.kept')] as [string, number | null]) },
    { name: 'DLQ persisted/s', points: timeseries.value.map((s, i) => [s.ts, rateAt(i, 'spring.watch.consumer.dlq.persisted')] as [string, number | null]) }
  ]
}
function renderDedup() {
  dedupChart.value = [
    { name: '日志 deduped/s', points: timeseries.value.map((s, i) => [s.ts, rateAt(i, 'spring.watch.consumer.log.deduped')] as [string, number | null]), area: true },
    { name: '告警候选/s', points: timeseries.value.map((s, i) => [s.ts, rateAt(i, 'spring.watch.consumer.log.alert_candidate')] as [string, number | null]) }
  ]
}
function renderFail() {
  failChart.value = [
    { name: '指标 parse_fail', points: timeseries.value.map((s, i) => [s.ts, rateAt(i, 'spring.watch.consumer.metric.parse_fail')] as [string, number | null]), area: true },
    { name: '指标 write_fail', points: timeseries.value.map((s, i) => [s.ts, rateAt(i, 'spring.watch.consumer.metric.write_fail')] as [string, number | null]) },
    { name: '日志 parse_fail', points: timeseries.value.map((s, i) => [s.ts, rateAt(i, 'spring.watch.consumer.log.parse_fail')] as [string, number | null]) },
    { name: '日志 write_fail', points: timeseries.value.map((s, i) => [s.ts, rateAt(i, 'spring.watch.consumer.log.write_fail')] as [string, number | null]) },
    { name: 'DLQ persist_fail', points: timeseries.value.map((s, i) => [s.ts, rateAt(i, 'spring.watch.consumer.dlq.persist_fail')] as [string, number | null]) }
  ]
}
function renderWrite() {
  writeChart.value = [
    { name: '指标写入均延迟 ms', points: timeseries.value.map((s, i) => [s.ts, meanLatencyAt(i, 'spring.watch.consumer.metric.write') || 0] as [string, number | null]) },
    { name: '日志写入均延迟 ms', points: timeseries.value.map((s, i) => [s.ts, meanLatencyAt(i, 'spring.watch.consumer.log.write') || 0] as [string, number | null]) }
  ]
}
function renderDedupOp() {
  dedupOpChart.value = [
    { name: 'keep/s', points: timeseries.value.map((s, i) => [s.ts, rateAt(i, 'spring.watch.ingest.log.dedup.keep')] as [string, number | null]), area: true },
    { name: 'drop/s', points: timeseries.value.map((s, i) => [s.ts, rateAt(i, 'spring.watch.ingest.log.dedup.drop')] as [string, number | null]) },
    { name: 'flush/s', points: timeseries.value.map((s, i) => [s.ts, rateAt(i, 'spring.watch.ingest.log.dedup.flush')] as [string, number | null]) },
    { name: 'flush_fail/s', points: timeseries.value.map((s, i) => [s.ts, rateAt(i, 'spring.watch.ingest.log.dedup.flush_fail')] as [string, number | null]) }
  ]
}
function renderMetricQuery() {
  const samples = timeseries.value
  const last = samples[samples.length - 1]
  if (!last) { metricQBar.value = { categories: [], series: [] }; return }
  const allKeys = Object.keys(last.meters.counters || {}).filter((k) => k.startsWith('spring.watch.metric.query.') && k.endsWith('.fail'))
  const cats = allKeys.map((k) => k.slice('spring.watch.metric.query.'.length).replace(/\.fail$/, ''))
  const failData = allKeys.map((k) => rateAt(samples.length - 1, k))
  const emptyData = allKeys.map((k) => rateAt(samples.length - 1, k.replace('.fail', '.empty_result')))
  metricQBar.value = {
    categories: cats,
    series: [
      { name: 'fail/s', data: failData },
      { name: 'empty_result/s', data: emptyData }
    ]
  }
}
function renderLogQuery() {
  const samples = timeseries.value
  const last = samples[samples.length - 1]
  if (!last) { logQBar.value = { categories: [], series: [] }; return }
  const allKeys = Object.keys(last.meters.counters || {}).filter((k) => k.startsWith('spring.watch.log.query.') && k.endsWith('.fail'))
  const cats = allKeys.map((k) => k.slice('spring.watch.log.query.'.length).replace(/\.fail$/, ''))
  const failData = allKeys.map((k) => rateAt(samples.length - 1, k))
  logQBar.value = { categories: cats, series: [{ name: 'fail/s', data: failData }] }
}
function renderHttpOutcome() {
  httpOutcomeChart.value = [
    { name: 'success/s', points: timeseries.value.map((s, i) => [s.ts, rateAt(i, 'spring.watch.collector.http.success')] as [string, number | null]), area: true },
    { name: 'failure/s', points: timeseries.value.map((s, i) => [s.ts, rateAt(i, 'spring.watch.collector.http.failure')] as [string, number | null]) },
    { name: 'timeout/s', points: timeseries.value.map((s, i) => [s.ts, rateAt(i, 'spring.watch.collector.http.timeout')] as [string, number | null]) },
    { name: 'non2xx/s', points: timeseries.value.map((s, i) => [s.ts, rateAt(i, 'spring.watch.collector.http.non2xx')] as [string, number | null]) }
  ]
}
function renderHttpLat() {
  httpLatChart.value = [
    { name: '均延迟 ms', points: timeseries.value.map((s, i) => [s.ts, meanLatencyAt(i, 'spring.watch.collector.http.request') || 0] as [string, number | null]) },
    { name: '重投 enqueue/s', points: timeseries.value.map((s, i) => [s.ts, rateAt(i, 'spring.watch.collector.retry.enqueued')] as [string, number | null]) },
    { name: '重投 dropped/s', points: timeseries.value.map((s, i) => [s.ts, rateAt(i, 'spring.watch.collector.retry.dropped')] as [string, number | null]) }
  ]
}
function renderJvmMem() {
  jvmMemChart.value = [
    { name: '堆已用 MB', points: timeseries.value.map((s) => [s.ts, (s.jvm.heap.used || 0) / 1048576] as [string, number | null]), area: true },
    { name: 'Metaspace MB', points: timeseries.value.map((s) => [s.ts, (s.jvm.metaspace.used || 0) / 1048576] as [string, number | null]) },
    { name: '非堆 MB', points: timeseries.value.map((s) => [s.ts, (s.jvm.nonHeap.used || 0) / 1048576] as [string, number | null]) }
  ]
}
function renderJvmThreads() {
  jvmThreadsChart.value = [
    { name: '线程数', points: timeseries.value.map((s) => [s.ts, s.jvm.threads.current || 0] as [string, number | null]) },
    { name: '守护线程', points: timeseries.value.map((s) => [s.ts, s.jvm.threads.daemon || 0] as [string, number | null]) },
    { name: '已加载类', points: timeseries.value.map((s) => [s.ts, s.jvm.classes.loaded || 0] as [string, number | null]) }
  ]
}
function renderJvmGc() {
  const keys = new Set<string>()
  timeseries.value.forEach((s) => (s.jvm.gc || []).forEach((g: any) => keys.add(g.name)))
  const series: LineSeriesItem[] = []
  for (const name of keys) {
    const points: [string, number | null][] = []
    for (let i = 0; i < timeseries.value.length; i++) {
      const cur = (timeseries.value[i].jvm.gc || []).find((g: any) => g.name === name)
      const prev = i > 0 ? (timeseries.value[i - 1].jvm.gc || []).find((g: any) => g.name === name) : null
      const inc = cur && prev ? (cur.timeMs - prev.timeMs) : 0
      points.push([timeseries.value[i].ts, inc])
    }
    series.push({ name, points, area: true })
  }
  jvmGcChart.value = series
}
function renderProcCpu() {
  procCpuChart.value = [
    { name: '进程 CPU', points: timeseries.value.map((s) => [s.ts, (s.process.cpuLoad || 0) * 100] as [string, number | null]) },
    { name: '系统 CPU', points: timeseries.value.map((s) => [s.ts, (s.process.systemCpuLoad || 0) * 100] as [string, number | null]) }
  ]
}
function renderProcRss() {
  procRssChart.value = [
    { name: 'JVM 堆已用 MB', points: timeseries.value.map((s) => [s.ts, (s.process.rssBytes || 0) / 1048576] as [string, number | null]) },
    { name: '进程虚拟内存 MB', points: timeseries.value.map((s) => [s.ts, (s.process.virtualBytes || 0) / 1048576] as [string, number | null]) }
  ]
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

function renderAll() {
  renderCards()
  if (timeseries.value.length < 2) return
  renderTraffic()
  renderDedup()
  renderFail()
  renderWrite()
  renderDedupOp()
  renderMetricQuery()
  renderLogQuery()
  renderHttpOutcome()
  renderHttpLat()
  renderJvmMem()
  renderJvmThreads()
  renderJvmGc()
  renderProcCpu()
  renderProcRss()
  renderMeterTable()
}

async function pollRealtime() {
  try {
    const resp = await api.get<any>('/api/self/realtime')
    realtime.value = resp?.sample || null
    if (appCount.value == null) {
      try {
        const apps = await api.get<any[]>('/api/apps/active')
        appCount.value = (apps || []).length
      } catch {
        appCount.value = 0
      }
    }
    renderCards()
  } catch {
    /* 静默 */
  }
}

async function pollTimeseries() {
  try {
    const resp = await api.get<any>('/api/self/timeseries', { window: 60 })
    timeseries.value = resp?.samples || []
    renderAll()
  } catch {
    /* 静默 */
  }
}

let timer1: number | null = null
let timer2: number | null = null

onMounted(() => {
  pollRealtime()
  pollTimeseries()
  timer1 = window.setInterval(pollRealtime, 5000)
  timer2 = window.setInterval(pollTimeseries, 10000)
})
onBeforeUnmount(() => {
  if (timer1) clearInterval(timer1)
  if (timer2) clearInterval(timer2)
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2>自身监控</h2>
      <span class="meta">{{ kv.meta }}</span>
      <span class="spacer" />
      <span :class="['status-pill', healthPill.cls]">{{ healthPill.text }}</span>
    </div>

    <div class="metric-cards">
      <div class="metric-card"><div class="title">在线应用</div><div><span class="value">{{ cardApps ?? '-' }}</span><span class="unit">个</span></div><div class="sub">{{ kv.appsSub }}</div></div>
      <div class="metric-card"><div class="title">JVM 堆使用率</div><div><span :class="['value', cardHeapCls]">{{ cardHeap != null ? formatPercent(cardHeap, 1) : '-' }}</span></div><div class="sub">{{ kv.heapSub }}</div></div>
      <div class="metric-card"><div class="title">进程 CPU</div><div><span :class="['value', cardCpuCls]">{{ cardCpu != null ? formatPercent(cardCpu, 1) : '-' }}</span></div><div class="sub">{{ kv.cpuSub }}</div></div>
      <div class="metric-card"><div class="title">启动时长</div><div><span class="value">{{ cardUptime }}</span></div><div class="sub">{{ kv.uptimeSub }}</div></div>
      <div class="metric-card"><div class="title">活跃 HTTP 抓取</div><div><span class="value">{{ cardActive != null ? fmtNum(cardActive) : '-' }}</span><span class="unit">请求</span></div><div class="sub">实时并发数</div></div>
      <div class="metric-card"><div class="title">重试队列</div><div><span class="value">{{ cardRetry != null ? fmtNum(cardRetry) : '-' }}</span><span class="unit">条</span></div><div class="sub">{{ kv.retrySub }}</div></div>
      <div class="metric-card"><div class="title">Kafka 兜底队列</div><div><span class="value">{{ cardKafka != null ? fmtNum(cardKafka) : '-' }}</span><span class="unit">条</span></div><div class="sub">Kafka 发送失败时本地堆积</div></div>
      <div class="metric-card"><div class="title">线程数</div><div><span class="value">{{ cardThreads != null ? fmtNum(cardThreads) : '-' }}</span></div><div class="sub">{{ kv.threadsSub }}</div></div>
    </div>

    <div class="section-title">1 · 采集流量(事件/秒)</div>
    <div class="chart-row">
      <div class="chart-panel"><div class="panel-head">指标 / 日志 / DLQ 接收速率<span class="tag">{{ timeseries.length }} 个采样点</span></div><div class="panel-body has-chart"><Chart v-if="trafficChart.length" type="line" :series="trafficChart" :area="true" y-axis-name="evt/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
      <div class="chart-panel"><div class="panel-head">日志去重 & 告警候选<span class="tag">drop/s vs alert/s</span></div><div class="panel-body has-chart"><Chart v-if="dedupChart.length" type="line" :series="dedupChart" :area="true" y-axis-name="evt/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
    </div>

    <div class="section-title">2 · 采集失败(事件/秒)</div>
    <div class="chart-row full">
      <div class="chart-panel"><div class="panel-head">入库失败分类<span class="tag">堆积说明写入侧健康度</span></div><div class="panel-body has-chart"><Chart v-if="failChart.length" type="line" :series="failChart" :area="true" y-axis-name="fail/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
    </div>

    <div class="section-title">3 · 写入与去重耗时</div>
    <div class="chart-row">
      <div class="chart-panel"><div class="panel-head">指标 / 日志 写入平均延迟<span class="tag">ms</span></div><div class="panel-body has-chart"><Chart v-if="writeChart.length" type="line" :series="writeChart" y-axis-name="ms" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
      <div class="chart-panel"><div class="panel-head">日志去重 keep/drop/flush 速率<span class="tag">op/s</span></div><div class="panel-body has-chart"><Chart v-if="dedupOpChart.length" type="line" :series="dedupOpChart" :area="true" y-axis-name="op/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
    </div>

    <div class="section-title">4 · 查询服务(请求/秒)</div>
    <div class="chart-row">
      <div class="chart-panel"><div class="panel-head">指标查询 QPS<span class="tag">最近 10 分钟</span></div><div class="panel-body has-chart"><Chart v-if="metricQBar.categories.length" type="bar" :categories="metricQBar.categories" :series="metricQBar.series" :horizontal="true" y-axis-name="req/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
      <div class="chart-panel"><div class="panel-head">日志查询 QPS<span class="tag">最近 10 分钟</span></div><div class="panel-body has-chart"><Chart v-if="logQBar.categories.length" type="bar" :categories="logQBar.categories" :series="logQBar.series" :horizontal="true" y-axis-name="req/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
    </div>

    <div class="section-title">5 · 主动抓取客户端</div>
    <div class="chart-row">
      <div class="chart-panel"><div class="panel-head">抓取结果分布<span class="tag">req/s</span></div><div class="panel-body has-chart"><Chart v-if="httpOutcomeChart.length" type="line" :series="httpOutcomeChart" :area="true" y-axis-name="req/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
      <div class="chart-panel"><div class="panel-head">抓取平均延迟<span class="tag">ms</span></div><div class="panel-body has-chart"><Chart v-if="httpLatChart.length" type="line" :series="httpLatChart" y-axis-name="ms / op·s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
    </div>

    <div class="section-title">6 · JVM 运行时</div>
    <div class="chart-row">
      <div class="chart-panel"><div class="panel-head">内存分布<span class="tag">MB</span></div><div class="panel-body has-chart"><Chart v-if="jvmMemChart.length" type="line" :series="jvmMemChart" :area="true" y-axis-name="MB" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
      <div class="chart-panel"><div class="panel-head">线程数与类加载<span class="tag">个</span></div><div class="panel-body has-chart"><Chart v-if="jvmThreadsChart.length" type="line" :series="jvmThreadsChart" y-axis-name="个" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
    </div>
    <div class="chart-row full">
      <div class="chart-panel"><div class="panel-head">GC 暂停时间(各收集器,ms/采样周期)<span class="tag">越大说明 GC 压力越重</span></div><div class="panel-body has-chart"><Chart v-if="jvmGcChart.length" type="line" :series="jvmGcChart" :area="true" y-axis-name="ms/周期" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
    </div>

    <div class="section-title">7 · 进程与主机</div>
    <div class="chart-row cols-3">
      <div class="chart-panel"><div class="panel-head">CPU 占用<span class="tag">0~100%</span></div><div class="panel-body has-chart"><Chart v-if="procCpuChart.length" type="line" :series="procCpuChart" y-axis-name="%" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
      <div class="chart-panel"><div class="panel-head">进程 RSS<span class="tag">MB</span></div><div class="panel-body has-chart"><Chart v-if="procRssChart.length" type="line" :series="procRssChart" y-axis-name="MB" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
      <div class="chart-panel"><div class="panel-head">系统资源<span class="tag">MB / 核</span></div><div class="panel-body" style="padding: 12px 14px;"><div class="kv-grid">
        <div class="kv"><span class="k">系统 CPU 负载</span><span class="v">{{ kv.sysCpu }}</span></div>
        <div class="kv"><span class="k">系统总内存</span><span class="v">{{ kv.sysTotal }}</span></div>
        <div class="kv"><span class="k">系统可用内存</span><span class="v">{{ kv.sysFree }}</span></div>
        <div class="kv"><span class="k">磁盘可用</span><span class="v">{{ kv.disk }}</span></div>
        <div class="kv"><span class="k">CPU 核数</span><span class="v">{{ kv.cores }}</span></div>
        <div class="kv"><span class="k">JVM 启动时长</span><span class="v">{{ kv.uptime }}</span></div>
      </div></div></div>
    </div>

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
                <td colspan="6" class="text-center text-muted">{{ realtime ? '暂无数据' : '加载中…' }}</td>
              </tr>
              <template v-else>
                <tr v-for="(r, i) in meterFlat" :key="i">
                  <template v-if="r.kind === 'group'">
                    <td colspan="6" class="bg-base-200 font-medium text-secondary">{{ r.group }}</td>
                  </template>
                  <template v-else>
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
</template>
