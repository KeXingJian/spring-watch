<script setup lang="ts">
import { ref, reactive, onMounted, onBeforeUnmount, watch, computed } from 'vue'
import { api } from '@/api/client'
import { useToast } from '@/utils/toast'
import { useAppStore } from '@/stores/app'
import { formatPercent, formatTime, shortMsg } from '@/utils/format'
import Chart from '@/charts/Chart.vue'
import EmptyState from '@/components/EmptyState.vue'
import Modal from '@/components/Modal.vue'
import type { LineSeriesItem, BarSeriesItem, PieDatum } from '@/charts/types'

const toast = useToast()
const appStore = useAppStore()

const rangeSec = ref(3600)
const rangeOptions = [
  { sec: 900, label: '15m' },
  { sec: 3600, label: '1h' },
  { sec: 21600, label: '6h' },
  { sec: 86400, label: '24h' }
]

const appid = computed<string | null>(() => appStore.currentAppid)

const cardTotal = ref<number | null>(null)
const cardError = ref<number | null>(null)
const cardWarn = ref<number | null>(null)
const cardErrorSub = ref('-')
const cardSpike = ref<'是' | '否' | '-'>('-')
const cardSpikeClass = ref('')
const cardSpikeSub = ref('倍数阈值 3.0')

const errorRateSeries = ref<LineSeriesItem[]>([])
const levelsPie = ref<PieDatum[]>([])
const errorRateEmpty = ref('')
const levelsEmpty = ref('')
const patternsBar = ref<{ categories: string[]; series: BarSeriesItem[] }>({ categories: [], series: [] })
const dedupBar = ref<{ categories: string[]; series: BarSeriesItem[] }>({ categories: [], series: [] })
const patternsEmpty = ref('')
const dedupEmpty = ref('')

const filters = reactive({
  keyword: '',
  level: '',
  logger: '',
  thread: '',
  trace: '',
  fp: ''
})
const searchResults = ref<any[]>([])
const searchCount = ref(0)
const showAppHint = ref(false)

const showDetail = ref(false)
const showContext = ref(false)
const detailRow = ref<any>(null)
const contextRows = ref<any[]>([])

function fromTo() {
  const to = new Date()
  const from = new Date(to.getTime() - rangeSec.value * 1000)
  return { from: from.toISOString(), to: to.toISOString() }
}

async function loadAll() {
  if (!appid.value) {
    showAppHint.value = true
    return
  }
  showAppHint.value = false
  await Promise.all([loadOverview(), loadErrorRate(), loadLevels()])
}

async function loadOverview() {
  try {
    const r = await api.get<any>('/api/logs/stats/error-rate', { appid: appid.value, windowSeconds: rangeSec.value })
    cardTotal.value = r?.total ?? null
    cardError.value = r?.error ?? null
    cardWarn.value = r?.warn ?? null
    cardErrorSub.value = '错误率 ' + formatPercent(r?.errorRate)
  } catch (e) {
    console.warn('overview failed', e)
  }
  try {
    const a = await api.get<any>('/api/logs/anomaly', { appid: appid.value, windowSeconds: 60, multiplier: 3.0 })
    cardSpike.value = a?.spiking ? '是' : '否'
    cardSpikeClass.value = a?.spiking ? 'danger' : 'success'
    const cur = a?.stats?.errorRate || 0
    const last = cur / 3.0
    cardSpikeSub.value = `当前 ${(cur * 100).toFixed(2)}%(基线 ≈ ${(last * 100).toFixed(2)}%)`
  } catch (e) {
    cardSpike.value = '-'
  }
}

