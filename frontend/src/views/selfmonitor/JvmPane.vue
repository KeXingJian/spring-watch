<script setup lang="ts">
import { inject, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useSelfMonitor } from '@/composables/useSelfMonitor'
import Chart from '@/charts/Chart.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { LineSeriesItem } from '@/charts/types'

const { range, pollSec, fetchSeries, fetchSeriesMulti, toMb, pack, gcLabel, poolLabel } = useSelfMonitor()
const refreshKey = inject<import('vue').Ref<number>>('selfMonitorRefreshKey', ref(0))

const loading = ref(false)
const lastError = ref<string | null>(null)

const jvmMemChart = ref<LineSeriesItem[]>([])
const jvmPoolChart = ref<LineSeriesItem[]>([])
const jvmThreadsChart = ref<LineSeriesItem[]>([])
const jvmGcChart = ref<LineSeriesItem[]>([])

async function refresh() {
  loading.value = true
  lastError.value = null
  try {
    const m = (metric: string) => ({ category: 'jvm' as const, metric, agg: 'last' as const })
    const safe = async <T,>(p: Promise<T>, fallback: T): Promise<T> => {
      try { return await p } catch (e) { console.warn('[jvm series fetch fail]', e); return fallback }
    }
    const [jvm_heap, jvm_meta, jvm_nonheap, jvm_pool_used, jvm_thr_cur, jvm_thr_daemon, jvm_cls_loaded, gc_time_multi] = await Promise.all([
      safe(fetchSeries(m('heap.used')).then((p) => p.map(toMb)), []),
      safe(fetchSeries(m('metaspace.used')).then((p) => p.map(toMb)), []),
      safe(fetchSeries(m('nonHeap.used')).then((p) => p.map(toMb)), []),
      safe(
        fetchSeriesMulti(m('pool.used')).then((arr) =>
          arr.map((s) => ({ name: s.name, points: (s.points as any[]).map(toMb) }))
        ),
        []
      ),
      safe(fetchSeries(m('threads.current')), []),
      safe(fetchSeries(m('threads.daemon')), []),
      safe(fetchSeries(m('classes.loaded')), []),
      safe(fetchSeriesMulti({ ...m('gc.time_ms'), agg: 'rate' }), [])
    ])
    jvmMemChart.value = [
      { name: '堆已用 MB', points: pack(jvm_heap), area: true },
      { name: 'Metaspace MB', points: pack(jvm_meta) },
      { name: '非堆 MB', points: pack(jvm_nonheap) }
    ]
    jvmPoolChart.value = (jvm_pool_used || []).map((s) => ({ name: poolLabel(s.name), points: s.points }))
    jvmThreadsChart.value = [
      { name: '线程数', points: pack(jvm_thr_cur) },
      { name: '守护线程', points: pack(jvm_thr_daemon) },
      { name: '已加载类', points: pack(jvm_cls_loaded) }
    ]
    jvmGcChart.value = (gc_time_multi || []).map((s) => ({ name: gcLabel(s.name), points: s.points, area: true }))
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
    <div class="section-title">JVM 运行时<span v-if="loading" class="tag">加载中…</span><span v-if="lastError" class="tag" style="color: oklch(var(--er))">异常: {{ lastError }}</span></div>
    <div class="chart-row">
      <div class="chart-panel"><div class="panel-head">内存分布<span class="tag">MB</span></div><div class="panel-body has-chart"><Chart v-if="jvmMemChart.length" type="line" :series="jvmMemChart" :area="true" y-axis-name="MB" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
      <div class="chart-panel"><div class="panel-head">堆各区已用<span class="tag">按 pool_name 分线,MB</span></div><div class="panel-body has-chart"><Chart v-if="jvmPoolChart.length" type="line" :series="jvmPoolChart" y-axis-name="MB" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
      <div class="chart-panel"><div class="panel-head">线程数与类加载<span class="tag">个</span></div><div class="panel-body has-chart"><Chart v-if="jvmThreadsChart.length" type="line" :series="jvmThreadsChart" y-axis-name="个" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
    </div>
    <div class="chart-row full">
      <div class="chart-panel"><div class="panel-head">GC 暂停时间(各收集器,ms/采样周期)<span class="tag">越大说明 GC 压力越重</span></div><div class="panel-body has-chart"><Chart v-if="jvmGcChart.length" type="line" :series="jvmGcChart" :area="true" y-axis-name="ms/周期" /><EmptyState v-else inline>暂无数据</EmptyState></div></div>
    </div>
  </div>
</template>
