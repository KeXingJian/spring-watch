<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount } from 'vue'
import { useInfraComponent } from '@/composables/useInfraComponent'
import { labelOf } from '@/utils/metricLabels'
import Chart from '@/charts/Chart.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { LineSeriesItem } from '@/charts/types'

type Point = [number, number | null]

const infra = useInfraComponent('kafka')
const {
  status, metrics, seriesData, latestData,
  loading, lastError, componentError,
  startPolling, stopPolling,
  chartKey, toMbPoints, fmtValue
} = infra

const lagMetric = computed(() => metrics.value.find((m) => m === 'consumer.lag'))

const partitionsByTopic = computed(() => {
  const out: Record<string, { metric: string; tag: any; points: Point[] }[]> = {}
  if (lagMetric.value && seriesData[chartKey(lagMetric.value)]) {
    for (const s of seriesData[chartKey(lagMetric.value)] as LineSeriesItem[]) {
      const topic = s.name.includes('topic=') ? (s.name.match(/topic=([^/\s]+)/)?.[1] || 'unknown') : 'all'
      if (!out[topic]) out[topic] = []
      out[topic].push({
        metric: lagMetric.value,
        tag: { topic, partition: s.name.match(/partition=(\d+)/)?.[1] || '?' },
        points: s.points as Point[]
      })
    }
  }
  return out
})

const SUMMARY_ORDER = [
  'broker.bytes_in_rate',
  'broker.bytes_out_rate',
  'consumer.lag.topic',
  'broker.messages_in_rate',
  'broker.produce_requests_rate',
  'broker.produce_failed_rate',
  'broker.fetch_failed_rate'

]

const summary = computed(() => {
  const rank = (m: string) => {
    const i = SUMMARY_ORDER.indexOf(m)
    return i === -1 ? SUMMARY_ORDER.length : i
  }
  const out: { metric: string; data: LineSeriesItem[] }[] = []
  for (const m of [...metrics.value].sort((a, b) => rank(a) - rank(b))) {
    if (m === 'consumer.lag' || m === 'consumer.lag.total') continue
    const s = seriesData[chartKey(m)]
    if (!s || s.length === 0) continue
    if (m === 'consumer.lag.topic') {
      const total = seriesData[chartKey('consumer.lag.total')]
      const merged = [...s, ...(total || []).map((x) => ({ ...x, name: '总滞留' }))]
      out.push({ metric: m, data: merged })
    } else {
      out.push({ metric: m, data: s })
    }
  }
  return out
})

onMounted(() => { startPolling() })
onBeforeUnmount(() => { stopPolling() })
</script>

<template>
  <div>
    <div class="flex items-center mb-3 gap-3 flex-wrap">
      <span v-if="lastError" class="text-xs" style="color: oklch(var(--er))">异常: {{ lastError }}</span>
      <span v-if="status && status.lastError" class="text-xs" style="color: oklch(var(--wa))">采集告警: {{ status.lastError }}</span>
    </div>

    <div v-if="loading && metrics.length === 0">
      <EmptyState>加载中…</EmptyState>
    </div>

    <div v-else-if="metrics.length === 0">
      <div class="card bg-base-100 border border-base-300 shadow-sm">
        <div class="card-body p-4">
          <div class="text-sm text-muted">Kafka lag 指标尚未采集(等待 KafkaLagMonitor 首次轮询)</div>
          <div v-if="componentError" class="text-xs mt-2" style="color: oklch(var(--er))">{{ componentError }}</div>
        </div>
      </div>
    </div>

    <div v-else>
      <div v-if="Object.keys(partitionsByTopic).filter((t) => t !== 'monitor-heartbeat').length > 0" class="mb-4">
        <div class="section-title">Consumer Lag(按 topic 分组,每个 partition 一条线)</div>
        <div v-for="(items, topic) in partitionsByTopic" :key="topic" v-show="topic !== 'monitor-heartbeat'" class="mb-3">
          <div class="text-xs text-muted mb-1">topic = {{ topic }}  ·  {{ items.length }} 个 partition</div>
          <Chart
            v-if="items.some((it) => it.points && it.points.length > 0)"
            type="line"
            :series="items.flatMap((it) => toMbPoints([{ name: 'partition ' + it.tag.partition, points: it.points, area: false }], it.metric))"
            :height="'240px'"
          />
          <EmptyState v-else inline>暂无数据</EmptyState>
        </div>
      </div>

      <div v-if="summary.length > 0">
        <div class="section-title">汇总指标</div>
        <div class="grid grid-cols-1 lg:grid-cols-2 gap-3">
          <div v-for="it in summary" :key="it.metric" class="border border-base-300 rounded p-2">
            <div class="flex items-center justify-between mb-1">
              <div class="min-w-0">
                <div class="text-sm font-medium truncate">{{ labelOf(it.metric) }}</div>
                <div class="text-xs text-muted truncate" :title="it.metric">{{ it.metric }}</div>
              </div>
              <span class="text-sm font-mono ml-2 shrink-0">{{ fmtValue(latestData[chartKey(it.metric)]?.value ?? latestData[chartKey(it.metric)]?.v, it.metric) }}</span>
            </div>
            <Chart v-if="it.data && it.data.length > 0" type="line" :series="toMbPoints(it.data, it.metric)" :height="'200px'" />
            <EmptyState v-else inline>暂无数据</EmptyState>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
