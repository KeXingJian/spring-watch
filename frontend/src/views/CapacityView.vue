<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { api } from '@/api/client'
import { useToast } from '@/utils/toast'
import { useAppStore } from '@/stores/app'
import { formatTime } from '@/utils/format'
import EmptyState from '@/components/EmptyState.vue'
import Markdown from '@/components/Markdown.vue'

const toast = useToast()
const appStore = useAppStore()

const apps = computed(() => appStore.apps)
const selectedAppid = ref<string>('')
const metricOptions = [
  'jvm_memory_used_bytes',
  'jvm_memory_committed_bytes',
  'jvm_gc_pause_seconds',
  'jvm_threads_live_threads',
  'system_cpu_usage',
  'process_cpu_usage',
  'disk_free_bytes',
  'disk_total_bytes'
]
const selectedMetric = ref(metricOptions[0])
const horizon = ref(24)
const predicting = ref(false)
const result = ref<any>(null)
const history = ref<any[]>([])
const loadingHistory = ref(false)

async function loadApps() {
  try {
    const res = await api.pageFull<any>('/api/apps/active', { size: 200 })
    appStore.setApps(res.items || [])
    if (!selectedAppid.value && apps.value.length) {
      selectedAppid.value = String(apps.value[0].appid)
      await loadHistory()
    }
  } catch {
    /* ignore */
  }
}

async function loadHistory() {
  if (!selectedAppid.value) return
  loadingHistory.value = true
  try {
    // 后端返回 Spring Data Page(content 数组),用 api.page 自动解包
    history.value = await api.page<any>('/api/capacity/history', {
      appid: selectedAppid.value,
      size: 20
    })
  } catch {
    history.value = []
  } finally {
    loadingHistory.value = false
  }
}

async function predict() {
  if (!selectedAppid.value) { toast.error('请选择应用'); return }
  predicting.value = true
  result.value = null
  try {
    result.value = await api.get<any>('/api/capacity/predict', {
      appid: selectedAppid.value,
      metric: selectedMetric.value,
      horizon: horizon.value
    })
    await loadHistory()
  } catch (e: any) {
    toast.error('预测失败: ' + e.message)
  } finally {
    predicting.value = false
  }
}

function riskBadge(risk: string) {
  return { safe: 'badge-success', warning: 'badge-warning', critical: 'badge-error' }[risk] || 'badge-ghost'
}

onMounted(() => {
  loadApps()
})
</script>

<template>
  <div class="page-container-narrow">
    <div class="card bg-base-100 border border-base-300 shadow-sm mb-4">
      <div class="card-body p-0">
        <div class="px-4 py-2.5 border-b border-base-300 flex items-center font-medium text-sm">
          <span>容量预测</span>
          <span class="ml-auto text-xs text-muted font-normal">统计模型趋势预测,LLM 解释</span>
        </div>
        <div class="grid grid-cols-2 gap-3 p-4">
          <div class="form-row mb-0">
            <label>应用</label>
            <select class="select select-bordered select-sm w-full" v-model="selectedAppid" @change="loadHistory">
              <option value="">-- 请选择 --</option>
              <option v-for="a in apps" :key="a.appid" :value="String(a.appid)">{{ a.appName }} ({{ a.appid }})</option>
            </select>
          </div>
          <div class="form-row mb-0">
            <label>指标</label>
            <select class="select select-bordered select-sm w-full" v-model="selectedMetric">
              <option v-for="m in metricOptions" :key="m" :value="m">{{ m }}</option>
            </select>
          </div>
          <div class="form-row mb-0">
            <label>预测前瞻(小时)</label>
            <input type="number" min="1" max="168" class="input input-bordered input-sm w-full" v-model.number="horizon" />
          </div>
          <div class="flex items-end">
            <button class="btn btn-primary btn-sm w-full" :disabled="predicting || !selectedAppid" @click="predict">
              <span v-if="predicting" class="loading loading-spinner loading-xs" />
              {{ predicting ? '预测中…' : '开始预测' }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <div v-if="result" class="card bg-base-100 border border-base-300 shadow-sm mb-4">
      <div class="card-body p-0">
        <div class="px-4 py-2.5 border-b border-base-300 flex items-center font-medium text-sm">
          <span>预测结果</span>
          <span class="ml-auto"><span :class="['badge badge-sm', riskBadge(result.risk)]">{{ (result.risk || 'safe').toUpperCase() }}</span></span>
        </div>
        <div class="grid grid-cols-2 md:grid-cols-4 gap-3 p-4">
          <div class="metric-card">
            <div class="title">当前值</div>
            <div><span class="value">{{ result.current?.toFixed(2) ?? '-' }}</span></div>
          </div>
          <div class="metric-card">
            <div class="title">{{ result.horizonHours }}h 后预测</div>
            <div><span class="value">{{ result.predicted?.toFixed(2) ?? '-' }}</span></div>
          </div>
          <div class="metric-card">
            <div class="title">趋势斜率</div>
            <div><span class="value">{{ result.slope?.toFixed(4) ?? '-' }}</span></div>
          </div>
          <div class="metric-card">
            <div class="title">场景 / 置信度</div>
            <div><span class="value" style="font-size: 0.95rem;">{{ result.scenario }} / {{ result.confidence }}</span></div>
          </div>
        </div>
        <div v-if="result.explanation" class="px-4 py-3 border-t border-base-300 text-sm">
          <div class="text-muted text-xs mb-1">解释</div>
          <div class="explanation-scroll"><Markdown :content="result.explanation" /></div>
        </div>
      </div>
    </div>

    <div class="card bg-base-100 border border-base-300 shadow-sm">
      <div class="card-body p-0">
        <div class="px-4 py-2.5 border-b border-base-300 flex items-center font-medium text-sm">
          <span>历史预测记录</span>
          <span class="ml-auto text-xs text-muted font-normal">{{ loadingHistory ? '加载中…' : history.length + ' 条' }}</span>
        </div>
        <div class="overflow-x-auto">
          <table class="table table-sm table-zebra">
            <thead>
              <tr class="text-secondary">
                <th class="w-12">ID</th>
                <th>指标</th>
                <th class="w-24">当前值</th>
                <th class="w-24">预测值</th>
                <th class="w-20">场景</th>
                <th class="w-20">风险</th>
                <th class="w-36">时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="history.length === 0">
                <td colspan="7"><EmptyState inline>{{ selectedAppid ? '暂无预测记录' : '请先选择应用' }}</EmptyState></td>
              </tr>
              <tr v-for="h in history" v-else :key="h.id">
                <td>{{ h.id }}</td>
                <td><code class="text-xs">{{ h.metric }}</code></td>
                <td>{{ h.currentValue?.toFixed(2) ?? '-' }}</td>
                <td>{{ h.predictedValue?.toFixed(2) ?? '-' }}</td>
                <td>{{ h.scenario || '-' }}</td>
                <td><span :class="['badge badge-sm', riskBadge(h.riskLevel)]">{{ (h.riskLevel || 'safe').toUpperCase() }}</span></td>
                <td>{{ formatTime(h.createdAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.explanation-scroll {
  max-height: 12rem;
  overflow-y: auto;
  padding-right: 4px;
}
.explanation-scroll :deep(.md-body) { font-size: 0.88rem; }
</style>