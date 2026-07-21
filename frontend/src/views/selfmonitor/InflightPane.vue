<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { api } from '@/api/client'
import { useSelfMonitor } from '@/composables/useSelfMonitor'
import { labelOf } from '@/utils/metricLabels'
import Chart from '@/charts/Chart.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { LineSeriesItem } from '@/charts/types'

type Point = [number, number | null]

const TOPICS = ['monitor-metrics', 'monitor-logs', 'monitor-heartbeat'] as const

const { range, rangeStartTs, rangeEndTs, rangeMs, everyForRange } = useSelfMonitor()

// v2.0:InflightQueue 核心指标(对应 InflightMetrics meter 名);
// 消费批大小是 DistributionSummary,SelfMonitorCollector 已把 p50/p95/p99 展开成带 quantile tag 的 Point,
// 1 张卡片里同图展示 3 条曲线,右侧最新值固定显示 p50(中位批大小)
const SUMMARY_METRICS: { key: string; label: string; agg: 'rate' | 'last' | 'mean'; meterType?: 'counter' | 'gauge' | 'summary'; quantiles?: string[]; format: 'rate' | 'int' }[] = [
  { key: 'spring.watch.inflight.producer.sent',        label: '投递速率(producer.sent /s)',         agg: 'rate',  meterType: 'counter', format: 'rate' },
  { key: 'spring.watch.inflight.producer.drained',     label: '消费速率(producer.drained /s)',      agg: 'rate',  meterType: 'counter', format: 'rate' },
  { key: 'spring.watch.inflight.producer.rejected',    label: '拒绝速率(producer.rejected /s)',     agg: 'rate',  meterType: 'counter', format: 'rate' },
  { key: 'spring.watch.inflight.queue.pending',        label: '总滞留(queue.pending)',             agg: 'last',  meterType: 'gauge',   format: 'int'   },
  { key: 'spring.watch.inflight.queue.capacity',       label: '总容量(queue.capacity)',             agg: 'last',  meterType: 'gauge',   format: 'int'   },
  { key: 'spring.watch.inflight.consumer.batch.size',  label: '消费批大小(p50/p95/p99)',           agg: 'last',  meterType: 'summary', quantiles: ['0.50', '0.95', '0.99'], format: 'int' }
]

// per-partition 滞留 topic × partition 二维分布
const PARTITION_METRICS = [
  { key: 'spring.watch.inflight.queue.pending',  label: '滞留' },
  { key: 'spring.watch.inflight.queue.capacity', label: '容量' }
]

const perTopicPending = ref<Record<string, LineSeriesItem[]>>({})
const perTopicCapacity = ref<Record<string, LineSeriesItem[]>>({})
const summary = ref<{ metric: string; label: string; data: LineSeriesItem[]; key: string }[]>([])
const latestValues = ref<Record<string, number | null>>({})
const loading = ref(false)
const lastError = ref<string | null>(null)
let pollingTimer: number | null = null

// 后端 SelfMetricQueryService.parseSeries 把 series.name 拼成
// `metric{tag1=v1}{tag2=v2}...` 形式(每个 tag 一个独立 {} 块,不是逗号分隔的单个 {}),
// 这里拆出 (metric, tags) 给 seriesLabel 用
function parseTags(rawName: string): { metric: string; tags: Record<string, string> } {
  const i = rawName.indexOf('{')
  if (i < 0) return { metric: rawName, tags: {} }
  const metric = rawName.slice(0, i)
  const tags: Record<string, string> = {}
  const re = /(\w+)=([^}]+)/g
  let m: RegExpExecArray | null
  while ((m = re.exec(rawName)) !== null) {
    tags[m[1]] = m[2]
  }
  return { metric, tags }
}

// 业务 tag 短名:monitor-metrics → metrics;其他原样返回
function shortTopicOf(topic: string): string {
  return topic.startsWith('monitor-') ? topic.slice('monitor-'.length) : topic
}

