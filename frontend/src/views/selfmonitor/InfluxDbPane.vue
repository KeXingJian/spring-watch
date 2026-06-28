<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount } from 'vue'
import { useInfraComponent } from '@/composables/useInfraComponent'
import { labelOf } from '@/utils/metricLabels'
import Chart from '@/charts/Chart.vue'
import EmptyState from '@/components/EmptyState.vue'

const infra = useInfraComponent('influxdb')
const {
  status, metrics, internalMeasurements, seriesData, latestData,
  range, rangeOptions, loading, lastError, componentError,
  refresh, startPolling, stopPolling, lastUpdateLabel,
  chartKey, toMbPoints, fmtValue
} = infra

const priority = computed(() => {
  const picked = infra.pickPriorityMetrics(metrics.value)
  return picked.filter((m) => {
    const arr = seriesData[chartKey(m)]
    return Array.isArray(arr) && arr.length > 0
  })
})

onMounted(() => { startPolling() })
onBeforeUnmount(() => { stopPolling() })
</script>

<template>
  <div>
    <div class="flex items-center mb-3 gap-3 flex-wrap">
      <div class="range-toggle" role="group" aria-label="时间范围">
        <button v-for="r in rangeOptions" :key="r"
                :class="['range-btn', { active: range === r }]"
                @click="range = r">{{ r }}</button>
      </div>
      <button class="btn btn-ghost btn-sm" :disabled="loading" @click="refresh" title="立即刷新">刷新</button>
      <span class="text-xs text-muted">最后更新 {{ lastUpdateLabel }}</span>
      <span v-if="lastError" class="text-xs" style="color: oklch(var(--er))">异常: {{ lastError }}</span>
      <span v-if="status && status.lastError" class="text-xs" style="color: oklch(var(--wa))">采集告警: {{ status.lastError }}</span>
    </div>

    <div v-if="loading && metrics.length === 0">
      <EmptyState>加载中…</EmptyState>
    </div>

    <div v-else-if="metrics.length === 0">
      <div class="card bg-base-100 border border-base-300 shadow-sm">
        <div class="card-body p-4">
          <div class="text-sm text-muted mb-2">infra_metrics 桶中无 influxdb 数据(等待 InfrastructureMetricsCollector 首次轮询)</div>
          <div v-if="internalMeasurements.length > 0" class="text-xs">
            <div class="mb-1 text-muted">_internal 桶实际有这些 measurement,可能是 collector 用了错误的名称:</div>
            <div class="flex flex-wrap gap-1">
              <span v-for="m in internalMeasurements" :key="m" class="badge badge-sm badge-outline">{{ m }}</span>
            </div>
          </div>
          <div v-else class="text-xs text-muted">_internal 桶查询失败,可能原因:token 无读权限,或 schema.measurements() 不支持</div>
          <div v-if="componentError" class="text-xs mt-2" style="color: oklch(var(--er))">{{ componentError }}</div>
        </div>
      </div>
    </div>

    <div v-else>
      <div class="section-title">关键指标</div>
      <div class="grid grid-cols-1 lg:grid-cols-2 gap-3">
        <div v-for="m in priority" :key="m" class="border border-base-300 rounded p-2">
          <div class="flex items-center justify-between mb-1">
            <div class="min-w-0">
              <div class="text-sm font-medium truncate">{{ labelOf(m) }}</div>
              <div class="text-xs text-muted truncate" :title="m">{{ m }}</div>
            </div>
            <span class="text-sm font-mono ml-2 shrink-0">{{ fmtValue(latestData[chartKey(m)]?.value ?? latestData[chartKey(m)]?.v, m) }}</span>
          </div>
          <Chart v-if="seriesData[chartKey(m)]?.length" type="line" :series="toMbPoints(seriesData[chartKey(m)] || [], m)" :height="'200px'" />
          <EmptyState v-else inline>暂无数据</EmptyState>
        </div>
      </div>
    </div>
  </div>
</template>
