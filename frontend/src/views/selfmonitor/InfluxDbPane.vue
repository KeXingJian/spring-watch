<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { api } from '@/api/client'
import { formatNumber, formatBytes } from '@/utils/format'
import Chart from '@/charts/Chart.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { LineSeriesItem } from '@/charts/types'

type Range = '5m' | '15m' | '1h' | '6h' | '24h'
type Point = [number, number | null]

/** InfluxDB 进程从 OS 申请的总字节(Go 视角); infra_metrics 桶里的实际字段名是 snake_case */
const TOTAL_MEMORY_METRIC = 'go_memstats.sys_bytes'
/** 平台向 InfluxDB 写入 springboot_metrics 的 Timer,count 字段速率 = 指标写入速率 */
const METRIC_WRITE_METER = 'spring.watch.consumer.metric.write'
/** 平台向 InfluxDB 写入 app_log 的 Timer,count 字段速率 = 日志写入速率 */
const LOG_WRITE_METER = 'spring.watch.consumer.log.write'
const COMPONENT = 'influxdb'

const status = ref<any>(null)
const range = ref<Range>('15m')
const rangeOptions: Range[] = ['5m', '15m', '1h', '6h', '24h']
const loading = ref(false)
const lastError = ref<string | null>(null)

const memorySeries = ref<LineSeriesItem[]>([])
const memoryLatest = ref<number | null>(null)
const writeRateSeries = ref<LineSeriesItem[]>([])
const selfError = ref<string | null>(null)

function rangeMs() {
  if (range.value === '5m') return 5 * 60 * 1000
  if (range.value === '15m') return 15 * 60 * 1000
  if (range.value === '6h') return 6 * 3600 * 1000
  if (range.value === '24h') return 24 * 3600 * 1000
  return 3600 * 1000
}
function everyForRange() {
  if (range.value === '6h') return '30s'
  if (range.value === '24h') return '1m'
  return '10s'
}

async function fetchInfraStatus() {
  try {
    status.value = await api.get('/api/infra/status')
  } catch (e: any) {
    lastError.value = e?.message || String(e)
  }
}

/** 字节 → MB 转换在拉取时就完成,前端图表直接显示 MB,避免重复换算。 */
async function fetchMemorySeries() {
  try {
    const resp: any = await api.get('/api/infra/series', {
      component: COMPONENT,
      metric: TOTAL_MEMORY_METRIC,
      from: new Date(Date.now() - rangeMs()).toISOString(),
      to: new Date().toISOString(),
      every: everyForRange()
    })
    const arr = resp?.series || []
    memorySeries.value = arr.map((s: any) => ({
      name: 'InfluxDB 进程总内存',
      points: (s.points || []).map((p: any) => [
        new Date(p.t).getTime(),
        p.v == null ? null : Number(p.v) / 1048576
      ] as Point),
      area: true
    }))
  } catch {
    memorySeries.value = []
  }
}

async function fetchMemoryLatest() {
  try {
    const resp: any = await api.get('/api/infra/latest', {
      component: COMPONENT,
      metric: TOTAL_MEMORY_METRIC
    })
    const v = resp?.value ?? resp?.v
    memoryLatest.value = v == null ? null : Number(v)
  } catch {
    memoryLatest.value = null
  }
}

async function fetchSelfRate(metric: string): Promise<Point[]> {
  try {
    const resp: any = await api.get('/api/self/series', {
      category: 'meter',
      metric,
      from: new Date(Date.now() - rangeMs()).toISOString(),
      to: new Date().toISOString(),
      agg: 'rate',
      every: everyForRange(),
      meterType: 'timer',
      field: 'count'
    })
    const series = resp?.series || []
    if (!series.length) return []
    return series[0].points.map((p: any) => [
      new Date(p.t).getTime(),
      p.v == null ? null : Number(p.v)
    ])
  } catch {
    return []
  }
}

