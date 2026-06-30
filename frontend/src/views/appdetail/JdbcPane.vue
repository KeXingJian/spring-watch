<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { fetchAppViewBatch, jdbcViewSpecs, latestRows, seriesItems, quantilePoints, type AppViewBatchResponse } from '@/composables/useAppView'
import { flattenPoints, extractTagValue, pickRowValue, type MetricRow, type QuantilePoint, type SeriesItem } from '@/composables/metrics'
import Chart from '@/charts/Chart.vue'
import EmptyState from '@/components/EmptyState.vue'
import MetricCard from '@/components/MetricCard.vue'
import type { LineSeriesItem } from '@/charts/types'

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

function buildQuantileSeries(pts: QuantilePoint[], unit: 's' | 'ms'): LineSeriesItem[] {
  if (pts.length === 0) return []
  const factor = unit === 's' ? 1000 : 1
  return [
    { name: 'P50', points: pts.map((p) => [p.t, p.q50 == null ? null : p.q50 * factor] as [string, number | null]) },
    { name: 'P95', points: pts.map((p) => [p.t, p.q95 == null ? null : p.q95 * factor] as [string, number | null]) },
    { name: 'P99', points: pts.map((p) => [p.t, p.q99 == null ? null : p.q99 * factor] as [string, number | null]) }
  ]
}

function buildCountSeries(series: SeriesItem[]): LineSeriesItem[] {
  if (series.length === 0) return []
  const allPts = series.flatMap((s) => (s.points || []).map((p) => [p.t, p.v] as [string, number | null]))
  if (allPts.length === 0) return []
  return [{ name: '调用次数(累计)', points: allPts, area: true }]
}

async function refresh() {
  if (!props.appid) return
  const { from, to } = fromTo()
  const resp = await fetchAppViewBatch(props.appid, jdbcViewSpecs(), { from, to, every: '30s' })
  apply(resp)
}

function apply(resp: AppViewBatchResponse) {
  // 卡片:11 latest
  const rowVal = (key: string) => pickRowValue(latestRows<MetricRow>(resp, key))
  max.value = rowVal('card_max')
  min.value = rowVal('card_min')
  idle.value = rowVal('card_idle')
  used.value = rowVal('card_used')
  pending.value = rowVal('card_pend')
  usagePct.value = (max.value && used.value != null) ? used.value / max.value : null
  avgUseMs.value = avgOf(rowVal('card_use_sum'), rowVal('card_use_cnt'))
  avgWaitMs.value = avgOf(rowVal('card_wait_sum'), rowVal('card_wait_cnt'))
  avgCreateMs.value = avgOf(rowVal('card_create_sum'), rowVal('card_create_cnt'))
  pendingClass.value = pending.value != null ? (pending.value > 0 ? 'danger' : 'success') : ''

  // 连接池状态
  const idlePts = flattenPoints(seriesItems(resp, 'conn_idle'))
  const usedPts = flattenPoints(seriesItems(resp, 'conn_used'))
  connChart.value = [
    { name: '空闲', points: idlePts, stack: true, area: true },
    { name: '使用', points: usedPts, stack: true, area: true }
  ]
  if (max.value && max.value > 0) {
    const maxVal = max.value
    usageChart.value = [{ name: '使用率', points: usedPts.map(([t, u]) => [t, u == null ? null : (u / maxVal) * 100] as [string, number | null]), area: true }]
    clearEmpty('jdbc-usage-chart')
  } else {
    usageChart.value = []
    setEmpty('jdbc-usage-chart', '暂无 max 数据')
  }

  // 等待请求
  const ps = flattenPoints(seriesItems(resp, 'pending'))
  pendingChart.value = ps.length > 0 ? [{ name: '等待请求', points: ps }] : []
  if (ps.length === 0) setEmpty('jdbc-pending-chart', '暂无等待请求数据')
  else clearEmpty('jdbc-pending-chart')

  // QPS
  const qpsSeries = seriesItems(resp, 'qps')
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

  // 分位
  const useQ = quantilePoints<QuantilePoint>(resp, 'q_use')
  const waitQ = quantilePoints<QuantilePoint>(resp, 'q_wait')
  const createQ = quantilePoints<QuantilePoint>(resp, 'q_create')
  useQuantile.value = buildQuantileSeries(useQ, 'ms')
  waitQuantile.value = buildQuantileSeries(waitQ, 'ms')
  createQuantile.value = buildQuantileSeries(createQ, 'ms')
  if (useQ.length === 0) setEmpty('jdbc-use-quantile', '暂无分位数据')
  else clearEmpty('jdbc-use-quantile')
  if (waitQ.length === 0) setEmpty('jdbc-wait-quantile', '暂无分位数据')
  else clearEmpty('jdbc-wait-quantile')
  if (createQ.length === 0) setEmpty('jdbc-create-quantile', '暂无分位数据')
  else clearEmpty('jdbc-create-quantile')

  // count 时序(累计)
  const useDistSeries = buildCountSeries(seriesItems(resp, 'd_use'))
  const waitDistSeries = buildCountSeries(seriesItems(resp, 'd_wait'))
  const createDistSeries = buildCountSeries(seriesItems(resp, 'd_create'))
  useDist.value = useDistSeries
  waitDist.value = waitDistSeries
  createDist.value = createDistSeries
  if (useDistSeries.length === 0) setEmpty('jdbc-use-dist', '暂无数据(可能未触发采集)')
  else clearEmpty('jdbc-use-dist')
  if (waitDistSeries.length === 0) setEmpty('jdbc-wait-dist', '暂无数据(可能未触发采集)')
  else clearEmpty('jdbc-wait-dist')
  if (createDistSeries.length === 0) setEmpty('jdbc-create-dist', '暂无数据(可能未触发采集)')
  else clearEmpty('jdbc-create-dist')
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
