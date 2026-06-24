<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch, computed } from 'vue'
import { api } from '@/api/client'
import { formatPercent } from '@/utils/format'
import {
  type LatestResp,
  type GroupedResp,
  type QuantileResp
} from '@/composables/metrics'
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

const routePanels = ref<Array<{
  id: string
  method: string
  status: string
  route: string
  count: number
  avgMs: number
  lastQps: number
  quantile: LineSeriesItem[]
  qps: LineSeriesItem[]
}>>([])

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
  const { from, to } = fromTo()
  const appid = props.appid
  if (!appid) return

  let data: any
  try {
    data = await api.get('/api/metrics/by-prefix', { appid, prefix: 'http_server_request_duration_seconds' })
  } catch {
    empty.value = true
    return
  }
  if (!data || !data.rows || data.rows.length === 0) {
    empty.value = true
    return
  }
  empty.value = false
  dataCount.value = data.count

  try {
    const [routeGrp, statusGrp, methodGrp] = await Promise.all([
      api.get<GroupedResp>('/api/metrics/grouped', { appid, metric: 'http_server_request_duration_seconds_count', groupBy: 'http_route' }),
      api.get<GroupedResp>('/api/metrics/grouped', { appid, metric: 'http_server_request_duration_seconds_count', groupBy: 'http_response_status_code' }),
      api.get<GroupedResp>('/api/metrics/grouped', { appid, metric: 'http_server_request_duration_seconds_count', groupBy: 'http_request_method' })
    ])
    routeCount.value = (routeGrp?.groups || []).length
    statusCount.value = (statusGrp?.groups || []).length
    const totalReq = (statusGrp?.groups || []).reduce((s, g) => s + (g.value || 0), 0)
    const errorReq = (statusGrp?.groups || [])
      .filter((g) => /^4|^5/.test(String(g.group || '')))
      .reduce((s, g) => s + (g.value || 0), 0)
    errorRate.value = totalReq > 0 ? errorReq / totalReq : 0
    errorClass.value = (errorRate.value || 0) >= 0.05 ? 'danger' : (errorRate.value || 0) >= 0.01 ? 'warn' : 'success'

    statusPie.value = (statusGrp?.groups || []).map((g) => ({ name: g.group || 'unknown', value: g.value || 0 }))
    methodPie.value = (methodGrp?.groups || []).map((g) => ({ name: g.group || 'unknown', value: g.value || 0 }))

    const topCount = (routeGrp?.groups || [])
      .slice()
      .sort((a, b) => (b.value || 0) - (a.value || 0))
      .slice(0, 10)
    topCountBar.value = {
      categories: topCount.map((g) => g.group || 'unknown'),
      series: [{ name: '请求数', data: topCount.map((g) => g.value || 0) }]
    }
  } catch (e: any) {
    console.warn('http grouped failed', e)
  }

  // Top by P99 sum (proxy)
  try {
    const p99Grp = await api.get<GroupedResp>('/api/metrics/grouped', {
      appid, metric: 'http_server_request_duration_seconds_sum', groupBy: 'http_route'
    })
    const topP99 = (p99Grp?.groups || [])
      .slice()
      .sort((a, b) => (b.value || 0) - (a.value || 0))
      .slice(0, 10)
    topP99Bar.value = {
      categories: topP99.map((g) => g.group || 'unknown'),
      series: [{ name: '累计耗时(s)', data: topP99.map((g) => g.value || 0) }]
    }
  } catch {
    setEmpty('http-topn-p99', '暂无数据')
  }

  // 全站响应时间 P50/P95/P99(每时刻取 max)
  try {
    const q = await api.get<QuantileResp>('/api/metrics/histogram-quantile', {
      appid, metric: 'http_server_request_duration_seconds', quantiles: '0.5,0.95,0.99', from, to, every: '30s'
    })
    const points = q?.points || []
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
  } catch (e: any) {
    setEmpty('http-rt-quantile', '分位计算失败:' + e.message)
  }

  // 按接口细粒度
  try {
    const detail = await api.get<GroupedResp>('/api/metrics/grouped', {
      appid, metric: 'http_server_request_duration_seconds_count', groupBy: 'http_request_method,http_response_status_code,http_route'
    })
    const list = (detail?.groups || []).map((g) => ({
      method: g.tags?.http_request_method || 'unknown',
      status: g.tags?.http_response_status_code || 'unknown',
      route: g.tags?.http_route || 'unknown',
      count: g.value || 0
    })).filter((r) => r.route)
    list.sort((a, b) => b.count - a.count)
    routePanels.value = list.map((r) => ({
      id: 'rp-' + Math.random().toString(36).slice(2, 9),
      ...r,
      avgMs: 0,
      lastQps: 0,
      quantile: [],
      qps: []
    }))
    // 触发懒加载
    for (const p of routePanels.value) {
      loadRoutePanel(appid, p, from, to)
    }
  } catch (e) {
    console.warn('http route detail failed', e)
  }
}

async function loadRoutePanel(appid: string, r: any, from: string, to: string) {
  const tagFilters = {
    http_request_method: r.method,
    http_response_status_code: r.status,
    http_route: r.route
  }
  try {
    const [countR, sumR, q, qpsR] = await Promise.all([
      api.get<LatestResp>('/api/metrics/latest', { appid, metric: 'http_server_request_duration_seconds_count', ...tagFilters }),
      api.get<LatestResp>('/api/metrics/latest', { appid, metric: 'http_server_request_duration_seconds_sum', ...tagFilters }),
      api.get<QuantileResp>('/api/metrics/histogram-quantile', { appid, metric: 'http_server_request_duration_seconds', from, to, every: '30s', ...tagFilters }),
      api.get<any>('/api/metrics/series', { appid, metric: 'http_server_request_duration_seconds_count', from, to, every: '30s', agg: 'rate', ...tagFilters })
    ])
    const count = countR?.rows?.[0]?.value || 0
    const sum = sumR?.rows?.[0]?.value || 0
    r.count = count
    r.avgMs = count > 0 ? (sum / count) * 1000 : 0
    const qpsPoints = (qpsR?.series || []).flatMap((s: any) => (s.points || []).map((p: any) => [p.t, p.v] as [string, number | null]))
    const lastQps = qpsPoints.filter((p: [string, number | null]) => p[1] != null).slice(-3).reduce((s: number, p: [string, number | null]) => s + (p[1] || 0), 0) / 3
    r.lastQps = isFinite(lastQps) ? lastQps : 0
    const points = q?.points || []
    if (points.length > 0) {
      r.quantile = [
        { name: 'P50', points: points.map((p) => [p.t, p.q50 == null ? null : p.q50 * 1000] as [string, number | null]) },
        { name: 'P95', points: points.map((p) => [p.t, p.q95 == null ? null : p.q95 * 1000] as [string, number | null]) },
        { name: 'P99', points: points.map((p) => [p.t, p.q99 == null ? null : p.q99 * 1000] as [string, number | null]) }
      ]
    } else {
      r.quantile = []
    }
    r.qps = qpsPoints.length > 0 ? [{ name: 'QPS', points: qpsPoints, area: true }] : []
  } catch (e) {
    console.warn('route panel load failed', r, e)
    r.quantile = []
    r.qps = []
  }
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