async function refreshWriteRates() {
  selfError.value = null
  try {
    const [m, l] = await Promise.all([
      fetchSelfRate(METRIC_WRITE_METER),
      fetchSelfRate(LOG_WRITE_METER)
    ])
    writeRateSeries.value = [
      { name: '指标写入', points: m, area: true },
      { name: '日志写入', points: l, area: true }
    ]
  } catch (e: any) {
    selfError.value = e?.message || String(e)
  }
}

async function refresh() {
  loading.value = true
  lastError.value = null
  try {
    await Promise.all([
      fetchInfraStatus(),
      fetchMemorySeries(),
      fetchMemoryLatest(),
      refreshWriteRates()
    ])
  } finally {
    loading.value = false
  }
}

function lastNonNull(series: LineSeriesItem | undefined): string {
  if (!series) return '-'
  const pts = series.points as Point[]
  for (let i = pts.length - 1; i >= 0; i--) {
    const v = pts[i][1]
    if (v != null) return formatNumber(v as number, 1)
  }
  return '-'
}

const metricWriteLatest = computed(() => lastNonNull(writeRateSeries.value[0]))
const logWriteLatest = computed(() => lastNonNull(writeRateSeries.value[1]))
const memoryLatestLabel = computed(() => {
  if (memoryLatest.value == null) return '-'
  return formatBytes(memoryLatest.value)
})

const lastUpdateLabel = computed(() => {
  if (!status.value || !status.value.lastSuccessEpochMs) return '未采集'
  return new Date(status.value.lastSuccessEpochMs).toLocaleTimeString('zh-CN', { hour12: false })
})

let timer: number | null = null
function startPolling() {
  if (timer) clearInterval(timer)
  refresh()
  timer = window.setInterval(refresh, 30000)
}
function stopPolling() {
  if (timer) { clearInterval(timer); timer = null }
}

onMounted(() => startPolling())
onBeforeUnmount(() => stopPolling())

watch(range, () => refresh())
</script>

<template>
  <div>
    <div class="flex items-center mb-3 gap-3 flex-wrap">
      <span class="text-xs text-muted">最后更新 {{ lastUpdateLabel }}</span>
      <span v-if="lastError" class="text-xs" style="color: oklch(var(--er))">异常: {{ lastError }}</span>
      <span v-if="selfError" class="text-xs" style="color: oklch(var(--er))">写入速率异常: {{ selfError }}</span>
      <span v-if="status && status.lastError" class="text-xs" style="color: oklch(var(--wa))">采集告警: {{ status.lastError }}</span>
    </div>

    <div v-if="loading && memorySeries.length === 0 && writeRateSeries.length === 0">
      <EmptyState>加载中…</EmptyState>
    </div>

    <div v-else>
      <div class="section-title">InfluxDB 监控 · 总内存 + 写入速率</div>

      <div class="chart-row full">
        <div class="chart-panel">
          <div class="panel-head">
            <span>InfluxDB 总内存</span>
            <span class="text-sm font-mono">{{ memoryLatestLabel }}</span>
            <span class="tag">MB · go_memstats.sys_bytes · {{ range }}</span>
          </div>
          <div class="panel-body has-chart">
            <Chart v-if="memorySeries.length && memorySeries[0].points.length"
                   type="line" :series="memorySeries"
                   y-axis-name="MB" :height="'280px'" />
            <EmptyState v-else inline>{{ lastError ? '查询异常' : '暂无数据' }}</EmptyState>
          </div>
        </div>
      </div>

      <div class="chart-row full">
        <div class="chart-panel">
          <div class="panel-head">
            <span>指标 / 日志 写入速率</span>
            <span class="text-sm font-mono" style="margin-right: 10px;">指标 {{ metricWriteLatest }} · 日志 {{ logWriteLatest }}</span>
            <span class="tag">点/秒 · {{ range }}</span>
          </div>
          <div class="panel-body has-chart">
            <Chart v-if="writeRateSeries.length && writeRateSeries.some((s) => s.points.length)"
                   type="line" :series="writeRateSeries" :area="true"
                   y-axis-name="点/秒" :height="'280px'" />
            <EmptyState v-else inline>{{ selfError ? '查询异常' : '暂无数据' }}</EmptyState>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
