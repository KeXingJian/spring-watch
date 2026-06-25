<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch, computed } from 'vue'
import { api } from '@/api/client'
import { formatPercent, formatMs } from '@/utils/format'
import {
  fetchSeries,
  flattenPoints,
  extractTagValue,
  pickRowValue,
  type LatestResp,
  type QuantileResp
} from '@/composables/metrics'
import Chart from '@/charts/Chart.vue'
import EmptyState from '@/components/EmptyState.vue'
import MetricCard from '@/components/MetricCard.vue'
import type { LineSeriesItem, BarSeriesItem, PieDatum } from '@/charts/types'

const props = defineProps<{ appid: string; rangeSec: number }>()

const max = ref<number | null>(null)
const min = ref<number | null>(null)
const idle = ref<number | null>(null)
const used = ref<number | null>(null)
const pending = ref<number | null>(null)
const avgUseMs = ref<number | null>(null)
const avgWaitMs = ref<number | null>(null)
const avgCreateMs = ref<number | null>(null)
const usagePct = ref<number | null>(null)
const pendingClass = ref('')

const connChart = ref<LineSeriesItem[]>([])
const usageChart = ref<LineSeriesItem[]>([])
const pendingChart = ref<LineSeriesItem[]>([])
const qpsChart = ref<LineSeriesItem[]>([])

const useQuantile = ref<LineSeriesItem[]>([])
const waitQuantile = ref<LineSeriesItem[]>([])
const createQuantile = ref<LineSeriesItem[]>([])
const useDist = ref<LineSeriesItem[]>([])
const waitDist = ref<LineSeriesItem[]>([])
const createDist = ref<LineSeriesItem[]>([])

const emptyMap = ref<Record<string, string>>({})
function setEmpty(id: string, msg: string) {
  emptyMap.value = { ...emptyMap.value, [id]: msg }
}
function clearEmpty(id: string) {
  const copy = { ...emptyMap.value }
  delete copy[id]
  emptyMap.value = copy
}

function fromTo() {
  const to = new Date()
  const from = new Date(to.getTime() - props.rangeSec * 1000)
  return { from: from.toISOString(), to: to.toISOString() }
}

function avgOf(sum: number | null, count: number | null): number | null {
  if (sum == null || count == null || count <= 0) return null
  return sum / count
}

