<script setup lang="ts">
import { ref, type Ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { fetchAppViewBatch, osViewSpecs, latestRows, seriesItems, groupedItems, type AppViewBatchResponse } from '@/composables/useAppView'
import { formatPercent, formatMB } from '@/utils/format'
import { flattenPoints, pickRowValue, type MetricRow, type GroupItem } from '@/composables/metrics'
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
  if (!props.appid) return
  const { from, to } = fromTo()
  const resp = await fetchAppViewBatch(props.appid, osViewSpecs(), { from, to, every: '30s' })
  apply(resp)
}

function renderGroupedBar(groups: GroupItem[], target: Ref<{ categories: string[]; series: BarSeriesItem[] }>, elId: string, opts: { yAxisName: string; valueTransform?: (v: number) => number | null; emptyMsg: string; dangerAboveZero?: boolean }) {
  if (groups.length === 0) {
    setEmpty(elId, opts.emptyMsg)
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
}

function apply(resp: AppViewBatchResponse) {
  const rowVal = (key: string) => pickRowValue(latestRows<MetricRow>(resp, key))
  cpuCount.value = rowVal('card_cpu')
  memUtil.value = rowVal('card_mem')
  rss.value = rowVal('card_rss')
  vms.value = rowVal('card_vms')

  // 内存 used/free 堆叠面积
  const usedPts = flattenPoints(seriesItems(resp, 'mem_used'))
  const freePts = flattenPoints(seriesItems(resp, 'mem_free'))
  memChart.value = [
    { name: 'used', points: usedPts.map(([t, v]) => [t, v == null ? null : v / 1048576] as [string, number | null]), stack: true, area: true },
    { name: 'free', points: freePts.map(([t, v]) => [t, v == null ? null : v / 1048576] as [string, number | null]), stack: true, area: true }
  ]

  // CPU 时间 user/system
  const userPts = flattenPoints(seriesItems(resp, 'cpu_user'))
  const sysPts = flattenPoints(seriesItems(resp, 'cpu_sys'))
  cpuChart.value = [
    { name: 'user', points: userPts },
    { name: 'system', points: sysPts }
  ]

  // 内存饼
  const pieRows = latestRows<MetricRow>(resp, 'mem_pie')
  const used = pieRows.find((x) => x.tags && x.tags.state === 'used')
  const free = pieRows.find((x) => x.tags && x.tags.state === 'free')
  const pieData: PieDatum[] = []
  if (used && used.value != null) pieData.push({ name: 'used', value: used.value })
  if (free && free.value != null) pieData.push({ name: 'free', value: free.value })
  if (pieData.length === 0) {
    setEmpty('os-mem-pie', '暂无内存数据')
    memPie.value = []
  } else {
    memPie.value = pieData
    clearEmpty('os-mem-pie')
  }

  // 5 个 grouped 柱
  renderGroupedBar(groupedItems<GroupItem>(resp, 'disk_io'),   diskIoBar,   'os-disk-io-by-dir',     { yAxisName: 'MB/s', valueTransform: (v) => v / 1048576, emptyMsg: '暂无磁盘 IO 数据' })
  renderGroupedBar(groupedItems<GroupItem>(resp, 'disk_iops'), diskIopsBar, 'os-disk-iops-by-dir',   { yAxisName: 'ops/s',                                       emptyMsg: '暂无磁盘 IOPS 数据' })
  renderGroupedBar(groupedItems<GroupItem>(resp, 'net_io'),    netIoBar,    'os-net-io-by-dir',      { yAxisName: 'MB/s', valueTransform: (v) => v / 1048576, emptyMsg: '暂无网络 IO 数据' })
  renderGroupedBar(groupedItems<GroupItem>(resp, 'net_pkts'),  netPktsBar,  'os-net-pkts-by-dir',    { yAxisName: 'pkts/s',                                      emptyMsg: '暂无网络包数据' })
  renderGroupedBar(groupedItems<GroupItem>(resp, 'net_errs'),  netErrsBar,  'os-net-errors-by-dir',  { yAxisName: 'errs/s',                                      emptyMsg: '暂无网络错误数据', dangerAboveZero: true })
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