async function loadErrorRate() {
  try {
    const { from, to } = fromTo()
    const r = await api.get<any[]>('/api/logs/stats/error-rate-series', { appid: appid.value, from, to, every: '1m' })
    const buckets = r || []
    if (buckets.length === 0) {
      errorRateEmpty.value = '暂无错误率数据'
      errorRateSeries.value = []
      return
    }
    const total: [string, number | null][] = []
    const error: [string, number | null][] = []
    const warn: [string, number | null][] = []
    for (const b of buckets) {
      total.push([b.time, b.total || 0])
      error.push([b.time, b.error || 0])
      warn.push([b.time, b.warn || 0])
    }
    errorRateSeries.value = [
      { name: 'total', points: total, area: true },
      { name: 'error', points: error },
      { name: 'warn', points: warn }
    ]
    errorRateEmpty.value = ''
  } catch (e: any) {
    errorRateEmpty.value = '加载失败: ' + (e?.message || e)
    errorRateSeries.value = []
  }
}

async function loadLevels() {
  try {
    const { from, to } = fromTo()
    const r = await api.get<any[]>('/api/logs/levels', { appid: appid.value, from, to })
    const data = (r || []).map((x) => ({ name: x.level, value: x.count }))
    if (data.length === 0) {
      levelsEmpty.value = '暂无级别数据'
      levelsPie.value = []
      return
    }
    levelsPie.value = data
    levelsEmpty.value = ''
  } catch (e: any) {
    levelsEmpty.value = '加载失败: ' + (e?.message || e)
    levelsPie.value = []
  }
}

async function doSearch() {
  if (!appid.value) return
  const { from, to } = fromTo()
  const params: any = {
    appid: appid.value,
    from,
    to,
    limit: 10
  }
  if (filters.keyword) params.keyword = filters.keyword
  if (filters.level) params.level = filters.level
  if (filters.logger) params.logger = filters.logger
  if (filters.thread) params.threadName = filters.thread
  if (filters.trace) params.traceId = filters.trace
  if (filters.fp) params.fingerprint = filters.fp
  try {
    const r = await api.get<any[]>('/api/logs/search', params)
    searchResults.value = r || []
    searchCount.value = searchResults.value.length
    await loadPatterns()
  } catch (e: any) {
    toast.error('检索失败: ' + e.message)
  }
}

function resetSearch() {
  filters.keyword = ''
  filters.level = ''
  filters.logger = ''
  filters.thread = ''
  filters.trace = ''
  filters.fp = ''
  doSearch()
}

async function loadPatterns() {
  try {
    const { from, to } = fromTo()
    const r = await api.get<any[]>('/api/logs/patterns', { appid: appid.value, from, to, level: 'ERROR', topN: 10 })
    const list = r || []
    if (list.length === 0) {
      patternsEmpty.value = '暂无异常模式'
      patternsBar.value = { categories: [], series: [] }
    } else {
      patternsBar.value = {
        categories: list.map((p) => shortMsg(p.fingerprint, 12)),
        series: [{ name: '次数', data: list.map((p) => p.count) }]
      }
      patternsEmpty.value = ''
    }
  } catch (e: any) {
    patternsEmpty.value = '加载失败: ' + (e?.message || e)
    patternsBar.value = { categories: [], series: [] }
  }
  try {
    const r = await api.get<any[]>('/api/logs/dedup/top', { appid: appid.value, topN: 10 })
    const list = r || []
    if (list.length === 0) {
      dedupEmpty.value = '暂无去重模式'
      dedupBar.value = { categories: [], series: [] }
    } else {
      dedupBar.value = {
        categories: list.map((p) => shortMsg(p.fingerprint, 12)),
        series: [{ name: '去重次数', data: list.map((p) => p.dedupCount) }]
      }
      dedupEmpty.value = ''
    }
  } catch (e: any) {
    dedupEmpty.value = '加载失败: ' + (e?.message || e)
    dedupBar.value = { categories: [], series: [] }
  }
}

function openDetail(row: any) {
  detailRow.value = row
  showDetail.value = true
}
function openContext(row: any) {
  loadContext(row)
}
function fpSearch(row: any) {
  filters.fp = row.fingerprint
  doSearch()
}