// 友好 series 名:
// - per-topic 上下文(scopeTopic 已知):只显示 pN
// - 汇总上下文:显示 shortTopic/pN(否则 6 个 topic × 3 partition 名字全撞)
function seriesLabel(rawName: string, scopeTopic?: string): string {
  const { tags } = parseTags(rawName)
  const p = tags.partition
  if (scopeTopic) {
    return p != null ? `p${p}` : 'p?'
  }
  const t = tags.topic
  if (t && p != null) return `${shortTopicOf(t)}/p${p}`
  if (p != null) return `p${p}`
  return rawName
}

function nowRange() {
  return { from: new Date(Date.now() - rangeMs()).toISOString(), to: new Date().toISOString() }
}

async function fetchSeriesByMetric(metric: string, agg: string, meterType?: 'counter' | 'gauge' | 'summary', quantile?: string): Promise<LineSeriesItem[]> {
  const { from, to } = nowRange()
  const params: Record<string, unknown> = {
    category: 'meter',
    metric,
    from,
    to,
    agg,
    every: everyForRange()
  }
  if (meterType) params.meterType = meterType
  if (quantile) params.quantile = quantile
  try {
    const resp: any = await api.get('/api/self/series', params)
    const series = resp?.series || []
    return series.map((s: any) => ({
      name: seriesLabel(s.name),
      points: (s.points || []).map((p: any) => [new Date(p.t).getTime(), p.v == null ? null : Number(p.v)])
    }))
  } catch (e: any) {
    return []
  }
}

// 1 张卡片里同图展示 N 条曲线:并发拉每个 quantile 的 series,合并返回,series.name 加 p50/p95/p99 前缀便于 legend 区分
async function fetchSeriesByQuantiles(metric: string, meterType: 'summary' | 'counter' | 'gauge', quantiles: string[]): Promise<LineSeriesItem[]> {
  const lists = await Promise.all(quantiles.map(q => fetchSeriesByMetric(metric, 'last', meterType, q)))
  const out: LineSeriesItem[] = []
  for (let i = 0; i < quantiles.length; i++) {
    const q = quantiles[i]
    const tag = quantileTag(q)
    for (const s of lists[i]) {
      out.push({ name: `${tag} ${s.name}`, points: s.points })
    }
  }
  return out
}

function quantileTag(q: string): string {
  if (q === '0.50') return 'p50'
  if (q === '0.95') return 'p95'
  if (q === '0.99') return 'p99'
  return `p${Math.round(parseFloat(q) * 100)}`
}

async function fetchLatest(metric: string, meterType?: 'counter' | 'gauge' | 'summary', quantile?: string): Promise<number | null> {
  // counter 走 rate(后端 -5m 范围 derivative 取末点,单位 /s),gauge/summary 走 last(最新快照)
  const agg = meterType === 'counter' ? 'rate' : 'last'
  const params: Record<string, unknown> = { category: 'meter', metric, agg }
  if (meterType) params.meterType = meterType
  if (quantile) params.quantile = quantile
  try {
    const resp: any = await api.get('/api/self/latest', params)
    if (resp?.rows && resp.rows.length > 0) {
      // 各 partition 独立 series:
      // - counter rate: 各 partition 速率求和 = 平台总速率
      // - gauge(如 queue.pending): 各 partition 当前 depth 求和 = 平台总滞留(各 partition 独立,非重复)
      // - summary(如 batch.size p50/p95/p99): 取平均体现典型批大小
      const vals = resp.rows.map((r: any) => r.value).filter((v: any) => v != null).map(Number)
      if (!vals.length) return null
      if (meterType === 'summary') return vals.reduce((a: number, b: number) => a + b, 0) / vals.length
      return vals.reduce((a: number, b: number) => a + b, 0)
    }
    return null
  } catch {
    return null
  }
}

