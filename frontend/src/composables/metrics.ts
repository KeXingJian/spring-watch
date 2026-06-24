import { ref, onMounted, onBeforeUnmount, watch, type Ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '@/api/client'
import { useAppStore } from '@/stores/app'

export interface MetricRow {
  tags?: Record<string, string>
  value: number | null
  time?: string
}

export interface LatestResp {
  rows?: MetricRow[]
}

export interface SeriesItem {
  name: string
  points: { t: string; v: number | null }[]
}

export interface SeriesResp {
  series?: SeriesItem[]
}

export interface GroupItem {
  tags?: Record<string, string>
  group?: string
  value: number
}

export interface GroupedResp {
  groups?: GroupItem[]
}

export interface QuantilePoint {
  t: string
  q50?: number | null
  q95?: number | null
  q99?: number | null
  tags?: Record<string, string>
}

export interface QuantileResp {
  points?: QuantilePoint[]
}

export function useAppidFromRoute(): Ref<string | null> {
  const appStore = useAppStore()
  const route = useRoute()
  const appid = ref<string | null>(null)

  function sync() {
    const q = route.query.appid
    if (q) {
      appid.value = String(q)
    } else if (appStore.currentAppid) {
      appid.value = appStore.currentAppid
    } else {
      appid.value = null
    }
  }

  sync()
  onMounted(() => {
    sync()
    window.addEventListener('appid-changed', sync as EventListener)
  })
  onBeforeUnmount(() => {
    window.removeEventListener('appid-changed', sync as EventListener)
  })
  watch(() => route.query.appid, sync)
  watch(() => appStore.currentAppid, sync)

  return appid
}

export function useFromToRange(rangeSec: Ref<number>) {
  function fromTo() {
    const to = new Date()
    const from = new Date(to.getTime() - rangeSec.value * 1000)
    return { from: from.toISOString(), to: to.toISOString() }
  }
  return { fromTo }
}

export function pickRowValue(rows: MetricRow[] | undefined, tagFilters?: Record<string, unknown>): number | null {
  if (!rows || rows.length === 0) return null
  if (!tagFilters) return rows[0].value ?? null
  for (const row of rows) {
    const tags = row.tags || {}
    let match = true
    for (const k in tagFilters) {
      if (String(tags[k] || '') !== String(tagFilters[k])) {
        match = false
        break
      }
    }
    if (match) return row.value ?? null
  }
  return rows[0].value ?? null
}

export function flattenPoints(seriesList: SeriesItem[] | undefined): [string, number | null][] {
  if (!Array.isArray(seriesList) || seriesList.length === 0) return []
  if (seriesList.length === 1) {
    return (seriesList[0].points || []).map((p) => [p.t, p.v] as [string, number | null])
  }
  const map = new Map<string, number | null>()
  for (const s of seriesList) {
    for (const p of s.points || []) {
      if (p.t != null && !map.has(p.t)) map.set(p.t, p.v)
    }
  }
  return Array.from(map.entries())
    .map(([t, v]) => [t, v] as [string, number | null])
    .sort((a, b) => a[0].localeCompare(b[0]))
}

export function extractTagValue(seriesName: string | null | undefined, tagKey: string): string | null {
  if (!seriesName) return null
  const m = seriesName.match(new RegExp(tagKey + '=([^,}]+)'))
  return m ? m[1] : null
}

export const POOL_NAME_CN: Record<string, string> = {
  "CodeHeap 'non-nmethods'": '非方法',
  "CodeHeap 'non-profiled nmethods'": '非采样方法',
  "CodeHeap 'profiled nmethods'": '采样方法',
  'Compressed Class Space': '压缩类空间',
  Metaspace: '元空间',
  'G1 Eden Space': 'G1 Eden 区',
  'G1 Old Gen': 'G1 老年代',
  'G1 Survivor Space': 'G1 存活区',
  CodeCache: '代码缓存'
}

export function translatePoolName(name: string | null | undefined): string {
  if (!name) return name || ''
  return POOL_NAME_CN[name] || name
}

export async function fetchSeries(
  appid: string,
  metric: string,
  tagFilters: Record<string, unknown> | undefined,
  from: string,
  to: string,
  every = '30s'
): Promise<SeriesItem[]> {
  try {
    const r = await api.get<SeriesResp>('/api/metrics/series', {
      appid,
      metric,
      from,
      to,
      every,
      ...(tagFilters || {})
    })
    return r?.series || []
  } catch (e) {
    console.warn('series fetch failed', metric, e)
    return []
  }
}