async function refresh() {
  const { from, to } = fromTo()
  const appid = props.appid
  if (!appid) return

  try {
    const [
      maxR, minR, idleR, usedR, pendingR,
      useSumR, useCntR, waitSumR, waitCntR, createSumR, createCntR
    ] = await Promise.all([
      api.get<LatestResp>('/api/metrics/latest', { appid, metric: 'db_client_connections_max' }),
      api.get<LatestResp>('/api/metrics/latest', { appid, metric: 'db_client_connections_idle_min' }),
      api.get<LatestResp>('/api/metrics/latest', { appid, metric: 'db_client_connections_usage', state: 'idle' }),
      api.get<LatestResp>('/api/metrics/latest', { appid, metric: 'db_client_connections_usage', state: 'used' }),
      api.get<LatestResp>('/api/metrics/latest', { appid, metric: 'db_client_connections_pending_requests' }),
      api.get<LatestResp>('/api/metrics/latest', { appid, metric: 'db_client_connections_use_time_milliseconds_sum' }),
      api.get<LatestResp>('/api/metrics/latest', { appid, metric: 'db_client_connections_use_time_milliseconds_count' }),
      api.get<LatestResp>('/api/metrics/latest', { appid, metric: 'db_client_connections_wait_time_milliseconds_sum' }),
      api.get<LatestResp>('/api/metrics/latest', { appid, metric: 'db_client_connections_wait_time_milliseconds_count' }),
      api.get<LatestResp>('/api/metrics/latest', { appid, metric: 'db_client_connections_create_time_milliseconds_sum' }),
      api.get<LatestResp>('/api/metrics/latest', { appid, metric: 'db_client_connections_create_time_milliseconds_count' })
    ])

    const v = (r: LatestResp) => pickRowValue(r?.rows)
    max.value = v(maxR); min.value = v(minR); idle.value = v(idleR); used.value = v(usedR)
    pending.value = v(pendingR)
    usagePct.value = (max.value && used.value != null) ? used.value / max.value : null
    avgUseMs.value = avgOf(v(useSumR), v(useCntR))
    avgWaitMs.value = avgOf(v(waitSumR), v(waitCntR))
    avgCreateMs.value = avgOf(v(createSumR), v(createCntR))
    pendingClass.value = pending.value != null ? (pending.value > 0 ? 'danger' : 'success') : ''
  } catch (e: any) {
    console.warn('jdbc cards failed', e)
  }

  // 连接池状态
  try {
    const idleSeries = flattenPoints(await fetchSeries(appid, 'db_client_connections_usage', { state: 'idle' }, from, to))
    const usedSeries = flattenPoints(await fetchSeries(appid, 'db_client_connections_usage', { state: 'used' }, from, to))
    connChart.value = [
      { name: '空闲', points: idleSeries, stack: true, area: true },
      { name: '使用', points: usedSeries, stack: true, area: true }
    ]
    if (max.value && max.value > 0) {
      const maxVal = max.value
      const pts = usedSeries.map(([t, u]) => [t, u == null ? null : (u / maxVal) * 100] as [string, number | null])
      usageChart.value = [{ name: '使用率', points: pts, area: true }]
      clearEmpty('jdbc-usage-chart')
    } else {
      usageChart.value = []
      setEmpty('jdbc-usage-chart', '暂无 max 数据')
    }
  } catch {
    /* ignore */
  }

  // 等待请求
  try {
    const ps = flattenPoints(await fetchSeries(appid, 'db_client_connections_pending_requests', {}, from, to))
    pendingChart.value = ps.length > 0 ? [{ name: '等待请求', points: ps }] : []
    if (ps.length === 0) setEmpty('jdbc-pending-chart', '暂无等待请求数据')
    else clearEmpty('jdbc-pending-chart')
  } catch {
    pendingChart.value = []
    setEmpty('jdbc-pending-chart', '加载失败')
  }

  // QPS
  try {
    const qpsSeries = await fetchSeries(appid, 'db_client_connections_use_time_milliseconds_count', { agg: 'rate' }, from, to)
    const arr = qpsSeries.map((s) => ({
      name: extractTagValue(s.name, 'pool_name') || 'QPS',
      points: (s.points || []).map((p) => [p.t, p.v] as [string, number | null])
    }))
    if (arr.some((s) => s.points.length > 0)) {
      qpsChart.value = arr
      clearEmpty('jdbc-qps-chart')
    } else {
      qpsChart.value = []
      setEmpty('jdbc-qps-chart', '暂无 QPS 数据')
    }
  } catch {
    qpsChart.value = []
    setEmpty('jdbc-qps-chart', '加载失败')
  }

  // 分位
  await renderHistogramQuantile(appid, 'db_client_connections_use_time_milliseconds', 'useQuantile', 'ms')
  await renderHistogramQuantile(appid, 'db_client_connections_wait_time_milliseconds', 'waitQuantile', 'ms')
  await renderHistogramQuantile(appid, 'db_client_connections_create_time_milliseconds', 'createQuantile', 'ms')
  await renderHistogram(appid, 'db_client_connections_use_time_milliseconds', 'useDist', 'ms')
  await renderHistogram(appid, 'db_client_connections_wait_time_milliseconds', 'waitDist', 'ms')
  await renderHistogram(appid, 'db_client_connections_create_time_milliseconds', 'createDist', 'ms')
}