async function pollOnce() {
  loading.value = true
  lastError.value = null
  try {
    // 1) per-partition pending + capacity(按 topic 聚合)
    const pendingByTopic: Record<string, LineSeriesItem[]> = {}
    const capacityByTopic: Record<string, LineSeriesItem[]> = {}
    for (const topic of TOPICS) {
      const params: Record<string, unknown> = {
        category: 'meter',
        metric: 'spring.watch.inflight.queue.pending',
        from: new Date(Date.now() - rangeMs()).toISOString(),
        to: new Date().toISOString(),
        agg: 'last',
        every: everyForRange(),
        meterType: 'gauge',
        topic
      }
      try {
        const resp: any = await api.get('/api/self/series', params)
        const series = (resp?.series || []).map((s: any) => ({
          name: seriesLabel(s.name, topic),
          points: (s.points || []).map((p: any) => [new Date(p.t).getTime(), p.v == null ? null : Number(p.v)])
        })) as LineSeriesItem[]
        pendingByTopic[topic] = series
      } catch {
        pendingByTopic[topic] = []
      }

      const capParams = { ...params, metric: 'spring.watch.inflight.queue.capacity' }
      try {
        const resp: any = await api.get('/api/self/series', capParams)
        const series = (resp?.series || []).map((s: any) => ({
          name: seriesLabel(s.name, topic),
          points: (s.points || []).map((p: any) => [new Date(p.t).getTime(), p.v == null ? null : Number(p.v)])
        })) as LineSeriesItem[]
        capacityByTopic[topic] = series
      } catch {
        capacityByTopic[topic] = []
      }
    }
    perTopicPending.value = pendingByTopic
    perTopicCapacity.value = capacityByTopic

    // 2) 汇总指标(消费批大小 1 张卡片内展示 p50/p95/p99 三条曲线;latest 用 p50 体现中位批大小)
    const sumResults: { metric: string; label: string; data: LineSeriesItem[]; key: string }[] = []
    const latest: Record<string, number | null> = {}
    for (const m of SUMMARY_METRICS) {
      const data = m.quantiles
        ? await fetchSeriesByQuantiles(m.key, m.meterType || 'summary', m.quantiles)
        : await fetchSeriesByMetric(m.key, m.agg, m.meterType)
      sumResults.push({ metric: m.key, label: m.label, data, key: m.key })
      latest[m.key] = m.quantiles
        ? await fetchLatest(m.key, m.meterType, '0.50')
        : await fetchLatest(m.key, m.meterType)
    }
    summary.value = sumResults
    latestValues.value = latest
  } catch (e: any) {
    lastError.value = e?.message || String(e)
  } finally {
    loading.value = false
  }
}

function startPolling() {
  if (pollingTimer) return
  pollOnce()
  pollingTimer = window.setInterval(pollOnce, 15000)
}
function stopPolling() {
  if (pollingTimer) { clearInterval(pollingTimer); pollingTimer = null }
}

function formatRate(v: number | null | undefined): string {
  if (v == null) return '-'
  if (v < 1) return v.toFixed(3)
  if (v < 1000) return v.toFixed(1)
  return (v / 1000).toFixed(2) + 'k'
}

function formatInt(v: number | null | undefined): string {
  if (v == null) return '-'
  return Math.round(v).toLocaleString()
}

function usagePercent(topic: string): number {
  const pendings = perTopicPending.value[topic] || []
  const capacities = perTopicCapacity.value[topic] || []
  if (!pendings.length || !capacities.length) return 0
  const totalP = pendings.reduce((s, x) => s + (x.points[x.points.length - 1]?.[1] ?? 0), 0)
  const totalC = capacities.reduce((s, x) => s + (x.points[x.points.length - 1]?.[1] ?? 0), 0)
  if (!totalC) return 0
  return (totalP as number) / (totalC as number) * 100
}

onMounted(() => { startPolling() })
onBeforeUnmount(() => { stopPolling() })
watch(() => range.value, () => pollOnce())
</script>

