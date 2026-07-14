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

// v2.0:InflightQueue 7 个核心指标(对应 InflightMetrics 7 个 meter 名)
const SUMMARY_METRICS: { key: string; label: string; agg: 'rate' | 'last' | 'mean'; meterType?: 'counter' | 'gauge' }[] = [
  { key: 'spring.watch.inflight.producer.sent',        label: '投递速率(producer.sent /s)',         agg: 'rate',  meterType: 'counter' },
  { key: 'spring.watch.inflight.producer.drained',     label: '消费速率(producer.drained /s)',      agg: 'rate',  meterType: 'counter' },
  { key: 'spring.watch.inflight.producer.rejected',    label: '拒绝速率(producer.rejected /s)',     agg: 'rate',  meterType: 'counter' },
  { key: 'spring.watch.inflight.queue.pending',        label: '总滞留(queue.pending)',             agg: 'last',  meterType: 'gauge'   },
  { key: 'spring.watch.inflight.queue.capacity',       label: '总容量(queue.capacity)',             agg: 'last',  meterType: 'gauge'   },
  { key: 'spring.watch.inflight.wal.append.fail',      label: 'WAL 落盘失败(/s)',                   agg: 'rate',  meterType: 'counter' },
  { key: 'spring.watch.inflight.consumer.batch.size',  label: '消费批大小(p50)',                    agg: 'mean',  meterType: 'gauge'   }
]

// per-partition 滞留 topic × partition 二维分布
const PARTITION_METRICS = [
  { key: 'spring.watch.inflight.queue.pending',  label: '滞留' },
  { key: 'spring.watch.inflight.queue.capacity', label: '容量' }
]

const perTopicPending = ref<Record<string, LineSeriesItem[]>>({})
const perTopicCapacity = ref<Record<string, LineSeriesItem[]>>({})
const summary = ref<{ metric: string; label: string; data: LineSeriesItem[] }[]>([])
const latestValues = ref<Record<string, number | null>>({})
const loading = ref(false)
const lastError = ref<string | null>(null)
let pollingTimer: number | null = null

function partitionLabel(seriesName: string, topic: string): string {
  const p = seriesName.match(/partition=(\d+)/)?.[1]
  return p != null ? `p${p}` : 'p?'
}

function nowRange() {
  return { from: new Date(Date.now() - rangeMs()).toISOString(), to: new Date().toISOString() }
}

async function fetchSeriesByMetric(metric: string, agg: string, meterType?: 'counter' | 'gauge'): Promise<LineSeriesItem[]> {
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
  try {
    const resp: any = await api.get('/api/self/series', params)
    const series = resp?.series || []
    return series.map((s: any) => ({
      name: s.name || metric,
      points: (s.points || []).map((p: any) => [new Date(p.t).getTime(), p.v == null ? null : Number(p.v)])
    }))
  } catch (e: any) {
    return []
  }
}

async function fetchLatest(metric: string, meterType?: 'counter' | 'gauge'): Promise<number | null> {
  const params: Record<string, unknown> = { category: 'meter', metric, agg: 'last' }
  if (meterType) params.meterType = meterType
  try {
    const resp: any = await api.get('/api/self/latest', params)
    if (resp?.rows && resp.rows.length > 0) {
      const v = resp.rows[0].value
      return v == null ? null : Number(v)
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
        meterType: 'gauge'
      }
      try {
        const resp: any = await api.get('/api/self/series', { ...params, topic })
        const series = (resp?.series || []).map((s: any) => ({
          name: s.name,
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
          name: s.name,
          points: (s.points || []).map((p: any) => [new Date(p.t).getTime(), p.v == null ? null : Number(p.v)])
        })) as LineSeriesItem[]
        capacityByTopic[topic] = series
      } catch {
        capacityByTopic[topic] = []
      }
    }
    perTopicPending.value = pendingByTopic
    perTopicCapacity.value = capacityByTopic

    // 2) 7 个汇总指标
    const sumResults: { metric: string; label: string; data: LineSeriesItem[] }[] = []
    const latest: Record<string, number | null> = {}
    for (const m of SUMMARY_METRICS) {
      const data = await fetchSeriesByMetric(m.key, m.agg, m.meterType)
      sumResults.push({ metric: m.key, label: m.label, data })
      latest[m.key] = await fetchLatest(m.key, m.meterType)
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
  const lastP = pendings[0].points[pendings[0].points.length - 1]?.[1] ?? 0
  const lastC = capacities[0].points[capacities[0].points.length - 1]?.[1] ?? 0
  if (!lastC) return 0
  return (lastP as number) / (lastC as number) * 100
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
          :series="(perTopicPending[topic] || []).map((s) => ({ name: partitionLabel(s.name, topic), points: s.points, area: true }))"
          :height="'180px'"
        />
        <EmptyState v-else inline>暂无滞留数据</EmptyState>
      </div>

      <!-- 7 个汇总指标 -->
      <div class="section-title">汇总指标</div>
      <div class="grid grid-cols-1 lg:grid-cols-2 gap-3">
        <div v-for="it in summary" :key="it.metric" class="border border-base-300 rounded p-2">
          <div class="flex items-center justify-between mb-1">
            <div class="min-w-0">
              <div class="text-sm font-medium truncate">{{ it.label }}</div>
              <div class="text-xs text-muted truncate" :title="it.metric">{{ it.metric }}</div>
            </div>
            <span class="text-sm font-mono ml-2 shrink-0">
              {{ formatRate(latestValues[it.metric]) }}
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
