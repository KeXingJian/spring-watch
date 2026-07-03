import { ref, computed } from 'vue'
import { api } from '@/api/client'
import type { LineSeriesItem, BarSeriesItem } from '@/charts/types'

export type Range = '5m' | '15m' | '1h' | '6h' | '24h'
export type Agg = 'mean' | 'max' | 'min' | 'sum' | 'last' | 'rate'
export type Point = [number, number | null]
export type FetchOpts = {
  category: 'jvm' | 'process' | 'meter'
  metric: string
  meterType?: string
  gcName?: string
  agg: Agg
  every?: string
  field?: string
}

/**
 * 自监控页共享状态(单例):range/pollSec/realtime/appCount + 拉 series 的工具方法。
 * 多个 Pane 共用同一份 range/pollSec 配置;realtime 由 SelfMonitorView 主动轮询,
 * 供顶部 health pill / meta、OverviewPane cards、ProcessPane 系统资源、MetersPane 原始表共用。
 * Pane 自身图表数据由各 Pane 独立按需拉取,不在此处集中。
 */
const range = ref<Range>('5m')
const rangeOptions: Range[] = ['5m', '15m', '1h', '6h', '24h']
const pollSec = ref<number>(30)
const pollOptions = [30, 60] as const

const realtime = ref<any>(null)
const appCount = ref<number | null>(null)

function rangeMs() {
  if (range.value === '5m') return 5 * 60 * 1000
  if (range.value === '15m') return 15 * 60 * 1000
  if (range.value === '6h') return 6 * 3600 * 1000
  if (range.value === '24h') return 24 * 3600 * 1000
  return 3600 * 1000
}

function everyForRange() {
  if (range.value === '6h') return '30s'
  if (range.value === '24h') return '1m'
  return '10s'
}

const rangeStartTs = computed(() => Date.now() - rangeMs())
const rangeEndTs = computed(() => Date.now())

/**
 * kxj: fetchSeries 每次都按"现在"算时间窗,不能用上面的 computed -
 * Vue 的 computed 没有响应式依赖时只算一次(Date.now() 不被追踪),
 * 会导致轮询时 from/to 一直是页面打开瞬间的固定值,新数据永远查不到,必须重启。
 */
function nowRange() {
  return { from: new Date(Date.now() - rangeMs()).toISOString(), to: new Date().toISOString() }
}

/** 拉单个 metric 的时序,失败 / 无数据时返回空数组,前端走 EmptyState。 */
async function fetchSeries(opts: FetchOpts): Promise<Point[]> {
  const { from, to } = nowRange()
  const params: Record<string, unknown> = {
    category: opts.category,
    metric: opts.metric,
    from,
    to,
    agg: opts.agg,
    every: opts.every ?? everyForRange()
  }
  if (opts.meterType) params.meterType = opts.meterType
  if (opts.gcName) params.gcName = opts.gcName
  if (opts.field) params.field = opts.field
  try {
    const resp: any = await api.get('/api/self/series', params)
    const series = resp?.series || []
    if (!series.length) return []
    return (series[0].points || []).map((p: any) => [
      new Date(p.t).getTime(),
      p.v == null ? null : Number(p.v)
    ])
  } catch {
    return []
  }
}

async function fetchSeriesMulti(opts: FetchOpts): Promise<LineSeriesItem[]> {
  const { from, to } = nowRange()
  const params: Record<string, unknown> = {
    category: opts.category,
    metric: opts.metric,
    from,
    to,
    agg: opts.agg,
    every: opts.every ?? everyForRange()
  }
  if (opts.meterType) params.meterType = opts.meterType
  if (opts.gcName) params.gcName = opts.gcName
  try {
    const resp: any = await api.get('/api/self/series', params)
    const series = resp?.series || []
    return series.map((s: any) => ({
      name: s.name || opts.metric,
      points: (s.points || []).map((p: any) => [new Date(p.t).getTime(), p.v == null ? null : Number(p.v)])
    }))
  } catch {
    return []
  }
}

async function pollRealtimeOnce() {
  try {
    const resp: any = await api.get('/api/self/realtime')
    realtime.value = resp?.sample || null
    if (appCount.value == null) {
      try {
        const res = await api.pageFull<any>('/api/apps/active', { size: 1 })
        appCount.value = res.total
      } catch {
        appCount.value = 0
      }
    }
  } catch {
    /* 静默 */
  }
}

/** 把 Point 序列中 null / NaN 过滤后取平均;全空返回 0。 */
function meanRate(points: Point[]): number {
  let sum = 0
  let n = 0
  for (const [, v] of points) {
    if (v == null || isNaN(v as number)) continue
    sum += v as number
    n++
  }
  return n === 0 ? 0 : sum / n
}

/** 把多个 rate 时序列成"按调用类型分桶"横向柱图;全 0 触发 EmptyState。 */
function buildQpsBar(labels: string[], series: Point[][]): { categories: string[]; series: BarSeriesItem[] } {
  const data = series.map(meanRate)
  if (data.every((v) => v === 0)) return { categories: [], series: [] }
  return { categories: labels, series: [{ name: 'QPS', data }] }
}

function toMb([t, v]: Point): Point {
  return [t, v == null ? null : (v as number) / 1048576]
}
function toPercent([t, v]: Point): Point {
  return [t, v == null ? null : (v as number) * 100]
}
function pack(points: Point[]): [number, number | null][] {
  return points.map(([t, v]) => [t, v])
}
function gcLabel(seriesName: string): string {
  const m = /\{gc_name=([^}]*)\}/.exec(seriesName || '')
  return m ? m[1] : seriesName
}
function poolLabel(seriesName: string): string {
  const m = /\{pool_name=([^}]*)\}/.exec(seriesName || '')
  return m ? m[1] : seriesName
}

export function useSelfMonitor() {
  return {
    range, rangeOptions,
    pollSec, pollOptions,
    realtime, appCount,
    rangeMs, everyForRange,
    rangeStartTs, rangeEndTs,
    fetchSeries, fetchSeriesMulti,
    pollRealtimeOnce,
    meanRate, buildQpsBar,
    toMb, toPercent, pack,
    gcLabel, poolLabel
  }
}
