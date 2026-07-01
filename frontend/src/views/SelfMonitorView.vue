<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, provide, watch } from 'vue'
import { useSelfMonitor } from '@/composables/useSelfMonitor'
import { formatPercent } from '@/utils/format'
import InfluxDbPane from '@/views/selfmonitor/InfluxDbPane.vue'
import KafkaPane from '@/views/selfmonitor/KafkaPane.vue'
import OverviewPane from '@/views/selfmonitor/OverviewPane.vue'
import CollectPane from '@/views/selfmonitor/CollectPane.vue'
import JvmPane from '@/views/selfmonitor/JvmPane.vue'
import ProcessPane from '@/views/selfmonitor/ProcessPane.vue'
import MetersPane from '@/views/selfmonitor/MetersPane.vue'

type Tab = 'overview' | 'collect' | 'jvm' | 'process' | 'meters' | 'influxdb' | 'kafka'
const tabs: { key: Tab; label: string }[] = [
  { key: 'overview', label: '总览' },
  { key: 'collect',  label: '采集' },
  { key: 'jvm',      label: 'JVM' },
  { key: 'process',  label: '进程' },
  { key: 'influxdb', label: 'InfluxDB' },
  { key: 'kafka',    label: 'Kafka' },
  { key: 'meters',   label: '指标库' }
]
const activeTab = ref<Tab>('overview')

const { range, rangeOptions, pollSec, pollOptions, realtime, pollRealtimeOnce } = useSelfMonitor()

/** 每次手动刷新递增;Pane watch 此 key 触发立即拉取。仅注入给当前 active 的 Pane。 */
const refreshKey = ref(0)
provide('selfMonitorRefreshKey', refreshKey)

const meta = computed(() => {
  if (!realtime.value) return '采样间隔 10s · InfluxDB 25h 保留'
  return '采样间隔 10s · InfluxDB 25h 保留 · ' + (realtime.value.iso || '')
})

const healthPill = computed(() => {
  if (!realtime.value) return { cls: 'warn', text: '采样中' }
  const heap = realtime.value.jvm?.heap || {}
  const used = heap.used || 0
  const max = heap.max || -1
  const heapPct = max > 0 ? used / max : 0
  const cpu = realtime.value.process?.cpuLoad || 0
  if (heapPct > 0.9 || cpu > 0.9) return { cls: 'bad', text: '高负载' }
  if (heapPct > 0.75 || cpu > 0.75) return { cls: 'warn', text: '负载偏高' }
  return { cls: 'ok', text: '正常' }
})

const heapPctLabel = computed(() => {
  if (!realtime.value) return '-'
  const heap = realtime.value.jvm?.heap || {}
  const max = heap.max || -1
  return max > 0 ? formatPercent(heap.used / max, 1) : '-'
})

function setPoll(sec: number) {
  if (pollSec.value === sec) return
  pollSec.value = sec
  startRealtimePolling()
}

function manualRefresh() {
  refreshKey.value++
}

/** 顶部 health pill / meta 用的实时采样轮询,常驻;Pane 图表各自独立按需拉取。 */
let realtimeTimer: number | null = null
function startRealtimePolling() {
  if (realtimeTimer) clearInterval(realtimeTimer)
  pollRealtimeOnce()
  realtimeTimer = window.setInterval(pollRealtimeOnce, pollSec.value * 1000)
}
function stopRealtimePolling() {
  if (realtimeTimer) { clearInterval(realtimeTimer); realtimeTimer = null }
}

onMounted(() => startRealtimePolling())
onBeforeUnmount(() => stopRealtimePolling())
watch(pollSec, () => startRealtimePolling())
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2>自身监控</h2>
      <span class="meta">{{ meta }} · 范围 {{ range }}</span>
      <span class="spacer" />
      <div class="range-toggle" role="group" aria-label="时间范围">
        <button v-for="r in rangeOptions" :key="r"
                :class="['range-btn', { active: range === r }]"
                @click="range = r">{{ r }}</button>
      </div>
      <div class="range-toggle" role="group" aria-label="刷新间隔">
        <button v-for="s in pollOptions" :key="s"
                :class="['range-btn', { active: pollSec === s }]"
                :title="`每 ${s} 秒自动刷新`"
                @click="setPoll(s)">{{ s }}s</button>
      </div>
      <button class="btn btn-ghost btn-sm" @click="manualRefresh" title="立即刷新当前 tab">刷新</button>
      <span :class="['status-pill', healthPill.cls]">{{ healthPill.text }} · 堆 {{ heapPctLabel }}</span>
    </div>

    <div class="tabs">
      <div v-for="t in tabs" :key="t.key" class="tab" :class="{ active: activeTab === t.key }" @click="activeTab = t.key">
        {{ t.label }}
      </div>
    </div>

    <!-- v-if 而非 v-show:切换 tab 时才挂载/卸载 Pane,挂载即触发按需拉取 -->
    <OverviewPane v-if="activeTab === 'overview'" />
    <CollectPane  v-else-if="activeTab === 'collect'" />
    <JvmPane      v-else-if="activeTab === 'jvm'" />
    <ProcessPane  v-else-if="activeTab === 'process'" />
    <MetersPane   v-else-if="activeTab === 'meters'" />
    <template v-else-if="activeTab === 'influxdb'">
      <div class="section-title">InfluxDB · {{ meta }}</div>
      <InfluxDbPane />
    </template>
    <template v-else-if="activeTab === 'kafka'">
      <div class="section-title">Kafka · {{ meta }}</div>
      <KafkaPane />
    </template>
  </div>
</template>