async function renderHistogramQuantile(appid: string, metric: string, target: 'useQuantile' | 'waitQuantile' | 'createQuantile', unit: string) {
  const { from, to } = fromTo()
  try {
    const r = await api.get<QuantileResp>('/api/metrics/histogram-quantile', {
      appid, metric, quantiles: '0.5,0.95,0.99', from, to, every: '30s'
    })
    const points = r?.points || []
    const elId = 'jdbc-' + target.replace('Quantile', '-quantile')
    if (points.length === 0) {
      setEmpty(elId, '暂无分位数据')
      if (target === 'useQuantile') useQuantile.value = []
      if (target === 'waitQuantile') waitQuantile.value = []
      if (target === 'createQuantile') createQuantile.value = []
      return
    }
    const series: LineSeriesItem[] = [
      { name: 'P50', points: points.map((p) => [p.t, p.q50 == null ? null : p.q50] as [string, number | null]) },
      { name: 'P95', points: points.map((p) => [p.t, p.q95 == null ? null : p.q95] as [string, number | null]) },
      { name: 'P99', points: points.map((p) => [p.t, p.q99 == null ? null : p.q99] as [string, number | null]) }
    ]
    if (target === 'useQuantile') useQuantile.value = series
    if (target === 'waitQuantile') waitQuantile.value = series
    if (target === 'createQuantile') createQuantile.value = series
    clearEmpty(elId)
  } catch {
    setEmpty('jdbc-' + target.replace('Quantile', '-quantile'), '分位计算失败')
  }
}

async function renderHistogram(appid: string, metric: string, target: 'useDist' | 'waitDist' | 'createDist', _unit: string) {
  const { from, to } = fromTo()
  const countMetric = metric + '_count'
  try {
    const r = await api.get<any>('/api/metrics/series', { appid, metric: countMetric, from, to, every: '30s' })
    const seriesArr = r?.series || []
    const allPoints = seriesArr.flatMap((s: any) => (s.points || []).map((p: any) => [p.t, p.v] as [string, number | null]))
    const elId = 'jdbc-' + target.replace('Dist', '-dist')
    if (allPoints.length === 0) {
      setEmpty(elId, '暂无数据(可能未触发采集)')
      if (target === 'useDist') useDist.value = []
      if (target === 'waitDist') waitDist.value = []
      if (target === 'createDist') createDist.value = []
      return
    }
    const series: LineSeriesItem[] = [{ name: '调用次数(累计)', points: allPoints, area: true }]
    if (target === 'useDist') useDist.value = series
    if (target === 'waitDist') waitDist.value = series
    if (target === 'createDist') createDist.value = series
    clearEmpty(elId)
  } catch {
    setEmpty('jdbc-' + target.replace('Dist', '-dist'), '加载失败')
  }
}

function onRefresh(e: any) {
  if (e?.detail?.tab !== 'jdbc') return
  refresh()
}

onMounted(() => {
  refresh()
  window.addEventListener('appdetail-refresh', onRefresh)
})
onBeforeUnmount(() => window.removeEventListener('appdetail-refresh', onRefresh))
watch(() => [props.appid, props.rangeSec], refresh)
</script>

