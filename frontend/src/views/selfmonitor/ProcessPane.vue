<script setup lang="ts">
import { computed, inject, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useSelfMonitor } from '@/composables/useSelfMonitor'
import { formatBytes, formatPercent } from '@/utils/format'
import Chart from '@/charts/Chart.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { LineSeriesItem } from '@/charts/types'

const { range, pollSec, realtime, fetchSeries, toMb, toPercent, pack } = useSelfMonitor()
const refreshKey = inject<import('vue').Ref<number>>('selfMonitorRefreshKey', ref(0))

const loading = ref(false)
const lastError = ref<string | null>(null)

const procCpuChart = ref<LineSeriesItem[]>([])
const procRssChart = ref<LineSeriesItem[]>([])

function fmtUptime(ms: number | null | undefined) {
  if (ms == null) return '-'
  const sec = Math.floor(ms / 1000)
  const d = Math.floor(sec / 86400)
  const h = Math.floor((sec % 86400) / 3600)
  const m = Math.floor((sec % 3600) / 60)
  if (d > 0) return d + 'd ' + h + 'h'
  if (h > 0) return h + 'h ' + m + 'm'
  return m + 'm ' + (sec % 60) + 's'
}

const sysCpu = computed(() => formatPercent(realtime.value?.process?.systemCpuLoad || 0, 1))
const sysTotal = computed(() => formatBytes(realtime.value?.process?.systemTotalBytes))
const sysFree = computed(() => formatBytes(realtime.value?.process?.systemFreeBytes))
const disk = computed(() => formatBytes(realtime.value?.process?.diskFreeBytes))
const cores = computed(() => (realtime.value?.process?.cpuCores || 0) + ' 核')
const uptime = computed(() => fmtUptime(realtime.value?.jvm?.uptimeMs))
const procRss = computed(() => {
  const rss = realtime.value?.process?.rssBytes
  return rss != null && rss > 0 ? formatBytes(rss) : '-'
})
const procRssSub = computed(() => {
  const p = realtime.value?.process
  if (!p || !p.rssBytes) return '不支持该平台'
  return `堆 ${formatBytes(p.heapUsed || 0)} · 非堆 ${formatBytes(p.nonHeapUsed || 0)}`
})

async function refresh() {
  loading.value = true
  lastError.value = null
  try {
    const m = (metric: string) => ({ category: 'process' as const, metric, agg: 'last' as const })
    const safe = async <T,>(p: Promise<T>, fallback: T): Promise<T> => {
      try { return await p } catch (e) { console.warn('[process series fetch fail]', e); return fallback }
    }
    const [proc_cpu, proc_sys_cpu, proc_rss, proc_heap, proc_nonheap, proc_virt] = await Promise.all([
      safe(fetchSeries({ ...m('cpu_load'), agg: 'mean' }).then((p) => p.map(toPercent)), []),
      safe(fetchSeries({ ...m('system_cpu_load'), agg: 'mean' }).then((p) => p.map(toPercent)), []),
      safe(fetchSeries(m('rss_bytes')).then((p) => p.map(toMb)), []),
      safe(fetchSeries(m('heap_used')).then((p) => p.map(toMb)), []),
      safe(fetchSeries(m('non_heap_used')).then((p) => p.map(toMb)), []),
      safe(fetchSeries(m('virtual_bytes')).then((p) => p.map(toMb)), [])
    ])
    procCpuChart.value = [
      { name: '进程 CPU', points: pack(proc_cpu) },
      { name: '系统 CPU', points: pack(proc_sys_cpu) }
    ]
    procRssChart.value = [
      { name: '进程 RSS MB', points: pack(proc_rss) },
      { name: 'JVM 堆已用 MB', points: pack(proc_heap) },
      { name: 'JVM 非堆已用 MB', points: pack(proc_nonheap) },
      { name: '进程虚拟内存 MB', points: pack(proc_virt) }
    ]
  } catch (e: any) {
    lastError.value = e?.message || String(e)
  } finally {
    loading.value = false
  }
}

let timer: number | null = null
function startPolling() {
  if (timer) clearInterval(timer)
  refresh()
  timer = window.setInterval(refresh, pollSec.value * 1000)
}
function stopPolling() {
  if (timer) { clearInterval(timer); timer = null }
}

onMounted(() => startPolling())
onBeforeUnmount(() => stopPolling())

watch(pollSec, () => { if (timer) startPolling() })
watch(range, () => { refresh() })
watch(refreshKey, () => { refresh() })
</script>

<template>
  <div>
    <div class="section-title">进程与主机<span v-if="loading" class="tag">加载中…</span><span v-if="lastError" class="tag" style="color: oklch(var(--er))">异常: {{ lastError }}</span></div>
    <div class="chart-row cols-3">
      <div class="chart-panel"><div class="panel-head">CPU 占用<span class="tag">0~100%</span></div><div class="panel-body has-chart"><Chart v-if="procCpuChart.length" type="line" :series="procCpuChart" y-axis-name="%" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
      <div class="chart-panel"><div class="panel-head">进程内存明细<span class="tag">RSS / JVM 堆 / JVM 非堆 / 虚拟内存,MB</span></div><div class="panel-body has-chart"><Chart v-if="procRssChart.length" type="line" :series="procRssChart" y-axis-name="MB" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
      <div class="chart-panel"><div class="panel-head">系统资源<span class="tag">MB / 核</span></div><div class="panel-body" style="padding: 12px 14px;"><div class="kv-grid">
        <div class="kv"><span class="k">系统 CPU 负载</span><span class="v">{{ sysCpu }}</span></div>
        <div class="kv"><span class="k">系统总内存</span><span class="v">{{ sysTotal }}</span></div>
        <div class="kv"><span class="k">系统可用内存</span><span class="v">{{ sysFree }}</span></div>
        <div class="kv"><span class="k">磁盘可用</span><span class="v">{{ disk }}</span></div>
        <div class="kv"><span class="k">CPU 核数</span><span class="v">{{ cores }}</span></div>
        <div class="kv"><span class="k">JVM 启动时长</span><span class="v">{{ uptime }}</span></div>
        <div class="kv"><span class="k">进程 RSS</span><span class="v">{{ procRss }}</span></div>
        <div class="kv"><span class="k">RSS 组成</span><span class="v">{{ procRssSub }}</span></div>
      </div></div></div>
    </div>
  </div>
</template>
