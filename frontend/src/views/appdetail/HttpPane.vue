<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { fetchAppViewBatch, httpOverviewSpecs, httpRouteSpecs, groupedItems, quantilePoints, latestRows, seriesItems, type AppViewBatchResponse } from '@/composables/useAppView'
import { formatPercent } from '@/utils/format'
import { type QuantilePoint, type GroupItem, type MetricRow } from '@/composables/metrics'
import Chart from '@/charts/Chart.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { LineSeriesItem, BarSeriesItem, PieDatum } from '@/charts/types'

const props = defineProps<{ appid: string; rangeSec: number }>()

const empty = ref(false)
const dataCount = ref<number | null>(null)
const routeCount = ref<number | null>(null)
const statusCount = ref<number | null>(null)
const errorRate = ref<number | null>(null)
const errorClass = ref('')

const statusPie = ref<PieDatum[]>([])
const methodPie = ref<PieDatum[]>([])
const topCountBar = ref<{ categories: string[]; series: BarSeriesItem[] }>({ categories: [], series: [] })
const topP99Bar = ref<{ categories: string[]; series: BarSeriesItem[] }>({ categories: [], series: [] })
const rtQuantile = ref<LineSeriesItem[]>([])

interface RoutePanel {
  id: string
  method: string
  status: string
  route: string
  count: number
  avgMs: number
  lastQps: number
  quantile: LineSeriesItem[]
  qps: LineSeriesItem[]
}
const routePanels = ref<RoutePanel[]>([])

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

async function refresh() {
  if (!props.appid) return
  const { from, to } = fromTo()
  // 第一阶段:overview + 路由列表
  const overview = await fetchAppViewBatch(props.appid, httpOverviewSpecs(), { from, to, every: '30s' })
  if (!applyOverview(overview)) return

  // 第二阶段:per-route 合并成 1 个 batch call
  const routes = routePanels.value.map((r) => ({ method: r.method, status: r.status, route: r.route }))
  if (routes.length === 0) return
  const routeResp = await fetchAppViewBatch(props.appid, httpRouteSpecs(routes), { from, to, every: '30s' })
  applyRouteDetail(routeResp)
}

function applyOverview(resp: AppViewBatchResponse): boolean {
  const routeGrp = groupedItems<GroupItem>(resp, 'grp_route')
  const statusGrp = groupedItems<GroupItem>(resp, 'grp_status')
  const methodGrp = groupedItems<GroupItem>(resp, 'grp_method')
  const p99Grp = groupedItems<GroupItem>(resp, 'grp_p99')
  const routeDetail = groupedItems<GroupItem>(resp, 'route_detail')

  // 没有任何 HTTP 指标 → 走 empty 分支
  if (routeGrp.length === 0 && statusGrp.length === 0 && routeDetail.length === 0) {
    empty.value = true
    return false
  }
  empty.value = false

  routeCount.value = routeGrp.length
  statusCount.value = statusGrp.length
  const totalReq = statusGrp.reduce((s, g) => s + (g.value || 0), 0)
  const errorReq = statusGrp
    .filter((g) => /^4|^5/.test(String(g.group || '')))
    .reduce((s, g) => s + (g.value || 0), 0)
  errorRate.value = totalReq > 0 ? errorReq / totalReq : 0
  errorClass.value = (errorRate.value || 0) >= 0.05 ? 'danger' : (errorRate.value || 0) >= 0.01 ? 'warn' : 'success'

  statusPie.value = statusGrp.map((g) => ({ name: g.group || 'unknown', value: g.value || 0 }))
  methodPie.value = methodGrp.map((g) => ({ name: g.group || 'unknown', value: g.value || 0 }))

  const topCount = [...routeGrp]
    .sort((a, b) => (b.value || 0) - (a.value || 0))
    .slice(0, 10)
  topCountBar.value = {
    categories: topCount.map((g) => g.group || 'unknown'),
    series: [{ name: '请求数', data: topCount.map((g) => g.value || 0) }]
  }

  const topP99 = [...p99Grp]
    .sort((a, b) => (b.value || 0) - (a.value || 0))
    .slice(0, 10)
  topP99Bar.value = {
    categories: topP99.map((g) => g.group || 'unknown'),
    series: [{ name: '累计耗时(s)', data: topP99.map((g) => g.value || 0) }]
  }
  if (topP99.length === 0) setEmpty('http-topn-p99', '暂无数据')
  else clearEmpty('http-topn-p99')

  // 全站 P50/P95/P99(每时刻取 max)
  const points = quantilePoints<QuantilePoint>(resp, 'rt_quantile')
  if (points.length === 0) {
    setEmpty('http-rt-quantile', '暂无响应时间数据')
    rtQuantile.value = []
  } else {
    const agg = new Map<string, { q50: number | null; q95: number | null; q99: number | null }>()
    for (const p of points) {
      const t = p.t
      if (!agg.has(t)) agg.set(t, { q50: null, q95: null, q99: null })
      const cur = agg.get(t)!
      for (const k of ['q50', 'q95', 'q99'] as const) {
        const v = p[k]
        if (v != null && (cur[k] == null || v > cur[k]!)) cur[k] = v
      }
    }
    const sorted = Array.from(agg.entries()).sort((a, b) => a[0].localeCompare(b[0]))
    rtQuantile.value = [
      { name: 'P50', points: sorted.map(([t, v]) => [t, v.q50] as [string, number | null]) },
      { name: 'P95', points: sorted.map(([t, v]) => [t, v.q95] as [string, number | null]) },
      { name: 'P99', points: sorted.map(([t, v]) => [t, v.q99] as [string, number | null]) }
    ]
    clearEmpty('http-rt-quantile')
  }

  // 按接口细粒度列表
  const list = routeDetail.map((g) => ({
    method: g.tags?.http_request_method || 'unknown',
    status: g.tags?.http_response_status_code || 'unknown',
    route: g.tags?.http_route || 'unknown',
    count: g.value || 0
  })).filter((r) => r.route)
  list.sort((a, b) => b.count - a.count)
  dataCount.value = list.length
  routePanels.value = list.map((r) => ({
    id: 'rp-' + Math.random().toString(36).slice(2, 9),
    ...r,
    avgMs: 0,
    lastQps: 0,
    quantile: [],
    qps: []
  }))
  return true
}

