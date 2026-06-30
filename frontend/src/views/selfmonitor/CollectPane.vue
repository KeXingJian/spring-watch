<script setup lang="ts">
import { inject, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useSelfMonitor } from '@/composables/useSelfMonitor'
import Chart from '@/charts/Chart.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { LineSeriesItem } from '@/charts/types'

const { range, pollSec, fetchSeries, pack } = useSelfMonitor()
const refreshKey = inject<import('vue').Ref<number>>('selfMonitorRefreshKey', ref(0))

const loading = ref(false)
const lastError = ref<string | null>(null)

const trafficChart = ref<LineSeriesItem[]>([])
const dedupChart = ref<LineSeriesItem[]>([])
const failChart = ref<LineSeriesItem[]>([])
const writeChart = ref<LineSeriesItem[]>([])
const dedupOpChart = ref<LineSeriesItem[]>([])

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
      try { return await p } catch (e) { console.warn('[collect series fetch fail]', e); return fallback }
    }
    const [
      recv_metric, recv_log, kept_log, persisted_dlq,
      deduped, alert_cand,
      parse_fail_m, write_fail_m, parse_fail_l, write_fail_l, persist_fail_dlq, http_body_rej, kafka_rej,
      write_calls_m, write_calls_l,
      keep, drop, flush, flush_fail
    ] = await Promise.all([
      safe(fetchSeries(m('spring.watch.consumer.metric.received', 'counter')), []),
      safe(fetchSeries(m('spring.watch.consumer.log.received', 'counter')), []),
      safe(fetchSeries(m('spring.watch.consumer.log.kept', 'counter')), []),
      safe(fetchSeries(m('spring.watch.consumer.dlq.persisted', 'counter')), []),
      safe(fetchSeries(m('spring.watch.consumer.log.deduped', 'counter')), []),
      safe(fetchSeries(m('spring.watch.consumer.log.alert_candidate', 'counter')), []),
      safe(fetchSeries(m('spring.watch.consumer.metric.parse_fail', 'counter')), []),
      safe(fetchSeries(m('spring.watch.consumer.metric.write_fail', 'counter')), []),
      safe(fetchSeries(m('spring.watch.consumer.log.parse_fail', 'counter')), []),
      safe(fetchSeries(m('spring.watch.consumer.log.write_fail', 'counter')), []),
      safe(fetchSeries(m('spring.watch.consumer.dlq.persist_fail', 'counter')), []),
      safe(fetchSeries(m('spring.watch.collector.http.body.rejected', 'counter')), []),
      safe(fetchSeries(m('spring.watch.kafka.fallback.rejected', 'counter')), []),
      safe(fetchSeries({ ...m('spring.watch.consumer.metric.write', 'timer'), field: 'count' }), []),
      safe(fetchSeries({ ...m('spring.watch.consumer.log.write', 'timer'), field: 'count' }), []),
      safe(fetchSeries(m('spring.watch.ingest.log.dedup.keep', 'counter')), []),
      safe(fetchSeries(m('spring.watch.ingest.log.dedup.drop', 'counter')), []),
      safe(fetchSeries(m('spring.watch.ingest.log.dedup.flush', 'counter')), []),
      safe(fetchSeries(m('spring.watch.ingest.log.dedup.flush_fail', 'counter')), [])
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

watch(pollSec, () => { if (timer) startPolling() })
watch(range, () => { refresh() })
watch(refreshKey, () => { refresh() })
</script>

<template>
  <div>
    <div class="section-title">采集流量(事件/秒) <span v-if="loading" class="tag">加载中…</span><span v-if="lastError" class="tag" style="color: oklch(var(--er))">异常: {{ lastError }}</span></div>
    <div class="chart-row">
      <div class="chart-panel"><div class="panel-head">指标 / 日志 / DLQ 接收速率<span class="tag">InfluxDB series · {{ range }}</span></div><div class="panel-body has-chart"><Chart v-if="trafficChart.length" type="line" :series="trafficChart" :area="true" y-axis-name="evt/s" /><EmptyState v-else inline>{{ lastError ? '查询异常' : '暂无数据' }}</EmptyState></div></div>
      <div class="chart-panel"><div class="panel-head">日志去重 & 告警候选<span class="tag">drop/s vs alert/s</span></div><div class="panel-body has-chart"><Chart v-if="dedupChart.length" type="line" :series="dedupChart" :area="true" y-axis-name="evt/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
    </div>

    <div class="section-title">采集失败(事件/秒)</div>
    <div class="chart-row full">
      <div class="chart-panel"><div class="panel-head">入库失败分类<span class="tag">堆积说明写入侧健康度</span></div><div class="panel-body has-chart"><Chart v-if="failChart.length" type="line" :series="failChart" :area="true" y-axis-name="fail/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
    </div>

    <div class="section-title">写入与去重耗时</div>
    <div class="chart-row">
      <div class="chart-panel"><div class="panel-head">指标 / 日志 写入调用速率<span class="tag">calls/s</span></div><div class="panel-body has-chart"><Chart v-if="writeChart.length" type="line" :series="writeChart" y-axis-name="calls/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
      <div class="chart-panel"><div class="panel-head">日志去重 keep/drop/flush 速率<span class="tag">op/s</span></div><div class="panel-body has-chart"><Chart v-if="dedupOpChart.length" type="line" :series="dedupOpChart" :area="true" y-axis-name="op/s" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
    </div>
  </div>
</template>
