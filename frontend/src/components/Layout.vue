<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { useRouter, useRoute, RouterLink, RouterView } from 'vue-router'
import { useAppStore, type AppInfo } from '@/stores/app'
import { api } from '@/api/client'
import { useToast } from '@/utils/toast'
import ToastHost from '@/components/ToastHost.vue'

const router = useRouter()
const route = useRoute()
const appStore = useAppStore()
const toast = useToast()

const now = ref('')
let timer: number | null = null
function tick() {
  now.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
}

onMounted(() => {
  tick()
  timer = window.setInterval(tick, 1000)
  appStore.loadFromQuery()
  refreshApps()
  window.addEventListener('appid-changed', refreshApps)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  window.removeEventListener('appid-changed', refreshApps)
})

async function refreshApps() {
  try {
    const apps = await api.get<AppInfo[]>('/api/apps/active')
    appStore.setApps(apps || [])
  } catch (e: any) {
    toast.error('应用列表加载失败: ' + e.message)
  }
}

const navItems = [
  { path: '/app-detail', label: '应用指标', icon: 'M3 13h4v8H3zM10 3h4v18h-4zM17 8h4v13h-4z' },
  { path: '/logs', label: '日志分析', icon: 'M4 4h16v3H4zM4 10h16v3H4zM4 16h10v3H4z' },
  { path: '/alert-rules', label: '告警规则', icon: 'M12 2 1 21h22zM12 9v5M12 17v.5' },
  { path: '/alert-history', label: '告警历史', icon: 'M4 5h16M4 10h16M4 15h12M4 20h8' },
  { path: '/email-config', label: '邮箱配置', icon: 'M3 5h18v14H3zM3 5l9 7 9-7' },
  { path: '/self-monitor', label: '自身监控', icon: 'M12 2l3 6 6 1-4.5 4.5L18 20l-6-3-6 3 1.5-6.5L3 9l6-1z' },
  { path: '/apps', label: '监控应用', icon: 'M12 2l9 4-9 4-9-4zM3 12l9 4 9-4M3 18l9 4 9-4' }
]

function isActive(path: string): boolean {
  return route.path === path || route.path.startsWith(path + '/')
}

function onAppChange(e: Event) {
  const val = (e.target as HTMLSelectElement).value
  appStore.setAppid(val || null)
  window.dispatchEvent(new CustomEvent('appid-changed', { detail: { appid: val || null } }))
  if (isActive('/app-detail') || isActive('/logs')) {
    const q = val ? { appid: val } : {}
    router.replace({ path: route.path, query: q })
  }
}
</script>

<template>
  <div class="app-shell">
    <header class="app-topbar bg-gradient-to-r from-slate-800 to-slate-600 shadow-md">
      <h1>spring-watch</h1>
      <span class="spacer" />
      <span class="label">应用</span>
      <select
        class="select select-sm bg-white/10 text-white border-white/20 focus:outline-none focus:border-white/40 ml-3"
        :value="appStore.currentAppid || ''"
        @change="onAppChange"
      >
        <option value="" class="text-slate-800">--请选择应用--</option>
        <option v-for="a in appStore.apps" :key="a.appid" :value="String(a.appid)" class="text-slate-800">
          {{ a.appName }} ({{ a.appid }})
        </option>
      </select>
      <span class="clock ml-3">{{ now }}</span>
    </header>
    <div class="shell-body">
      <nav class="app-sidenav bg-base-100 border-r border-base-300">
        <RouterLink
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          :class="{ active: isActive(item.path) }"
        >
          <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path :d="item.icon" />
          </svg>
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>
      <main class="app-main">
        <RouterView v-slot="{ Component }">
          <component :is="Component" />
        </RouterView>
      </main>
    </div>
    <ToastHost />
  </div>
</template>