async function loadContext(row: any) {
  if (!appid.value) return
  const params: any = {
    appid: appid.value,
    time: row.time,
    threadName: row.threadName,
    logger: row.logger,
    host: row.host,
    beforeSec: 30,
    afterSec: 30,
    limit: 100
  }
  try {
    contextRows.value = (await api.get<any[]>('/api/logs/context', params)) || []
    detailRow.value = row
    showContext.value = true
  } catch (e: any) {
    toast.error('加载上下文失败: ' + e.message)
  }
}

async function copyDetail() {
  if (!detailRow.value) return
  const text = [detailRow.value.message || '', detailRow.value.throwable || ''].join('\n\n')
  try {
    await navigator.clipboard.writeText(text)
    toast.success('已复制')
  } catch {
    /* ignore */
  }
}

function viewContextFromDetail() {
  if (detailRow.value) {
    showDetail.value = false
    loadContext(detailRow.value)
  }
}

onMounted(() => {
  loadAll()
  doSearch()
  window.addEventListener('appid-changed', () => {
    loadAll()
    doSearch()
  })
})
onBeforeUnmount(() => {
  window.removeEventListener('appid-changed', () => {})
})

watch(appid, () => {
  loadAll()
  doSearch()
})
watch(rangeSec, () => {
  loadAll()
})
</script>

