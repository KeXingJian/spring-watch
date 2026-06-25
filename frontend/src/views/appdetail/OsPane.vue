<script setup lang="ts">
import { ref, type Ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { api } from '@/api/client'
import { formatPercent, formatMB } from '@/utils/format'
import {
  fetchSeries,
  flattenPoints,
  pickRowValue,
  type LatestResp,
  type GroupedResp
} from '@/composables/metrics'
import Chart from '@/charts/Chart.vue'
import EmptyState from '@/components/EmptyState.vue'
import MetricCard from '@/components/MetricCard.vue'
import type { LineSeriesItem, BarSeriesItem, PieDatum } from '@/charts/types'

const props = defineProps<{ appid: string; rangeSec: number }>()

const cpuCount = ref<number | null>(null)
const memUtil = ref<number | null>(null)
const rss = ref<number | null>(null)
const vms = ref<number | null>(null)

const memChart = ref<LineSeriesItem[]>([])
const cpuChart = ref<LineSeriesItem[]>([])
const memPie = ref<PieDatum[]>([])

const diskIoBar = ref<{ categories: string[]; series: BarSeriesItem[] }>({ categories: [], series: [] })
const diskIopsBar = ref<{ categories: string[]; series: BarSeriesItem[] }>({ categories: [], series: [] })
const netIoBar = ref<{ categories: string[]; series: BarSeriesItem[] }>({ categories: [], series: [] })
const netPktsBar = ref<{ categories: string[]; series: BarSeriesItem[] }>({ categories: [], series: [] })
const netErrsBar = ref<{ categories: string[]; series: BarSeriesItem[] }>({ categories: [], series: [] })

const emptyMap = ref<Record<string, string>>({})
function setEmpty(id: string, msg: string) {
  emptyMap.value = { ...emptyMap.value, [id]: msg }
}
function clearEmpty(id: string) {
  const c = { ...emptyMap.value }
  delete c[id]
  emptyMap.value = c
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

  try {
    const [cpuCountR, memUtilR, rssR, vmsR] = await Promise.all([
      api.get<LatestResp>('/api/metrics/latest', { appid, metric: 'jvm_cpu_count' }),
      api.get<LatestResp>('/api/metrics/latest', { appid, metric: 'system_memory_utilization', state: 'used' }),
      api.get<LatestResp>('/api/metrics/latest', { appid, metric: 'runtime_java_memory_bytes', type: 'rss' }),
      api.get<LatestResp>('/api/metrics/latest', { appid, metric: 'runtime_java_memory_bytes', type: 'vms' })
    ])
    cpuCount.value = pickRowValue(cpuCountR?.rows)
    memUtil.value = pickRowValue(memUtilR?.rows)
    rss.value = pickRowValue(rssR?.rows)
    vms.value = pickRowValue(vmsR?.rows)
  } catch (e) {
    console.warn('os cards failed', e)
  }

  // 内存 used/free 堆叠面积
  try {
    const usedPts = flattenPoints(await fetchSeries(appid, 'system_memory_usage_bytes', { state: 'used' }, from, to))
    const freePts = flattenPoints(await fetchSeries(appid, 'system_memory_usage_bytes', { state: 'free' }, from, to))
    memChart.value = [
      { name: 'used', points: usedPts.map(([t, v]) => [t, v == null ? null : v / 1048576] as [string, number | null]), stack: true, area: true },
      { name: 'free', points: freePts.map(([t, v]) => [t, v == null ? null : v / 1048576] as [string, number | null]), stack: true, area: true }
    ]
  } catch (e) {
    console.warn(e)
  }

  // CPU 时间 user/system
  try {
    const userPts = flattenPoints(await fetchSeries(appid, 'runtime_java_cpu_time_milliseconds', { type: 'user' }, from, to))
    const sysPts = flattenPoints(await fetchSeries(appid, 'runtime_java_cpu_time_milliseconds', { type: 'system' }, from, to))
    cpuChart.value = [
      { name: 'user', points: userPts },
      { name: 'system', points: sysPts }
    ]
  } catch (e) {
    console.warn(e)
  }

  await renderMemPie(appid)
  await renderGroupedBar(appid, 'system_disk_io_bytes_total', diskIoBar, 'os-disk-io-by-dir', { agg: 'rate', valueTransform: (v: number) => v / 1048576, yAxisName: 'MB/s', emptyMsg: '暂无磁盘 IO 数据' })
  await renderGroupedBar(appid, 'system_disk_operations_total', diskIopsBar, 'os-disk-iops-by-dir', { agg: 'rate', yAxisName: 'ops/s', emptyMsg: '暂无磁盘 IOPS 数据' })
  await renderGroupedBar(appid, 'system_network_io_bytes_total', netIoBar, 'os-net-io-by-dir', { agg: 'rate', valueTransform: (v: number) => v / 1048576, yAxisName: 'MB/s', emptyMsg: '暂无网络 IO 数据' })
  await renderGroupedBar(appid, 'system_network_packets_total', netPktsBar, 'os-net-pkts-by-dir', { agg: 'rate', yAxisName: 'pkts/s', emptyMsg: '暂无网络包数据' })
  await renderGroupedBar(appid, 'system_network_errors_total', netErrsBar, 'os-net-errors-by-dir', { agg: 'rate', yAxisName: 'errs/s', emptyMsg: '暂无网络错误数据', dangerAboveZero: true })
}

async function renderMemPie(appid: string) {
  try {
    const r = await api.get<LatestResp>('/api/metrics/latest', { appid, metric: 'system_memory_usage_bytes' })
    const rows = r?.rows || []
    const used = rows.find((x) => x.tags && x.tags.state === 'used')
    const free = rows.find((x) => x.tags && x.tags.state === 'free')
    const data: PieDatum[] = []
    if (used && used.value != null) data.push({ name: 'used', value: used.value })
    if (free && free.value != null) data.push({ name: 'free', value: free.value })
    if (data.length === 0) {
      setEmpty('os-mem-pie', '暂无内存数据')
      memPie.value = []
      return
    }
    memPie.value = data
    clearEmpty('os-mem-pie')
  } catch {
    setEmpty('os-mem-pie', '内存数据缺失')
  }
}

async function renderGroupedBar(
  appid: string,
  metric: string,
  target: Ref<{ categories: string[]; series: BarSeriesItem[] }>,
  elId: string,
  opts: { agg?: string; yAxisName?: string; valueTransform?: (v: number) => number | null; emptyMsg?: string; dangerAboveZero?: boolean }
) {
  try {
    const r = await api.get<GroupedResp>('/api/metrics/grouped', { appid, metric, groupBy: 'device,direction', agg: opts.agg || 'last' })
    const groups = r?.groups || []
    if (groups.length === 0) {
      setEmpty(elId, opts.emptyMsg || '暂无数据')
      target.value = { categories: [], series: [] }
      return
    }
    const devices: string[] = []
    const directionsSet = new Set<string>()
    const map = new Map<string, number>()
    for (const g of groups) {
      const tags = g.tags || {}
      const d1 = tags.device || 'default'
      const d2 = tags.direction || 'default'
      if (!devices.includes(d1)) devices.push(d1)
      directionsSet.add(d2)
      map.set(d1 + '|' + d2, g.value || 0)
    }
    const directions = Array.from(directionsSet).sort()
    const transform = opts.valueTransform || ((x: number) => x)
    const series: BarSeriesItem[] = directions.map((dir) => ({
      name: dir,
      data: devices.map((dev) => {
        const v = map.get(dev + '|' + dir)
        const t = v == null ? 0 : transform(v)
        return t == null ? 0 : t
      })
    }))
    if (opts.dangerAboveZero) {
      for (const s of series) {
        if (s.data.some((d: any) => (typeof d === 'number' ? d : d.value) > 0)) {
          ;(s as any).itemStyle = { color: '#dc2626' }
        } else {
          ;(s as any).itemStyle = { color: '#16a34a' }
        }
      }
    }
    target.value = { categories: devices, series }
    clearEmpty(elId)
  } catch (e) {
    console.warn('grouped bar failed', metric, e)
    setEmpty(elId, opts.emptyMsg || '加载失败')
  }
}

function onRefresh(e: any) {
  if (e?.detail?.tab !== 'os') return
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
  <div class="metric-cards">
    <MetricCard title="CPU 核心数" :value="cpuCount" :fixed="0" />
    <MetricCard title="系统内存使用率" :value="memUtil" format="percent" :threshold="0.85" />
    <div class="metric-card">
      <div class="title">Java 进程 RSS</div>
      <div class="value">{{ formatMB(rss) }} <span class="unit">MB</span></div>
    </div>
    <div class="metric-card">
      <div class="title">Java 进程 VMS</div>
      <div class="value">{{ formatMB(vms) }} <span class="unit">MB</span></div>
    </div>
  </div>

  <div class="chart-row">
    <div class="chart-panel">
      <div class="panel-head">系统内存使用量(used / free) <span class="tag">MB,堆叠</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="memChart.length" type="line" :series="memChart" :stack="true" :area="true" y-axis-name="MB" />
        <EmptyState v-else inline>暂无数据</EmptyState>
      </div>
    </div>
    <div class="chart-panel">
      <div class="panel-head">Java CPU 时间(user / system) <span class="tag">ms</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="cpuChart.length" type="line" :series="cpuChart" y-axis-name="ms" />
        <EmptyState v-else inline>暂无数据</EmptyState>
      </div>
    </div>
  </div>

  <div class="chart-row">
    <div class="chart-panel">
      <div class="panel-head">系统内存利用率 <span class="tag">used / free</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="memPie.length" type="pie" :data="memPie" name="内存" :donut="true" legend-orient="horizontal" />
        <EmptyState v-else inline>{{ emptyMap['os-mem-pie'] || '暂无数据' }}</EmptyState>
      </div>
    </div>
    <div class="chart-panel">
      <div class="panel-head">磁盘 IO 字节 <span class="tag">MB,按 device × direction</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="diskIoBar.categories.length" type="bar" :categories="diskIoBar.categories" :series="diskIoBar.series" y-axis-name="MB" />
        <EmptyState v-else inline>{{ emptyMap['os-disk-io-by-dir'] || '暂无数据' }}</EmptyState>
      </div>
    </div>
  </div>

  <div class="chart-row">
    <div class="chart-panel">
      <div class="panel-head">磁盘 IOPS <span class="tag">ops,按 device × direction</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="diskIopsBar.categories.length" type="bar" :categories="diskIopsBar.categories" :series="diskIopsBar.series" y-axis-name="ops" />
        <EmptyState v-else inline>{{ emptyMap['os-disk-iops-by-dir'] || '暂无数据' }}</EmptyState>
      </div>
    </div>
    <div class="chart-panel">
      <div class="panel-head">网络 IO 字节 <span class="tag">MB,按 device × direction</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="netIoBar.categories.length" type="bar" :categories="netIoBar.categories" :series="netIoBar.series" y-axis-name="MB" />
        <EmptyState v-else inline>{{ emptyMap['os-net-io-by-dir'] || '暂无数据' }}</EmptyState>
      </div>
    </div>
  </div>

  <div class="chart-row">
    <div class="chart-panel">
      <div class="panel-head">网络收发包 <span class="tag">pkts,按 device × direction</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="netPktsBar.categories.length" type="bar" :categories="netPktsBar.categories" :series="netPktsBar.series" y-axis-name="pkts" />
        <EmptyState v-else inline>{{ emptyMap['os-net-pkts-by-dir'] || '暂无数据' }}</EmptyState>
      </div>
    </div>
    <div class="chart-panel">
      <div class="panel-head">网络错误数 <span class="tag">errs,按 device × direction,&gt;0 高亮</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="netErrsBar.categories.length" type="bar" :categories="netErrsBar.categories" :series="netErrsBar.series" y-axis-name="errs" />
        <EmptyState v-else inline>{{ emptyMap['os-net-errors-by-dir'] || '暂无数据' }}</EmptyState>
      </div>
    </div>
  </div>
</template>