function applyRouteDetail(resp: AppViewBatchResponse) {
  routePanels.value.forEach((p, i) => {
    const count = pickLatestFirst(latestRows<MetricRow>(resp, `r${i}_count`))
    const sum = pickLatestFirst(latestRows<MetricRow>(resp, `r${i}_sum`))
    const qPoints = quantilePoints<QuantilePoint>(resp, `r${i}_q`)
    const qpsSeries = seriesItems(resp, `r${i}_qps`)

    p.count = count || 0
    p.avgMs = (count && count > 0) ? ((sum || 0) / count) * 1000 : 0
    const qpsPts = qpsSeries.flatMap((s) => (s.points || []).map((pp) => [pp.t, pp.v] as [string, number | null]))
    const valid = qpsPts.filter(([, v]) => v != null).slice(-3)
    const lastQps = valid.length > 0 ? valid.reduce((s, [, v]) => s + (v || 0), 0) / valid.length : 0
    p.lastQps = isFinite(lastQps) ? lastQps : 0

    if (qPoints.length > 0) {
      p.quantile = [
        { name: 'P50', points: qPoints.map((q) => [q.t, q.q50 == null ? null : q.q50 * 1000] as [string, number | null]) },
        { name: 'P95', points: qPoints.map((q) => [q.t, q.q95 == null ? null : q.q95 * 1000] as [string, number | null]) },
        { name: 'P99', points: qPoints.map((q) => [q.t, q.q99 == null ? null : q.q99 * 1000] as [string, number | null]) }
      ]
    } else {
      p.quantile = []
    }
    p.qps = qpsPts.length > 0 ? [{ name: 'QPS', points: qpsPts, area: true }] : []
  })
}

function pickLatestFirst(rows: MetricRow[]): number {
  if (!rows || rows.length === 0) return 0
  return rows[0].value || 0
}