<template>
  <div class="page-container">
    <div v-if="showAppHint" class="alert alert-info mb-4 text-sm">
      <span>请先在 [监控应用] 选择一个应用</span>
    </div>

    <div class="toolbar">
      <span class="label">时间范围:</span>
      <div class="join">
        <button v-for="r in rangeOptions" :key="r.sec" class="btn btn-sm join-item" :class="rangeSec === r.sec ? 'btn-primary' : 'btn-ghost'" @click="rangeSec = r.sec">{{ r.label }}</button>
      </div>
      <span class="right" />
      <button class="btn btn-ghost btn-sm" @click="loadAll">
        <svg viewBox="0 0 20 20" fill="currentColor" class="w-4 h-4"><path fill-rule="evenodd" d="M4 2a1 1 0 0 1 1 1v2.101a7.002 7.002 0 0 1 11.601 2.566 1 1 0 1 1-1.885.666A5.002 5.002 0 0 0 5.999 7H9a1 1 0 0 1 0 2H4a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1zm.008 9.057a1 1 0 0 1 1.276.61A5.002 5.002 0 0 0 14.001 13H11a1 1 0 1 1 0-2h5a1 1 0 0 1 1 1v5a1 1 0 1 1-2 0v-2.1a7.002 7.002 0 0 1-11.601-2.566 1 1 0 0 1 .61-1.277z" clip-rule="evenodd"/></svg>
        刷新
      </button>
    </div>

    <div class="metric-cards" style="grid-template-columns: repeat(4, 1fr);">
      <div class="metric-card"><div class="title">总日志(全级别)</div><div class="value">{{ cardTotal ?? '-' }}</div><div class="sub">最近 1h</div></div>
      <div class="metric-card"><div class="title">ERROR 数</div><div class="value danger">{{ cardError ?? '-' }}</div><div class="sub">{{ cardErrorSub }}</div></div>
      <div class="metric-card"><div class="title">WARN 数</div><div class="value warn">{{ cardWarn ?? '-' }}</div><div class="sub">告警关注</div></div>
      <div class="metric-card"><div class="title">错误率突增</div><div class="value" :class="cardSpikeClass">{{ cardSpike }}</div><div class="sub">{{ cardSpikeSub }}</div></div>
    </div>

    <div class="chart-row" style="grid-template-columns: 2fr 1fr;">
      <div class="chart-panel">
        <div class="panel-head">错误率时序 <span class="tag">total / error / warn,1m 窗口</span></div>
        <div class="panel-body has-chart">
          <Chart v-if="errorRateSeries.length" type="line" :series="errorRateSeries" :area="true" y-axis-name="条" />
          <EmptyState v-else inline>{{ errorRateEmpty || '暂无数据' }}</EmptyState>
        </div>
      </div>
      <div class="chart-panel">
        <div class="panel-head">级别分布 <span class="tag">最近 1h</span></div>
        <div class="panel-body has-chart">
          <Chart v-if="levelsPie.length" type="pie" :data="levelsPie" name="级别" :donut="true" />
          <EmptyState v-else inline>{{ levelsEmpty || '暂无数据' }}</EmptyState>
        </div>
      </div>
    </div>

    <div class="card bg-base-100 border border-base-300 shadow-sm mb-4">
      <div class="card-body p-0">
        <div class="px-4 py-2.5 border-b border-base-300 flex items-center font-medium text-sm">
          <span>日志检索</span>
          <span class="ml-auto text-xs text-muted font-normal">{{ searchCount }} 条</span>
        </div>
        <div class="flex flex-wrap gap-2 p-3 border-b border-base-300 items-center bg-base-200/40">
          <span class="label">关键字:</span>
          <input class="input input-bordered input-sm min-w-[180px]" v-model="filters.keyword" placeholder="message/throwable 包含" @keyup.enter="doSearch" />
          <span class="label">级别:</span>
          <select class="select select-bordered select-sm" v-model="filters.level">
            <option value="">全部</option>
            <option value="ERROR">ERROR</option>
            <option value="WARN">WARN</option>
            <option value="INFO">INFO</option>
            <option value="DEBUG">DEBUG</option>
          </select>
          <span class="label">logger:</span>
          <input class="input input-bordered input-sm min-w-[160px]" v-model="filters.logger" placeholder="类全名,精确匹配" />
          <span class="label">thread:</span>
          <input class="input input-bordered input-sm min-w-[120px]" v-model="filters.thread" placeholder="线程名" />
          <span class="label">traceId:</span>
          <input class="input input-bordered input-sm min-w-[140px]" v-model="filters.trace" placeholder="精确" />
          <span class="label">fingerprint:</span>
          <input class="input input-bordered input-sm min-w-[140px]" v-model="filters.fp" placeholder="精确" />
          <span class="ml-auto" />
          <button class="btn btn-primary btn-sm" @click="doSearch">查询</button>
          <button class="btn btn-ghost btn-sm" @click="resetSearch">重置</button>
        </div>
        <div class="overflow-x-auto">
          <table class="table table-sm table-zebra">
            <thead>
              <tr class="text-secondary">
                <th class="w-40">时间</th>
                <th class="w-20">级别</th>
                <th class="w-60">logger</th>
                <th class="w-32">thread</th>
                <th>message</th>
                <th class="w-24">traceId</th>
                <th class="w-52">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="searchResults.length === 0">
                <td colspan="7"><EmptyState inline>设置过滤条件后点击 [查询]</EmptyState></td>
              </tr>
              <tr
                v-for="(r, idx) in searchResults"
                v-else
                :key="idx"
                :class="(r.level || '').toUpperCase() === 'ERROR' ? 'bg-error/5' : (r.level || '').toUpperCase() === 'WARN' ? 'bg-warning/5' : ''"
              >
                <td>{{ formatTime(r.time) }}</td>
                <td><span :class="['level-tag', (r.level || 'UNKNOWN').toUpperCase()]">{{ (r.level || 'UNKNOWN').toUpperCase() }}</span></td>
                <td :title="r.logger"><code class="text-xs">{{ shortMsg(r.logger, 30) }}</code></td>
                <td><code class="text-xs">{{ r.threadName || '-' }}</code></td>
                <td class="truncate max-w-md" :title="r.message">{{ r.message || '-' }}</td>
                <td>{{ r.traceId ? shortMsg(r.traceId, 10) : '-' }}</td>
                <td>
                  <div class="row-actions">
                    <button class="btn btn-ghost btn-xs" @click="openDetail(r)">详情</button>
                    <button class="btn btn-ghost btn-xs" @click="openContext(r)">上下文</button>
                    <button v-if="r.fingerprint" class="btn btn-ghost btn-xs" @click="fpSearch(r)">按指纹</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <div class="chart-row">
      <div class="chart-panel">
        <div class="panel-head">Top 异常模式 <span class="tag">按 fingerprint 聚合</span></div>
        <div class="panel-body has-chart">
          <Chart v-if="patternsBar.categories.length" type="bar" :categories="patternsBar.categories" :series="patternsBar.series" :horizontal="true" />
          <EmptyState v-else inline>{{ patternsEmpty || '暂无数据' }}</EmptyState>
        </div>
      </div>
      <div class="chart-panel">
        <div class="panel-head">高频去重模式 <span class="tag">滑动窗口 1m 内重复</span></div>
        <div class="panel-body has-chart">
          <Chart v-if="dedupBar.categories.length" type="bar" :categories="dedupBar.categories" :series="dedupBar.series" :horizontal="true" />
          <EmptyState v-else inline>{{ dedupEmpty || '暂无数据' }}</EmptyState>
        </div>
      </div>
    </div>
  </div>

  <Modal v-model="showDetail" title="日志详情" :width="760">
    <div class="text-sm mb-2 flex items-center gap-2 flex-wrap">
      <span v-if="detailRow" :class="['level-tag', (detailRow.level || 'UNKNOWN').toUpperCase()]">{{ (detailRow.level || 'UNKNOWN').toUpperCase() }}</span>
      <span>{{ formatTime(detailRow?.time) }} | logger=<code class="text-xs">{{ detailRow?.logger || '-' }}</code> | thread={{ detailRow?.threadName || '-' }}</span>
    </div>
    <label class="text-xs text-secondary block mt-2 mb-1">message</label>
    <pre class="log-detail">{{ detailRow?.message || '-' }}</pre>
    <label class="text-xs text-secondary block mt-3 mb-1">throwable</label>
    <pre class="log-detail" style="max-height: 200px;">{{ detailRow?.throwable || '(无异常栈)' }}</pre>
    <label class="text-xs text-secondary block mt-3 mb-1">pattern / fingerprint</label>
    <pre class="log-detail" style="max-height: 80px;">
