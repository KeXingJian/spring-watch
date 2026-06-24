<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { useToast } from '@/utils/toast'
import { api } from '@/api/client'
import { formatTime } from '@/utils/format'
import EmptyState from '@/components/EmptyState.vue'
import JdbcPane from '@/views/appdetail/JdbcPane.vue'
import HttpPane from '@/views/appdetail/HttpPane.vue'
import JvmPane from '@/views/appdetail/JvmPane.vue'
import OsPane from '@/views/appdetail/OsPane.vue'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const toast = useToast()

const tabs = ['jdbc', 'http', 'jvm', 'os'] as const
type Tab = (typeof tabs)[number]
const activeTab = ref<Tab>('jdbc')

const rangeSec = ref(900)
const appid = computed<string | null>(() => {
  const q = route.query.appid
  if (q) return String(q)
  return appStore.currentAppid
})

const appInfo = ref<any>(null)

async function loadAppInfo() {
  if (!appid.value) {
    appInfo.value = null
    return
  }
  try {
    let app: any = null
    try {
      app = await api.get('/api/apps/by-appid/' + appid.value)
    } catch {
      try {
        const apps = await api.get<any[]>('/api/apps?appid=' + appid.value)
        app = Array.isArray(apps) ? apps[0] : null
      } catch {
        /* ignore */
      }
    }
    appInfo.value = app
  } catch {
    /* ignore */
  }
}

const statusBadge = computed(() => {
  const s = appInfo.value?.status
  if (!s) return null
  return s === 'active' ? 'badge-success' : 'badge-ghost'
})

let timer: number | null = null
function startPolling() {
  if (timer) clearInterval(timer)
  timer = window.setInterval(() => {
    window.dispatchEvent(new CustomEvent('appdetail-refresh', { detail: { appid: appid.value, tab: activeTab.value, rangeSec: rangeSec.value } }))
  }, 30000)
}

onMounted(() => {
  loadAppInfo()
  startPolling()
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})

watch(appid, () => {
  loadAppInfo()
})

watch(activeTab, () => {
  window.dispatchEvent(new CustomEvent('appdetail-refresh', { detail: { appid: appid.value, tab: activeTab.value, rangeSec: rangeSec.value } }))
})

watch(rangeSec, () => {
  window.dispatchEvent(new CustomEvent('appdetail-refresh', { detail: { appid: appid.value, tab: activeTab.value, rangeSec: rangeSec.value } }))
})

function onAppidChange(e: Event) {
  const val = (e.target as HTMLSelectElement).value
  appStore.setAppid(val || null)
  const q = val ? { appid: val } : {}
  router.replace({ path: route.path, query: q })
  window.dispatchEvent(new CustomEvent('appid-changed', { detail: { appid: val || null } }))
}

const rangeOptions = [
  { sec: 300, label: '5m' },
  { sec: 900, label: '15m' },
  { sec: 3600, label: '1h' },
  { sec: 21600, label: '6h' },
  { sec: 86400, label: '24h' }
]

function manualRefresh() {
  window.dispatchEvent(new CustomEvent('appdetail-refresh', { detail: { appid: appid.value, tab: activeTab.value, rangeSec: rangeSec.value } }))
}
</script>

<template>
  <div class="page">
    <div class="card bg-base-100 border border-base-300 shadow-sm mb-4">
      <div class="card-body p-3 px-4 app-info-bar">
        <span><span class="label">应用:</span><span class="value">{{ appInfo?.appName || (appid ? '-' : '未选择') }}</span></span>
        <span><span class="label">appid:</span><span class="value">{{ appid || '-' }}</span></span>
        <span><span class="label">endpoint:</span><span class="value">{{ appInfo?.endpoint || '(未配置)' }}</span></span>
        <span>
          <span class="label">状态:</span>
          <span v-if="statusBadge" :class="['badge badge-sm', statusBadge]">{{ appInfo.status }}</span>
          <span v-else>-</span>
        </span>
        <span><span class="label">最近心跳:</span><span class="value">{{ appInfo?.lastHeartbeat ? formatTime(appInfo.lastHeartbeat) : '无' }}</span></span>
        <span class="ml-auto">
          <select class="select select-bordered select-sm" :value="appid || ''" @change="onAppidChange">
            <option value="">切换应用…</option>
            <option v-for="a in appStore.apps" :key="a.appid" :value="String(a.appid)">
              {{ a.appName }} ({{ a.appid }})
            </option>
          </select>
        </span>
      </div>
    </div>

    <div v-if="!appid">
      <EmptyState icon="<svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.5'><circle cx='11' cy='11' r='7'/><path d='M21 21l-4.3-4.3'/></svg>">请先在顶栏选择应用</EmptyState>
    </div>
    <template v-else>
      <div class="tabs">
        <div v-for="t in tabs" :key="t" class="tab" :class="{ active: activeTab === t }" @click="activeTab = t">
          {{ ({ jdbc: 'JDBC', http: 'HTTP', jvm: 'JVM', os: 'OS' } as any)[t] }}
        </div>
        <span class="spacer" />
        <div class="right-tools">
          <div class="join">
            <button
              v-for="r in rangeOptions"
              :key="r.sec"
              class="btn btn-sm join-item"
              :class="rangeSec === r.sec ? 'btn-primary' : 'btn-ghost'"
              @click="rangeSec = r.sec"
            >{{ r.label }}</button>
          </div>
          <button class="btn btn-ghost btn-sm" @click="manualRefresh">
            <svg viewBox="0 0 20 20" fill="currentColor" class="w-4 h-4"><path fill-rule="evenodd" d="M4 2a1 1 0 0 1 1 1v2.101a7.002 7.002 0 0 1 11.601 2.566 1 1 0 1 1-1.885.666A5.002 5.002 0 0 0 5.999 7H9a1 1 0 0 1 0 2H4a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1zm.008 9.057a1 1 0 0 1 1.276.61A5.002 5.002 0 0 0 14.001 13H11a1 1 0 1 1 0-2h5a1 1 0 0 1 1 1v5a1 1 0 1 1-2 0v-2.1a7.002 7.002 0 0 1-11.601-2.566 1 1 0 0 1 .61-1.277z" clip-rule="evenodd"/></svg>
            刷新
          </button>
        </div>
      </div>

      <div v-show="activeTab === 'jdbc'">
        <JdbcPane :appid="appid" :range-sec="rangeSec" />
      </div>
      <div v-show="activeTab === 'http'">
        <HttpPane :appid="appid" :range-sec="rangeSec" />
      </div>
      <div v-show="activeTab === 'jvm'">
        <JvmPane :appid="appid" :range-sec="rangeSec" />
      </div>
      <div v-show="activeTab === 'os'">
        <OsPane :appid="appid" :range-sec="rangeSec" />
      </div>
    </template>
  </div>
</template>
