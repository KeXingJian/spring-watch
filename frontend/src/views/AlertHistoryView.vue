<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { api } from '@/api/client'
import { useToast } from '@/utils/toast'
import { formatTime } from '@/utils/format'
import EmptyState from '@/components/EmptyState.vue'

const toast = useToast()

const rangeSec = ref(604800)
const rangeOptions = [
  { sec: 3600, label: '1h' },
  { sec: 86400, label: '24h' },
  { sec: 604800, label: '7d' },
  { sec: 2592000, label: '30d' }
]
const levelFilter = ref('')
const statusFilter = ref('')
const allRows = ref<any[]>([])
const expanded = ref<Record<number, boolean>>({})

/** M4-5: 总行数 + 最近一次清理行数 / 时间,从 /api/self/realtime 拉 */
const totalRows = ref<number | null>(null)
const lastPurgedRows = ref<number | null>(null)
const lastPurgedAt = ref<string | null>(null)
let pollTimer: number | null = null

function gaugeVal(realtime: any, name: string): number | null {
  if (!realtime || !realtime.meters) return null
  const entry = (realtime.meters.gauges || {})[name]
  if (entry == null) return null
  if (typeof entry === 'number') return entry
  if (Array.isArray(entry)) {
    const vals = entry.map((e: any) => e?.value).filter((v: any) => v != null) as number[]
    if (!vals.length) return null
    return vals.reduce((a, b) => a + b, 0)
  }
  return entry.value ?? null
}

async function loadRetention() {
  try {
    const rt: any = await api.get('/api/self/realtime')
    totalRows.value = gaugeVal(rt, 'spring.watch.alert.history.total_rows')
    lastPurgedRows.value = gaugeVal(rt, 'spring.watch.alert.history.purged.last_rows')
    const epoch = gaugeVal(rt, 'spring.watch.alert.history.purged.last_at_epoch')
    if (epoch && epoch > 0) {
      lastPurgedAt.value = new Date(epoch).toISOString()
    } else {
      lastPurgedAt.value = null
    }
  } catch {
    /* 静默:列表页能加载就够,自监控读取失败不影响主流程 */
  }
}

const filtered = computed(() => {
  return allRows.value.filter((r) => {
    if (levelFilter.value && (r.alertLevel || 'warning') !== levelFilter.value) return false
    const isFiring = !r.resolvedAt
    if (statusFilter.value === 'firing' && !isFiring) return false
    if (statusFilter.value === 'resolved' && isFiring) return false
    return true
  })
})

async function loadAll() {
  try {
    const res = await api.pageFull<any>('/api/alert/history', { size: 200 })
    allRows.value = res.items || []
  } catch (e: any) {
    toast.error('加载失败: ' + e.message)
  }
}

function toggleDetail(id: number) {
  expanded.value[id] = !expanded.value[id]
}

function appLabel(r: any) {
  return r.app ? `${r.app.appName} (${r.app.appid})` : '-'
}

function formatPurgedAt(iso: string | null): string {
  if (!iso) return '尚未触发清理'
  const d = new Date(iso)
  if (isNaN(d.getTime()) || d.getTime() < 1000) return '尚未触发清理'
  return formatTime(iso) + ' (' + d.toLocaleString() + ')'
}