fingerprint: {{ detailRow?.fingerprint || '-' }}
pattern: {{ detailRow?.pattern || '-' }}
    </pre>
    <template #footer>
      <button class="btn btn-ghost btn-sm" @click="copyDetail">复制全文</button>
      <button class="btn btn-ghost btn-sm" @click="viewContextFromDetail">查看上下文</button>
      <button class="btn btn-primary btn-sm" @click="showDetail = false">关闭</button>
    </template>
  </Modal>

  <Modal v-model="showContext" title="上下文(±30s)" :width="920">
    <div v-if="detailRow" class="text-sm mb-2">
      中心日志: {{ formatTime(detailRow.time) }} <code class="text-xs">{{ detailRow.logger || '-' }}</code> thread={{ detailRow.threadName || '-' }} | 共 {{ contextRows.length }} 条上下文
    </div>
    <div>
      <div
        v-for="(r, i) in contextRows"
        :key="i"
        :class="['p-2 mb-1.5 rounded border-l-[3px]', r.time === detailRow?.time ? 'border-error bg-error/5' : 'border-base-300 bg-base-200/40']"
      >
        <div class="text-xs text-muted flex items-center gap-2">
          <span :class="['level-tag', (r.level || 'UNKNOWN').toUpperCase()]">{{ (r.level || 'UNKNOWN').toUpperCase() }}</span>
          <span>{{ formatTime(r.time) }} | <code class="text-xs">{{ r.threadName || '-' }}</code></span>
        </div>
        <div class="text-sm mt-1 whitespace-pre-wrap break-all">
          {{ r.message || '-' }}
          <span v-if="r.throwable" class="text-error">{{ shortMsg(r.throwable, 500) }}</span>
        </div>
      </div>
      <EmptyState v-if="contextRows.length === 0" inline>无上下文</EmptyState>
    </div>
    <template #footer>
      <button class="btn btn-primary btn-sm" @click="showContext = false">关闭</button>
    </template>
  </Modal>
</template>
