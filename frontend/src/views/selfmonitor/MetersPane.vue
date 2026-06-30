<script setup lang="ts">
import { computed } from 'vue'
import { useSelfMonitor } from '@/composables/useSelfMonitor'
import { labelOf } from '@/utils/metricLabels'

const { realtime } = useSelfMonitor()

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
const groupOrder = () => [
  '指标采集', '日志采集', 'DLQ', '指标查询', '日志查询', '日志聚合', '日志去重', '日志指纹',
  'HTTP 抓取', '重投队列', 'Kafka 兜底', '主机限流', '告警评估', '告警历史', '其他'
]

const rows = computed<any[]>(() => {
  if (!realtime.value || !realtime.value.meters) return []
  const counters = realtime.value.meters.counters || {}
  const timers = realtime.value.meters.timers || {}
  const gauges = realtime.value.meters.gauges || {}
  const out: any[] = []
  for (const k of Object.keys(counters).sort()) out.push({ name: k, type: 'Counter', val: counters[k], count: null, total: null, max: null, group: groupOf(k) })
  for (const k of Object.keys(timers).sort()) {
    const t = timers[k]
    out.push({ name: k, type: 'Timer', val: null, count: t.count, total: t.totalMs, max: t.maxMs, group: groupOf(k) })
  }
  for (const k of Object.keys(gauges).sort()) out.push({ name: k, type: 'Gauge', val: gauges[k], count: null, total: null, max: null, group: groupOf(k) })
  const order = groupOrder()
  out.sort((a, b) => {
    const ga = order.indexOf(a.group), gb = order.indexOf(b.group)
    if (ga !== gb) return ga - gb
    return a.name.localeCompare(b.name)
  })
  return out
})

const meterFlat = computed(() => {
  const result: any[] = []
  let lastGroup: string | null = null
  for (const r of rows.value) {
    if (r.group !== lastGroup) {
      result.push({ kind: 'group', group: r.group })
      lastGroup = r.group
    }
    result.push({ kind: 'row', ...r })
  }
  return result
})

function fmtNum(n: number | null | undefined) {
  if (n == null || isNaN(n)) return '-'
  return n.toFixed(0).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}
</script>

<template>
  <div>
    <div class="section-title">原始 Micrometer 指标(只读)</div>
    <div class="card bg-base-100 border border-base-300 shadow-sm">
      <div class="card-body p-0">
        <div class="px-4 py-2.5 border-b border-base-300 flex items-center font-medium text-sm">
          <span>所有 spring.watch.* 指标</span>
          <span class="ml-auto text-xs text-muted font-normal">{{ rows.length }} 条</span>
        </div>
        <div class="overflow-auto">
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
              <tr v-if="rows.length === 0">
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
</template>