function onRefresh(e: any) {
  if (e?.detail?.tab !== 'http') return
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
  <div v-if="empty" class="empty-state">
    <div class="icon">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" class="w-12 h-12">
        <circle cx="12" cy="12" r="9" />
        <path d="M12 7v5l3 2" stroke-linecap="round" />
      </svg>
    </div>
    <div>暂无 HTTP 请求数据</div>
    <div class="hint">HTTP 指标是懒加载的,需要 mock-test 收到至少一次请求才会出现。<br />请先访问 <code>http://&lt;endpoint&gt;/api/ping</code> 触发采集。</div>
  </div>
  <div v-else>
    <div class="metric-cards">
      <div class="metric-card"><div class="title">总请求数</div><div class="value">{{ dataCount ?? '-' }}</div></div>
      <div class="metric-card"><div class="title">不同路由数</div><div class="value">{{ routeCount ?? '-' }}</div></div>
      <div class="metric-card"><div class="title">不同状态码数</div><div class="value">{{ statusCount ?? '-' }}</div></div>
      <div class="metric-card">
        <div class="title">错误占比 (4xx/5xx)</div>
        <div class="value" :class="errorClass">{{ errorRate != null ? formatPercent(errorRate) : '-' }}</div>
      </div>
    </div>

    <div class="chart-row">
      <div class="chart-panel">
        <div class="panel-head">状态码分布</div>
        <div class="panel-body has-chart">
          <Chart v-if="statusPie.length" type="pie" :data="statusPie" name="状态码" :donut="true" />
          <EmptyState v-else inline>暂无数据</EmptyState>
        </div>
      </div>
      <div class="chart-panel">
        <div class="panel-head">HTTP 方法分布</div>
        <div class="panel-body has-chart">
          <Chart v-if="methodPie.length" type="pie" :data="methodPie" name="方法" :donut="true" />
          <EmptyState v-else inline>暂无数据</EmptyState>
        </div>
      </div>
    </div>

    <div class="chart-row">
      <div class="chart-panel">
        <div class="panel-head">Top10 路由(按请求数)</div>
        <div class="panel-body has-chart">
          <Chart
            v-if="topCountBar.categories.length"
            type="bar"
            :categories="topCountBar.categories"
            :series="topCountBar.series"
            :horizontal="true"
          />
          <EmptyState v-else inline>暂无数据</EmptyState>
        </div>
      </div>
      <div class="chart-panel">
        <div class="panel-head">Top10 路由(按累计耗时)</div>
        <div class="panel-body has-chart">
          <Chart
            v-if="topP99Bar.categories.length"
            type="bar"
            :categories="topP99Bar.categories"
            :series="topP99Bar.series"
            :horizontal="true"
            y-axis-name="s"
          />
          <EmptyState v-else inline>{{ emptyMap['http-topn-p99'] || '暂无数据' }}</EmptyState>
        </div>
      </div>
    </div>

    <div class="chart-row">
      <div class="chart-panel">
        <div class="panel-head">响应时间 P50 / P95 / P99(全站最差)</div>
        <div class="panel-body has-chart">
          <Chart v-if="rtQuantile.length" type="line" :series="rtQuantile" :smooth="true" y-axis-name="s" />
          <EmptyState v-else inline>{{ emptyMap['http-rt-quantile'] || '暂无数据' }}</EmptyState>
        </div>
      </div>
    </div>

    <h3 class="section-title">按接口({{ routePanels.length }})</h3>
    <div
      v-for="r in routePanels"
      :key="r.id"
      :class="['chart-panel mb-3', String(r.status).match(/^[45]/) ? 'route-err' : 'route-ok']"
    >
      <div class="panel-head">
        <span :class="['method-tag', String(r.status).match(/^[45]/) ? 'danger' : 'ok']">{{ r.method }}</span>
        <span :class="['status-tag', String(r.status).match(/^[45]/) ? 'danger' : 'ok']">{{ r.status }}</span>
        <code class="route-tag">{{ r.route }}</code>
        <span class="tag" style="margin-left:auto;">
          <span class="route-stat">{{ r.count.toFixed(0) }} 次</span> ·
          <span class="route-stat">{{ r.avgMs > 0 ? r.avgMs.toFixed(1) + ' ms' : '-' }}</span> ·
          <span class="route-stat">{{ isFinite(r.lastQps) ? r.lastQps.toFixed(2) + ' qps' : '-' }}</span>
        </span>
      </div>
      <div class="chart-row gap-sm">
        <div class="panel-body has-chart route-chart">
          <Chart v-if="r.quantile.length" type="line" :series="r.quantile" y-axis-name="ms" />
          <EmptyState v-else inline>无分位数据</EmptyState>
        </div>
        <div class="panel-body has-chart route-chart">
          <Chart v-if="r.qps.length" type="line" :series="r.qps" :area="true" y-axis-name="req/s" />
          <EmptyState v-else inline>无 QPS 数据</EmptyState>
        </div>
      </div>
    </div>
  </div>
</template>