<template>
  <div>
    <div class="flex items-center mb-3 gap-3 flex-wrap">
      <span v-if="lastError" class="text-xs" style="color: oklch(var(--er))">异常: {{ lastError }}</span>
      <span class="text-xs text-muted">
        InflightQueue 自监控 · K=4 partition/topic · 15s 轮询
      </span>
    </div>

    <!-- 概览卡片:4 个核心数字 -->
    <div class="grid grid-cols-2 lg:grid-cols-4 gap-3 mb-4">
      <div class="card bg-base-100 border border-base-300 shadow-sm">
        <div class="card-body p-3">
          <div class="text-xs text-muted">投递速率(sent/s)</div>
          <div class="text-2xl font-mono">{{ formatRate(latestValues['spring.watch.inflight.producer.sent']) }}</div>
        </div>
      </div>
      <div class="card bg-base-100 border border-base-300 shadow-sm">
        <div class="card-body p-3">
          <div class="text-xs text-muted">消费速率(drained/s)</div>
          <div class="text-2xl font-mono">{{ formatRate(latestValues['spring.watch.inflight.producer.drained']) }}</div>
        </div>
      </div>
      <div class="card bg-base-100 border border-base-300 shadow-sm">
        <div class="card-body p-3">
          <div class="text-xs text-muted">总滞留(pending)</div>
          <div class="text-2xl font-mono">{{ formatInt(latestValues['spring.watch.inflight.queue.pending']) }}</div>
        </div>
      </div>
      <div class="card bg-base-100 border border-base-300 shadow-sm">
        <div class="card-body p-3">
          <div class="text-xs text-muted">拒绝速率(rejected/s)</div>
          <div class="text-2xl font-mono" :class="latestValues['spring.watch.inflight.producer.rejected'] ? 'text-error' : ''">
            {{ formatRate(latestValues['spring.watch.inflight.producer.rejected']) }}
          </div>
        </div>
      </div>
    </div>

    <div v-if="loading && Object.keys(perTopicPending).length === 0">
      <EmptyState>加载中…</EmptyState>
    </div>

    <div v-else>
      <!-- 各分区滞留 topic × partition 分布 -->
      <div class="section-title">各分区滞留(topic × partition)</div>
      <div v-for="topic in TOPICS" :key="topic" class="mb-4">
        <div class="flex items-center mb-1 gap-3">
          <span class="text-sm font-medium">{{ topic }}</span>
          <span class="text-xs text-muted">利用率 {{ usagePercent(topic).toFixed(1) }}%</span>
        </div>
        <Chart
          v-if="(perTopicPending[topic] || []).length > 0"
          type="line"
          :series="(perTopicPending[topic] || []).map((s) => ({ name: s.name, points: s.points, area: true }))"
          :height="'180px'"
        />
        <EmptyState v-else inline>暂无滞留数据</EmptyState>
      </div>

      <!-- 汇总指标(消费批大小 1 张卡片,p50/p95/p99 三条曲线同图) -->
      <div class="section-title">汇总指标</div>
      <div class="grid grid-cols-1 lg:grid-cols-2 gap-3">
        <div v-for="it in summary" :key="it.key" class="border border-base-300 rounded p-2">
          <div class="flex items-center justify-between mb-1">
            <div class="min-w-0">
              <div class="text-sm font-medium truncate">{{ it.label }}</div>
              <div class="text-xs text-muted truncate" :title="it.metric">{{ labelOf(it.metric) }}</div>
            </div>
            <span class="text-sm font-mono ml-2 shrink-0">
              {{ it.format === 'int' ? formatInt(latestValues[it.key]) : formatRate(latestValues[it.key]) }}
            </span>
          </div>
          <Chart
            v-if="it.data && it.data.length > 0"
            type="line"
            :series="it.data"
            :height="'180px'"
          />
          <EmptyState v-else inline>暂无数据</EmptyState>
        </div>
      </div>
    </div>
  </div>
</template>