<template>
  <div class="metric-cards" style="grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));">
    <MetricCard title="最大连接数" :value="max" :fixed="0" />
    <MetricCard title="最小空闲" :value="min" :fixed="0" />
    <MetricCard title="空闲连接" :value="idle" :fixed="0" />
    <MetricCard title="使用连接" :value="used" :fixed="0" />
    <MetricCard title="使用率" :value="usagePct" format="percent" :threshold="0.85" />
    <MetricCard title="等待请求" :value="pending" :fixed="0" />
    <MetricCard title="平均使用耗时" :value="avgUseMs" format="ms" />
    <MetricCard title="平均等待耗时" :value="avgWaitMs" format="ms" />
    <MetricCard title="平均创建耗时" :value="avgCreateMs" format="ms" />
  </div>

  <div class="chart-row">
    <div class="chart-panel">
      <div class="panel-head">连接池状态(空闲/使用) <span class="tag">堆叠面积</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="connChart.length" type="line" :series="connChart" y-axis-name="connections" />
        <EmptyState v-else inline>暂无数据</EmptyState>
      </div>
    </div>
    <div class="chart-panel">
      <div class="panel-head">连接使用率(used/max) <span class="tag">%</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="usageChart.length" type="line" :series="usageChart" y-axis-name="%" :area="true" />
        <EmptyState v-else inline>{{ emptyMap['jdbc-usage-chart'] || '暂无数据' }}</EmptyState>
      </div>
    </div>
  </div>

  <div class="chart-row">
    <div class="chart-panel">
      <div class="panel-head">等待请求时序 <span class="tag">&gt;0 告警</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="pendingChart.length" type="line" :series="pendingChart" y-axis-name="requests" />
        <EmptyState v-else inline>{{ emptyMap['jdbc-pending-chart'] || '暂无数据' }}</EmptyState>
      </div>
    </div>
    <div class="chart-panel">
      <div class="panel-head">获取连接 QPS <span class="tag">agg=rate</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="qpsChart.length" type="line" :series="qpsChart" :area="true" y-axis-name="req/s" />
        <EmptyState v-else inline>{{ emptyMap['jdbc-qps-chart'] || '暂无数据' }}</EmptyState>
      </div>
    </div>
  </div>
  <div class="chart-row">
    <div class="chart-panel">
      <div class="panel-head">连接使用耗时 P50/P95/P99</div>
      <div class="panel-body has-chart">
        <Chart v-if="useQuantile.length" type="line" :series="useQuantile" y-axis-name="ms" />
        <EmptyState v-else inline>{{ emptyMap['jdbc-use-quantile'] || '暂无数据' }}</EmptyState>
      </div>
    </div>
    <div class="chart-panel">
      <div class="panel-head">连接使用次数趋势(累计 count)</div>
      <div class="panel-body has-chart">
        <Chart v-if="useDist.length" type="line" :series="useDist" y-axis-name="count" />
        <EmptyState v-else inline>{{ emptyMap['jdbc-use-dist'] || '暂无数据' }}</EmptyState>
      </div>
    </div>
  </div>

  <div class="chart-row">
    <div class="chart-panel">
      <div class="panel-head">获取连接等待耗时 P50/P95/P99 <span class="tag">关注长尾</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="waitQuantile.length" type="line" :series="waitQuantile" y-axis-name="ms" />
        <EmptyState v-else inline>{{ emptyMap['jdbc-wait-quantile'] || '暂无数据' }}</EmptyState>
      </div>
    </div>
    <div class="chart-panel">
      <div class="panel-head">获取连接等待次数趋势(累计 count)</div>
      <div class="panel-body has-chart">
        <Chart v-if="waitDist.length" type="line" :series="waitDist" y-axis-name="count" />
        <EmptyState v-else inline>{{ emptyMap['jdbc-wait-dist'] || '暂无数据' }}</EmptyState>
      </div>
    </div>
  </div>

  <div class="chart-row">
    <div class="chart-panel">
      <div class="panel-head">创建连接耗时 P50/P95/P99</div>
      <div class="panel-body has-chart">
        <Chart v-if="createQuantile.length" type="line" :series="createQuantile" y-axis-name="ms" />
        <EmptyState v-else inline>{{ emptyMap['jdbc-create-quantile'] || '暂无数据' }}</EmptyState>
      </div>
    </div>
    <div class="chart-panel">
      <div class="panel-head">创建连接次数趋势(累计 count)</div>
      <div class="panel-body has-chart">
        <Chart v-if="createDist.length" type="line" :series="createDist" y-axis-name="count" />
        <EmptyState v-else inline>{{ emptyMap['jdbc-create-dist'] || '暂无数据' }}</EmptyState>
      </div>
    </div>
  </div>
</template>
