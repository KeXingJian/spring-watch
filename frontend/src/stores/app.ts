import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface AppInfo {
  id: number
  appid: number
  appName: string
  endpoint?: string
  status?: string
}

const STORAGE_KEY = 'spring_watch.currentAppid'

function isValidAppid(v: unknown): v is string {
  if (typeof v !== 'string') return false
  return /^\d+$/.test(v) && v !== '0'
}

export const useAppStore = defineStore('app', () => {
  const currentAppid = ref<string | null>(null)
  const apps = ref<AppInfo[]>([])
  const loaded = ref(false)

  function loadFromQuery() {
    const m = window.location.search.match(/[?&]appid=(\d+)/)
    if (m) {
      currentAppid.value = m[1]
      localStorage.setItem(STORAGE_KEY, m[1])
      return
    }
    const stored = localStorage.getItem(STORAGE_KEY)
    if (isValidAppid(stored)) currentAppid.value = stored
    else if (stored) localStorage.removeItem(STORAGE_KEY)
  }

  function setAppid(appid: string | number | null) {
    if (appid == null || appid === '' || !isValidAppid(String(appid))) {
      localStorage.removeItem(STORAGE_KEY)
      currentAppid.value = null
    } else {
      const s = String(appid)
      localStorage.setItem(STORAGE_KEY, s)
      currentAppid.value = s
    }
  }

  function setApps(list: AppInfo[]) {
    apps.value = list
    loaded.value = true
  }

  return { currentAppid, apps, loaded, loadFromQuery, setAppid, setApps }
})
