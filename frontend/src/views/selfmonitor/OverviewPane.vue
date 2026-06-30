<script setup lang="ts">
import { computed, inject, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useSelfMonitor } from '@/composables/useSelfMonitor'
import { formatBytes, formatPercent, formatNumber } from '@/utils/format'
import Chart from '@/charts/Chart.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { LineSeriesItem, BarSeriesItem } from '@/charts/types'

const refreshKey = inject<import('vue').Ref<number>>('selfMonitorRefreshKey', ref(0))

const {
  range, pollSec, realtime, appCount,
  fetchSeries, buildQpsBar, meanRate, pack
} = useSelfMonitor()

const loading = ref(false)
const lastError = ref<string | null>(null)

const metricQBar = ref<{ categories: string[]; series: BarSeriesItem[] }>({ categories: [], series: [] })
const logQBar = ref<{ categories: string[]; series: BarSeriesItem[] }>({ categories: [], series: [] })
const httpOutcomeChart = ref<LineSeriesItem[]>([])
const httpLatChart = ref<LineSeriesItem[]>([])

function gaugeVal(name: string) {
  if (!realtime.value || !realtime.value.meters) return null
  return (realtime.value.meters.gauges || {})[name]
}
function counterVal(name: string) {
  if (!realtime.value || !realtime.value.meters) return null
  return (realtime.value.meters.counters || {})[name]
}

const cardHeap = computed(() => {
  if (!realtime.value) return null
  const heap = realtime.value.jvm?.heap || {}
  const used = heap.used || 0
  const max = heap.max || -1
  return max > 0 ? used / max : 0
})
const cardHeapCls = computed(() => {
  const v = cardHeap.value
  if (v == null) return ''
  return v > 0.85 ? 'danger' : v > 0.7 ? 'warn' : 'success'
})
const heapSub = computed(() => {
  if (!realtime.value) return '-'
  const heap = realtime.value.jvm?.heap || {}
  return formatBytes(heap.used || 0) + ' / ' + (heap.max > 0 ? formatBytes(heap.max) : '-')
})
const cardCpu = computed(() => realtime.value?.process?.cpuLoad ?? null)
const cardCpuCls = computed(() => {
  const v = cardCpu.value
  if (v == null) return ''
  return v > 0.85 ? 'danger' : v > 0.7 ? 'warn' : 'success'
})
const cpuSub = computed(() => '系统 ' + formatPercent(realtime.value?.process?.systemCpuLoad || 0, 1))
const cardUptime = computed(() => {
  const ms = realtime.value?.jvm?.uptimeMs
  if (ms == null) return '-'
  const sec = Math.floor(ms / 1000)
  const d = Math.floor(sec / 86400)
  const h = Math.floor((sec % 86400) / 3600)
  const m = Math.floor((sec % 3600) / 60)
  if (d > 0) return d + 'd ' + h + 'h'
  if (h > 0) return h + 'h ' + m + 'm'
  return m + 'm ' + (sec % 60) + 's'
})
const uptimeSub = computed(() => '采样 ' + (realtime.value?.iso || '-'))
const cardActive = computed(() => gaugeVal('spring.watch.collector.http.active'))
const cardRetry = computed(() => gaugeVal('spring.watch.collector.retry.queue.size'))
const retrySub = computed(() => '已注册主机 ' + (cardRetry.value == null ? '-' : Number(cardRetry.value).toFixed(0).replace(/\B(?=(\d{3})+(?!\d))/g, ',')))
const cardKafka = computed(() => gaugeVal('spring.watch.collector.kafka.fallback.size'))
const cardBodyRejected = computed(() => counterVal('spring.watch.collector.http.body.rejected'))
const cardKafkaRejected = computed(() => counterVal('spring.watch.kafka.fallback.rejected'))
const cardThreads = computed(() => realtime.value?.jvm?.threads?.current ?? null)
const threadsSub = computed(() => {
  const th = realtime.value?.jvm?.threads || {}
  return '守护 ' + (th.daemon || 0) + ' / 峰值 ' + (th.peak || 0)
})
const cardApps = computed(() => appCount.value)
const appsSub = computed(() => '已注册监控应用')