onMounted(() => {
  loadAll()
  loadRetention()
  pollTimer = window.setInterval(loadRetention, 30_000)
})
onBeforeUnmount(() => {
  if (pollTimer != null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
})
</script>

<template>
  <div class="page-container">
    <div class="toolbar">
      <span class="label">时间范围:</span>
      <div class="join">
        <button v-for="r in rangeOptions" :key="r.sec" class="btn btn-sm join-item" :class="rangeSec === r.sec ? 'btn-primary' : 'btn-ghost'" @click="rangeSec = r.sec">{{ r.label }}</button>
      </div>
      <select class="select select-bordered select-sm" v-model="levelFilter">
        <option value="">全部级别</option>
        <option value="info">INFO</option>
        <option value="warning">WARNING</option>
        <option value="critical">CRITICAL</option>
      </select>
      <select class="select select-bordered select-sm" v-model="statusFilter">
        <option value="">全部状态</option>
        <option value="firing">FIRING(未恢复)</option>
        <option value="resolved">RESOLVED(已恢复)</option>
      </select>
      <span class="right" />
      <button class="btn btn-ghost btn-sm" @click="loadAll">
        <svg viewBox="0 0 20 20" fill="currentColor" class="w-4 h-4"><path fill-rule="evenodd" d="M4 2a1 1 0 0 1 1 1v2.101a7.002 7.002 0 0 1 11.601 2.566 1 1 0 1 1-1.885.666A5.002 5.002 0 0 0 5.999 7H9a1 1 0 0 1 0 2H4a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1zm.008 9.057a1 1 0 0 1 1.276.61A5.002 5.002 0 0 0 14.001 13H11a1 1 0 1 1 0-2h5a1 1 0 0 1 1 1v5a1 1 0 1 1-2 0v-2.1a7.002 7.002 0 0 1-11.601-2.566 1 1 0 0 1 .61-1.277z" clip-rule="evenodd"/></svg>
        刷新
      </button>
    </div>

    <!-- M4-5: 顶部 3 张统计卡,验证 P0-5 清理策略按预期执行 -->
    <div class="metric-cards">
      <div class="metric-card">
        <div class="title">alert_history 总行数</div>
        <div><span class="value">{{ totalRows != null ? totalRows.toLocaleString() : '-' }}</span><span class="unit">条</span></div>
        <div class="sub">PG 表当前总行数(实时)</div>
      </div>
      <div class="metric-card">
        <div class="title">累计清理行数</div>
        <div><span class="value">{{ lastPurgedRows != null ? lastPurgedRows.toLocaleString() : '-' }}</span><span class="unit">条/次</span></div>
        <div class="sub">最近一次定时清理删除的行数</div>
      </div>
      <div class="metric-card">
        <div class="title">最近一次清理</div>
        <div><span class="value" style="font-size: 0.95rem;">{{ formatPurgedAt(lastPurgedAt) }}</span></div>
        <div class="sub">每日 03:30 cron 触发,保留期默认 90 天</div>
      </div>
    </div>

    <div class="card bg-base-100 border border-base-300 shadow-sm">
      <div class="card-body p-0">
        <div class="px-4 py-2.5 border-b border-base-300 flex items-center font-medium text-sm">
          <span>告警历史</span>
          <span class="ml-auto text-xs text-muted font-normal">{{ filtered.length }} 条(共 {{ allRows.length }})</span>
        </div>
        <div class="overflow-x-auto">
          <table class="table table-sm table-zebra">
            <thead>
              <tr class="text-secondary">
                <th class="w-12">ID</th>
                <th class="w-36">触发时间</th>
                <th class="w-16">级别</th>
                <th class="w-20">状态</th>
                <th class="w-44">应用</th>
                <th class="w-36">规则</th>
                <th>消息</th>
                <th class="w-16">详情</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="filtered.length === 0">
                <td colspan="8"><EmptyState inline>无告警历史</EmptyState></td>
              </tr>
              <template v-for="r in filtered" :key="r.id">
                <tr>
                  <td>{{ r.id }}</td>
                  <td>{{ formatTime(r.createdAt) }}</td>
                  <td><span :class="['badge-level', r.alertLevel || 'warning']">{{ (r.alertLevel || 'warning').toUpperCase() }}</span></td>
                  <td>
                    <span :class="['badge badge-sm', !r.resolvedAt ? 'badge-error' : 'badge-success']">
                      {{ !r.resolvedAt ? 'FIRING' : 'RESOLVED' }}
                    </span>
                  </td>
                  <td>{{ appLabel(r) }}</td>
                  <td>{{ r.rule ? r.rule.ruleName : '-' }}</td>
                  <td class="truncate max-w-md" :title="r.alertMessage">{{ r.alertMessage || '-' }}</td>
                  <td>
                    <button class="btn btn-ghost btn-xs" @click="toggleDetail(r.id)">{{ expanded[r.id] ? '收起' : '查看' }}</button>
                  </td>
                </tr>
                <tr v-if="expanded[r.id]">
                  <td colspan="8" class="bg-base-200 p-3">
                    <pre class="code !max-h-48">{{ r.notifyResult || r.alertMessage || '(无详情)' }}</pre>
                    <div v-if="r.resolvedAt" class="text-xs text-muted mt-2">恢复时间: {{ formatTime(r.resolvedAt) }}</div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>
