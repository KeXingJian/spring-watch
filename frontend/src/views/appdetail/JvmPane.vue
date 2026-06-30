<script setup lang="ts">
import { ref, type Ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { fetchAppViewBatch, jvmViewSpecs, latestRows, seriesItems, groupedItems, quantilePoints, type AppViewBatchResponse } from '@/composables/useAppView'
import { formatPercent, formatMs } from '@/utils/format'
import { extractTagValue, translatePoolName, pickRowValue, type MetricRow, type GroupItem, type QuantilePoint, type SeriesItem } from '@/composables/metrics'
import Chart from '@/charts/Chart.vue'
import EmptyState from '@/components/EmptyState.vue'
import MetricCard from '@/components/MetricCard.vue'
import type { LineSeriesItem, BarSeriesItem } from '@/charts/types'

const props = defineProps<{ appid: string; rangeSec: number }>()

const cpuUtil = ref<number | null>(null)
const cpuClass = ref('')
const classCurrent = ref<number | null>(null)
const classLoaded = ref<number | null>(null)
const classUnloaded = ref<number | null>(null)
const threads = ref<number | null>(null)
const cpuTime = ref<number | null>(null)

const heapChart = ref<LineSeriesItem[]>([])
const nonHeapChart = ref<LineSeriesItem[]>([])
const memUtilBar = ref<{ categories: string[]; series: BarSeriesItem[] }>({ categories: [], series: [] })
const heapCommitted = ref<LineSeriesItem[]>([])
const gcCountBar = ref<{ categories: string[]; series: BarSeriesItem[] }>({ categories: [], series: [] })
const gcMeanBar = ref<{ categories: string[]; series: BarSeriesItem[] }>({ categories: [], series: [] })
const gcQuantile = ref<LineSeriesItem[]>([])
const afterGcBar = ref<{ categories: string[]; series: BarSeriesItem[] }>({ categories: [], series: [] })
const threadStateBar = ref<{ categories: string[]; series: BarSeriesItem[] }>({ categories: [], series: [] })
const classRate = ref<LineSeriesItem[]>([])

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

function toMbPoints(series: SeriesItem[]): LineSeriesItem[] {
  return series.map((s) => ({
    name: translatePoolName(extractTagValue(s.name, 'jvm_memory_pool_name')) || s.name,
    points: (s.points || []).map((p) => [p.t, p.v == null ? null : p.v / 1048576] as [string, number | null]),
    area: true
  }))
}

function renderMultiDimBar(groups: GroupItem[], dim1: string, dim2: string): { categories: string[]; series: BarSeriesItem[] } {
  if (groups.length === 0) return { categories: [], series: [] }
  const dim1Vals: string[] = []
  const dim2Set = new Set<string>()
  const map = new Map<string, number>()
  for (const g of groups) {
    const t = g.tags || {}
    const v1 = t[dim1] || 'default'
    const v2 = t[dim2] || 'default'
    if (!dim1Vals.includes(v1)) dim1Vals.push(v1)
    dim2Set.add(v2)
    map.set(v1 + '|' + v2, g.value || 0)
  }
  const dim2Vals = Array.from(dim2Set).sort()
  const series: BarSeriesItem[] = dim2Vals.map((d2) => ({
    name: d2 === 'true' ? 'daemon' : d2 === 'false' ? 'user' : d2,
    data: dim1Vals.map((d1) => map.get(d1 + '|' + d2) ?? 0)
  }))
  return { categories: dim1Vals, series }
}

async function refresh() {
  if (!props.appid) return
  const { from, to } = fromTo()
  const resp = await fetchAppViewBatch(props.appid, jvmViewSpecs(), { from, to, every: '30s' })
  apply(resp)
}

function apply(resp: AppViewBatchResponse) {
  const rowVal = (key: string) => pickRowValue(latestRows<MetricRow>(resp, key))

  cpuUtil.value = rowVal('card_cpu')
  cpuClass.value = cpuUtil.value == null ? '' : (cpuUtil.value >= 0.85 ? 'danger' : cpuUtil.value >= 0.6 ? 'warn' : 'success')
  classCurrent.value = rowVal('card_cls_cur')
  classLoaded.value = rowVal('card_cls_load')
  classUnloaded.value = rowVal('card_cls_unld')
  const thrRows = latestRows<MetricRow>(resp, 'card_threads')
  const totalThreads = thrRows.reduce((s, r) => s + (r.value || 0), 0)
  threads.value = totalThreads > 0 ? totalThreads : null
  cpuTime.value = rowVal('card_cpu_time')

  // 堆 / 非堆内存
  const heap = seriesItems(resp, 'heap')
  const nonheap = seriesItems(resp, 'nonheap')
  heapChart.value = toMbPoints(heap)
  nonHeapChart.value = toMbPoints(nonheap)
  if (heap.length === 0) setEmpty('jvm-heap', '暂无内存数据')
  else clearEmpty('jvm-heap')
  if (nonheap.length === 0) setEmpty('jvm-nonheap', '暂无内存数据')
  else clearEmpty('jvm-nonheap')

  // heap committed
  const heapCommittedSeries = seriesItems(resp, 'heap_committed')
  heapCommitted.value = toMbPoints(heapCommittedSeries)
  if (heapCommittedSeries.length === 0) setEmpty('jvm-heap-committed', '暂无 committed 数据')
  else clearEmpty('jvm-heap-committed')

  // 各 pool used% (用 latest 算)
  const usedRows = latestRows<MetricRow>(resp, 'mem_used')
  if (usedRows.length === 0) {
    setEmpty('jvm-mem-util', '暂无内存数据')
    memUtilBar.value = { categories: [], series: [] }
  } else {
    const limitRows = latestRows<MetricRow>(resp, 'mem_limit')
    const limitMap = new Map<string, number>()
    for (const r of limitRows) {
      const t = r.tags || {}
      if (t.jvm_memory_pool_name) limitMap.set(t.jvm_memory_pool_name, r.value || 0)
    }
    const data = usedRows.map((r) => {
      const t = r.tags || {}
      const pool = t.jvm_memory_pool_name || 'unknown'
      const usedVal = r.value || 0
      const lim = limitMap.get(pool)
      return {
        name: translatePoolName(pool),
        raw: pool,
        used: usedVal,
        limit: lim,
        pct: lim && lim > 0 ? (usedVal / lim) * 100 : null
      }
    })
    data.sort((a, b) => (b.pct || 0) - (a.pct || 0))
    const categories = data.map((d) => d.name)
    const pctSeries = {
      name: '已用 %',
      data: data.map((d) => {
        const v = d.pct == null ? 0 : d.pct
        let color = '#16a34a'
        if (d.pct == null) color = '#94a3b8'
        else if (d.pct >= 90) color = '#dc2626'
        else if (d.pct >= 70) color = '#ea580c'
        return { value: v, itemStyle: { color } }
      })
    }
    memUtilBar.value = { categories, series: [pctSeries] }
    clearEmpty('jvm-mem-util')
  }

  // GC count
  const gcCountGroups = groupedItems<GroupItem>(resp, 'gc_count')
  const gcCount = renderMultiDimBar(gcCountGroups, 'jvm_gc_name', 'jvm_gc_action')
  gcCountBar.value = gcCount
  if (gcCount.categories.length === 0) setEmpty('jvm-gc-count', 'GC 数据缺失')
  else clearEmpty('jvm-gc-count')

  // GC mean
  const gcSumGroups = groupedItems<GroupItem>(resp, 'gc_sum')
  const gcCntGroups = groupedItems<GroupItem>(resp, 'gc_cnt')
  const sumMap = new Map<string, number>()
  for (const g of gcSumGroups) sumMap.set(g.tags?.jvm_gc_name || g.group || '', g.value || 0)
  const meanRows = gcCntGroups.map((g) => {
    const name = g.tags?.jvm_gc_name || g.group || ''
    const sum = sumMap.get(name) || 0
    const count = g.value || 0
    return { name, meanMs: count > 0 ? (sum / count) * 1000 : 0 }
  }).filter((r) => r.name)
  if (meanRows.length === 0) {
    setEmpty('jvm-gc-mean', 'GC 数据缺失')
    gcMeanBar.value = { categories: [], series: [] }
  } else {
    meanRows.sort((a, b) => a.name.localeCompare(b.name))
    gcMeanBar.value = {
      categories: meanRows.map((r) => r.name),
      series: [{ name: '平均耗时', data: meanRows.map((r) => r.meanMs) }]
    }
    clearEmpty('jvm-gc-mean')
  }

  // GC quantile
  const gcQPoints = quantilePoints<QuantilePoint>(resp, 'gc_quantile')
  if (gcQPoints.length === 0) {
    setEmpty('jvm-gc-quantile', 'GC 分位数据缺失')
    gcQuantile.value = []
  } else {
    const byName = new Map<string, { q50: [string, number | null][]; q95: [string, number | null][]; q99: [string, number | null][] }>()
    for (const p of gcQPoints) {
      const t = p.tags || {}
      const name = t.jvm_gc_name || 'unknown'
      const action = t.jvm_gc_action || 'unknown'
      const seriesName = name + ' (' + action + ')'
      if (!byName.has(seriesName)) byName.set(seriesName, { q50: [], q95: [], q99: [] })
      const b = byName.get(seriesName)!
      for (const k of ['q50', 'q95', 'q99'] as const) {
        const v = p[k]
        if (v != null) b[k].push([p.t, v * 1000])
      }
    }
    const final: LineSeriesItem[] = []
    for (const [seriesName, q] of byName.entries()) {
      for (const k of ['q50', 'q95', 'q99'] as const) {
        if (q[k].length > 0) final.push({ name: seriesName + ' ' + k.toUpperCase(), points: q[k] })
      }
    }
    gcQuantile.value = final
    if (final.length === 0) setEmpty('jvm-gc-quantile', 'GC 分位数据缺失')
    else clearEmpty('jvm-gc-quantile')
  }

  // after GC
  const afterRows = latestRows<MetricRow>(resp, 'after_gc')
  if (afterRows.length === 0) {
    setEmpty('jvm-after-gc', '暂无 GC 后数据')
    afterGcBar.value = { categories: [], series: [] }
  } else {
    const data = afterRows.map((r) => ({
      name: translatePoolName(r.tags?.jvm_memory_pool_name || 'unknown'),
      value: r.value == null ? 0 : r.value / 1048576
    })).sort((a, b) => b.value - a.value)
    afterGcBar.value = { categories: data.map((d) => d.name), series: [{ name: 'MB', data: data.map((d) => d.value) }] }
    clearEmpty('jvm-after-gc')
  }

  // thread state
  const tsGroups = groupedItems<GroupItem>(resp, 'thread_state')
  const tsBar = renderMultiDimBar(tsGroups, 'jvm_thread_state', 'jvm_thread_daemon')
  threadStateBar.value = tsBar
  if (tsBar.categories.length === 0) setEmpty('jvm-thread-state', '线程数据缺失')
  else clearEmpty('jvm-thread-state')

  // class rate
  const loadSeries = seriesItems(resp, 'cls_load_rate')
  const unloadSeries = seriesItems(resp, 'cls_unload_rate')
  const classRateSeries: LineSeriesItem[] = []
  for (const r of [...loadSeries, ...unloadSeries]) {
    const m = r.name.match(/jvm_class_(loaded|unloaded)_total/)
    const label = m ? (m[1] === 'loaded' ? '加载速率' : '卸载速率') : r.name
    classRateSeries.push({ name: label, points: (r.points || []).map((p) => [p.t, p.v] as [string, number | null]) })
  }
  if (classRateSeries.length === 0) {
    setEmpty('jvm-class-rate', '暂无类加载数据')
    classRate.value = []
  } else {
    classRate.value = classRateSeries
    clearEmpty('jvm-class-rate')
  }
}

function onRefresh(e: any) {
  if (e?.detail?.tab !== 'jvm') return
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
    <div class="metric-card">
      <div class="title">CPU 使用率</div>
      <div class="value" :class="cpuClass">{{ cpuUtil != null ? formatPercent(cpuUtil) : '-' }}</div>
    </div>
    <MetricCard title="当前加载类" :value="classCurrent" :fixed="0" />
    <MetricCard title="累计已加载类" :value="classLoaded" :fixed="0" />
    <MetricCard title="累计已卸载类" :value="classUnloaded" :fixed="0" />
    <MetricCard title="总线程数" :value="threads" :fixed="0" />
    <div class="metric-card">
      <div class="title">JVM CPU 时间累计</div>
      <div class="value">{{ cpuTime != null ? cpuTime.toFixed(1) + ' s' : '-' }}</div>
    </div>
  </div>

  <div class="chart-row">
    <div class="chart-panel">
      <div class="panel-head">堆内存使用(按 pool 堆叠) <span class="tag">MB</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="heapChart.length" type="line" :series="heapChart" :area="true" y-axis-name="MB" />
        <EmptyState v-else inline>{{ emptyMap['jvm-heap'] || '暂无数据' }}</EmptyState>
      </div>
    </div>
    <div class="chart-panel">
      <div class="panel-head">非堆内存使用(按 pool 堆叠) <span class="tag">MB</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="nonHeapChart.length" type="line" :series="nonHeapChart" :area="true" y-axis-name="MB" />
        <EmptyState v-else inline>{{ emptyMap['jvm-nonheap'] || '暂无数据' }}</EmptyState>
      </div>
    </div>
  </div>

  <div class="chart-row">
    <div class="chart-panel">
      <div class="panel-head">各 pool 已用 vs 上限 <span class="tag">used%, 横向条</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="memUtilBar.categories.length" type="bar" :categories="memUtilBar.categories" :series="memUtilBar.series" :horizontal="true" />
        <EmptyState v-else inline>{{ emptyMap['jvm-mem-util'] || '暂无数据' }}</EmptyState>
      </div>
    </div>
    <div class="chart-panel">
      <div class="panel-head">堆 committed 趋势 <span class="tag">MB, 按 pool</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="heapCommitted.length" type="line" :series="heapCommitted" :area="true" y-axis-name="MB" />
        <EmptyState v-else inline>{{ emptyMap['jvm-heap-committed'] || '暂无数据' }}</EmptyState>
      </div>
    </div>
  </div>

  <div class="chart-row">
    <div class="chart-panel">
      <div class="panel-head">GC 累计次数 <span class="tag">按 gc_name × action</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="gcCountBar.categories.length" type="bar" :categories="gcCountBar.categories" :series="gcCountBar.series" y-axis-name="count" />
        <EmptyState v-else inline>{{ emptyMap['jvm-gc-count'] || 'GC 数据缺失' }}</EmptyState>
      </div>
    </div>
    <div class="chart-panel">
      <div class="panel-head">GC 平均耗时 <span class="tag">ms, 按 gc_name</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="gcMeanBar.categories.length" type="bar" :categories="gcMeanBar.categories" :series="gcMeanBar.series" y-axis-name="ms" />
        <EmptyState v-else inline>{{ emptyMap['jvm-gc-mean'] || 'GC 数据缺失' }}</EmptyState>
      </div>
    </div>
  </div>

  <div class="chart-row">
    <div class="chart-panel">
      <div class="panel-head">GC 耗时分位数 <span class="tag">P50/P95/P99, 按 gc_name 多线</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="gcQuantile.length" type="line" :series="gcQuantile" y-axis-name="ms" />
        <EmptyState v-else inline>{{ emptyMap['jvm-gc-quantile'] || 'GC 分位数据缺失' }}</EmptyState>
      </div>
    </div>
    <div class="chart-panel">
      <div class="panel-head">GC 后剩余内存 <span class="tag">MB, 各 pool</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="afterGcBar.categories.length" type="bar" :categories="afterGcBar.categories" :series="afterGcBar.series" y-axis-name="MB" />
        <EmptyState v-else inline>{{ emptyMap['jvm-after-gc'] || '暂无 GC 后数据' }}</EmptyState>
      </div>
    </div>
  </div>

  <div class="chart-row">
    <div class="chart-panel">
      <div class="panel-head">线程分布 <span class="tag">daemon × state, 分组柱</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="threadStateBar.categories.length" type="bar" :categories="threadStateBar.categories" :series="threadStateBar.series" y-axis-name="threads" />
        <EmptyState v-else inline>{{ emptyMap['jvm-thread-state'] || '线程数据缺失' }}</EmptyState>
      </div>
    </div>
    <div class="chart-panel">
      <div class="panel-head">类加载 / 卸载速率 <span class="tag">classes/s, 实时</span></div>
      <div class="panel-body has-chart">
        <Chart v-if="classRate.length" type="line" :series="classRate" y-axis-name="classes/s" />
        <EmptyState v-else inline>{{ emptyMap['jvm-class-rate'] || '暂无类加载数据' }}</EmptyState>
      </div>
    </div>
  </div>
</template>