const healthPill = computed(() => {
  if (!realtime.value) return { cls: 'warn', text: '未就绪' }
  const heap = cardHeap.value || 0
  const cpu = cardCpu.value || 0
  if (heap > 0.9 || cpu > 0.9) return { cls: 'bad', text: '高负载' }
  if (heap > 0.75 || cpu > 0.75) return { cls: 'warn', text: '负载偏高' }
  return { cls: 'ok', text: '正常' }
})

function fmtNum(n: number | null | undefined) {
  if (n == null || isNaN(n)) return '-'
  return n.toFixed(0).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

async function refresh() {
  loading.value = true
  lastError.value = null
  try {
    const m = (metric: string, meterType?: string) => {
      const o: any = { category: 'meter', metric, agg: 'rate' }
      if (meterType) o.meterType = meterType
      return o
    }
    const safe = async <T,>(p: Promise<T>, fallback: T): Promise<T> => {
      try { return await p } catch (e) { console.warn('[overview series fetch fail]', e); return fallback }
    }
    const [
      http_ok, http_fail, http_timeout, http_non2xx, http_body_rej,
      http_calls, retry_enq, retry_drop,
      metric_q_latest, metric_q_series, metric_q_grouped, metric_q_histogram, metric_q_fail,
      log_q_search, log_q_patterns, log_q_levels, log_q_trace, log_q_context, log_q_dedup_top, log_q_fail
    ] = await Promise.all([
      safe(fetchSeries(m('spring.watch.collector.http.success', 'counter')), []),
      safe(fetchSeries(m('spring.watch.collector.http.failure', 'counter')), []),
      safe(fetchSeries(m('spring.watch.collector.http.timeout', 'counter')), []),
      safe(fetchSeries(m('spring.watch.collector.http.non2xx', 'counter')), []),
      safe(fetchSeries(m('spring.watch.collector.http.body.rejected', 'counter')), []),
      safe(fetchSeries({ ...m('spring.watch.collector.http.request', 'timer'), field: 'count' }), []),
      safe(fetchSeries(m('spring.watch.collector.retry.enqueued', 'counter')), []),
      safe(fetchSeries(m('spring.watch.collector.retry.dropped', 'counter')), []),
      safe(fetchSeries({ ...m('spring.watch.metric.query.latest', 'timer'), field: 'count' }), []),
      safe(fetchSeries({ ...m('spring.watch.metric.query.series', 'timer'), field: 'count' }), []),
      safe(fetchSeries({ ...m('spring.watch.metric.query.grouped', 'timer'), field: 'count' }), []),
      safe(fetchSeries({ ...m('spring.watch.metric.query.histogram', 'timer'), field: 'count' }), []),
      safe(fetchSeries(m('spring.watch.metric.query.fail', 'counter')), []),
      safe(fetchSeries(m('spring.watch.log.query.search', 'counter')), []),
      safe(fetchSeries(m('spring.watch.log.query.patterns', 'counter')), []),
      safe(fetchSeries(m('spring.watch.log.query.levels', 'counter')), []),
      safe(fetchSeries(m('spring.watch.log.query.trace', 'counter')), []),
      safe(fetchSeries(m('spring.watch.log.query.context', 'counter')), []),
      safe(fetchSeries(m('spring.watch.log.query.dedup_top', 'counter')), []),
      safe(fetchSeries(m('spring.watch.log.query.fail', 'counter')), [])
    ])
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
    metricQBar.value = buildQpsBar(
      ['最新', '时序', '分组', '直方图', '失败'],
      [metric_q_latest, metric_q_series, metric_q_grouped, metric_q_histogram, metric_q_fail]
    )
    logQBar.value = buildQpsBar(
      ['搜索', '模式', '级别', '链路', '上下文', '去重 Top', '失败'],
      [log_q_search, log_q_patterns, log_q_levels, log_q_trace, log_q_context, log_q_dedup_top, log_q_fail]
    )
    // 引用一次,避免 lint 报未使用
    void http_body_rej
    void meanRate
  } catch (e: any) {
    lastError.value = e?.message || String(e)
  } finally {
    loading.value = false
  }
}

let timer: number | null = null
function startPolling() {
  if (timer) clearInterval(timer)
  refresh()
  timer = window.setInterval(refresh, pollSec.value * 1000)
}
function stopPolling() {
  if (timer) { clearInterval(timer); timer = null }
}

onMounted(() => startPolling())
onBeforeUnmount(() => stopPolling())

watch(pollSec, () => {
  if (timer) startPolling()
})
watch(range, () => {
  refresh()
})
watch(refreshKey, () => {
  refresh()
})
</script>

<template>
  <div>
    <div class="metric-cards">
      <div class="metric-card"><div class="title">在线应用</div><div><span class="value">{{ cardApps ?? '-' }}</span><span class="unit">个</span></div><div class="sub">{{ appsSub }}</div></div>
      <div class="metric-card"><div class="title">JVM 堆使用率</div><div><span :class="['value', cardHeapCls]">{{ cardHeap != null ? formatPercent(cardHeap, 1) : '-' }}</span></div><div class="sub">{{ heapSub }}</div></div>
      <div class="metric-card"><div class="title">进程 CPU</div><div><span :class="['value', cardCpuCls]">{{ cardCpu != null ? formatPercent(cardCpu, 1) : '-' }}</span></div><div class="sub">{{ cpuSub }}</div></div>
      <div class="metric-card"><div class="title">启动时长</div><div><span class="value">{{ cardUptime }}</span></div><div class="sub">{{ uptimeSub }}</div></div>
      <div class="metric-card"><div class="title">活跃 HTTP 抓取</div><div><span class="value">{{ cardActive != null ? fmtNum(cardActive) : '-' }}</span><span class="unit">请求</span></div><div class="sub">实时并发数</div></div>
      <div class="metric-card"><div class="title">重试队列</div><div><span class="value">{{ cardRetry != null ? fmtNum(cardRetry) : '-' }}</span><span class="unit">条</span></div><div class="sub">{{ retrySub }}</div></div>
      <div class="metric-card"><div class="title">Kafka 兜底队列</div><div><span class="value">{{ cardKafka != null ? fmtNum(cardKafka) : '-' }}</span><span class="unit">条</span></div><div class="sub">Kafka 发送失败时本地堆积</div></div>
      <div class="metric-card"><div class="title">Kafka 兜底被拒</div><div><span :class="['value', cardKafkaRejected && cardKafkaRejected > 0 ? 'danger' : '']">{{ cardKafkaRejected != null ? fmtNum(cardKafkaRejected) : '-' }}</span><span class="unit">次</span></div><div class="sub">队列满后丢弃的累计数</div></div>
      <div class="metric-card"><div class="title">Agent 响应体超限</div><div><span :class="['value', cardBodyRejected && cardBodyRejected > 0 ? 'danger' : '']">{{ cardBodyRejected != null ? fmtNum(cardBodyRejected) : '-' }}</span><span class="unit">次</span></div><div class="sub">&gt; 4MB 被拒收的累计数</div></div>
      <div class="metric-card"><div class="title">线程数</div><div><span class="value">{{ cardThreads != null ? fmtNum(cardThreads) : '-' }}</span></div><div class="sub">{{ threadsSub }}</div></div>
    </div>

    <div class="section-title">查询服务与主动抓取<span v-if="loading" class="tag">加载中…</span><span v-if="lastError" class="tag" style="color: oklch(var(--er))">异常: {{ lastError }}</span></div>
    <div class="chart-row">
      <div class="chart-panel"><div class="panel-head">指标查询 QPS<span class="tag">最近 {{ range }}</span></div><div class="panel-body has-chart"><Chart v-if="metricQBar.categories.length" type="bar" :categories="metricQBar.categories" :series="metricQBar.series" :horizontal="true" y-axis-name="req/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
      <div class="chart-panel"><div class="panel-head">日志查询 QPS<span class="tag">最近 {{ range }}</span></div><div class="panel-body has-chart"><Chart v-if="logQBar.categories.length" type="bar" :categories="logQBar.categories" :series="logQBar.series" :horizontal="true" y-axis-name="req/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
    </div>
    <div class="chart-row">
      <div class="chart-panel"><div class="panel-head">抓取结果分布<span class="tag">req/s</span></div><div class="panel-body has-chart"><Chart v-if="httpOutcomeChart.length" type="line" :series="httpOutcomeChart" :area="true" y-axis-name="req/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
      <div class="chart-panel"><div class="panel-head">抓取调用速率<span class="tag">calls/s</span></div><div class="panel-body has-chart"><Chart v-if="httpLatChart.length" type="line" :series="httpLatChart" y-axis-name="calls/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
    </div>
  </div>
</template>
