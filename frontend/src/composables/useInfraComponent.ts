import { ref, reactive, computed, onBeforeUnmount, watch } from 'vue'
import { api } from '@/api/client'
import { formatPercent, formatNumber } from '@/utils/format'
import type { LineSeriesItem } from '@/charts/types'

type Range = '5m' | '15m' | '1h' | '6h' | '24h'
type Point = [number, number | null]

/**
 * 基础设施单一组件(InfluxDB / Kafka)的数据获取与轮询。
 * 每个调用方传一个 component 名,内部独立维护状态,30s 自动刷新。
 */
export function useInfraComponent(component: string) {
  const status = ref<any>(null)
  const metrics = ref<string[]>([])
  const internalMeasurements = ref<string[]>([])
  const seriesData = reactive<Record<string, LineSeriesItem[]>>({})
  const latestData = reactive<Record<string, any>>({})
  const range = ref<Range>('15m')
  const rangeOptions: Range[] = ['5m', '15m', '1h', '6h', '24h']
  const loading = ref(false)
  const lastError = ref<string | null>(null)
  const componentError = ref<string | null>(null)

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
  function chartKey(metric: string) {
    return component + '/' + metric
  }

  async function fetchStatus() {
    try { status.value = await api.get('/api/infra/status') } catch (e: any) { lastError.value = e?.message || String(e) }
  }
  async function fetchInternalMeasurements() {
    try {
      const resp: any = await api.get('/api/infra/internal-measurements')
      internalMeasurements.value = resp?.measurements || []
    } catch {}
  }
  async function fetchMetrics() {
    try {
      const resp: any = await api.get('/api/infra/metrics', { component })
      metrics.value = resp?.metrics || []
    } catch (e: any) {
      componentError.value = e?.message || String(e)
      metrics.value = []
    }
  }
  async function fetchSeries(metric: string) {
    try {
      const params = {
        component, metric,
        from: new Date(Date.now() - rangeMs()).toISOString(),
        to: new Date().toISOString(),
        every: everyForRange()
      }
      const resp: any = await api.get('/api/infra/series', params)
      const seriesArr: any[] = resp?.series || []
      if (seriesArr.length === 0) {
        seriesData[chartKey(metric)] = []
        return
      }
      seriesData[chartKey(metric)] = seriesArr.map((s: any) => ({
        name: s.name || metric,
        points: (s.points || []).map((p: any) => [new Date(p.t).getTime(), p.v == null ? null : Number(p.v)] as Point)
      }))
    } catch {
      seriesData[chartKey(metric)] = []
    }
  }
  async function fetchLatest(metric: string) {
    try {
      const resp: any = await api.get('/api/infra/latest', { component, metric })
      latestData[chartKey(metric)] = resp && Object.keys(resp).length ? resp : null
    } catch {
      latestData[chartKey(metric)] = null
    }
  }

  function pickPriorityMetrics(all: string[]): string[] {
    if (!all || all.length === 0) return []
    const order = [
      'tsm1_engine.cacheSizeBytes', 'tsm1_wal.size',
      'go_memstats.heapInuseBytes', 'go_memstats.heapAllocBytes', 'go_memstats.sysBytes', 'go_memstats.heapObjects',
      'go_runtime.Goroutines', 'go_goroutines',
      'go_gc_duration_seconds.count', 'go_gc_duration_seconds.sum',
      'storage.shards', 'storage.series',
      'query_control.activeQueries', 'httpd.activeConnections',
      'write.pointsWritten', 'write.writeOk', 'write.writeError',
      'tsm1_engine.compactionDurationSeconds'
    ]
    const out: string[] = []
    for (const o of order) if (all.includes(o)) out.push(o)
    for (const m of all) if (!out.includes(m)) out.push(m)
    return out.slice(0, 12)
  }

  function isBytesMetric(key: string): boolean {
    return /heap|mem|bytes|cache|Size|WAL/.test(key)
  }

  function fmtBytes(v: number): string {
    const mb = v / 1048576
    if (mb >= 1024) return (mb / 1024).toFixed(2) + ' GB'
    return mb.toFixed(1) + ' MB'
  }

  function toMbPoints(series: LineSeriesItem[], key: string): LineSeriesItem[] {
    if (!isBytesMetric(key)) return series
    return series.map((s) => ({
      ...s,
      points: (s.points as Point[]).map(([t, v]) => [t, v == null ? null : (v as number) / 1048576] as Point)
    }))
  }

  function fmtValue(v: any, key: string): string {
    if (v == null) return '-'
    if (typeof v === 'string') return v
    if (isBytesMetric(key)) return fmtBytes(v as number)
    if (key.includes('pct') || key.includes('percent')) return formatPercent((v as number) / 100, 1)
    return formatNumber(v as number)
  }

  async function refreshSeriesOnly() {
    const priority = pickPriorityMetrics(metrics.value)
    await Promise.all(priority.flatMap((m) => [fetchSeries(m), fetchLatest(m)]))
  }

  async function refresh() {
    loading.value = true
    componentError.value = null
    try {
      await Promise.all([fetchStatus(), fetchInternalMeasurements(), fetchMetrics()])
      await refreshSeriesOnly()
    } catch (e: any) {
      componentError.value = e?.message || String(e)
    } finally {
      loading.value = false
    }
  }

  let timer: number | null = null
  function startPolling() {
    if (timer) clearInterval(timer)
    refresh()
    timer = window.setInterval(refresh, 30000)
  }
  function stopPolling() {
    if (timer) { clearInterval(timer); timer = null }
  }

  const lastUpdateLabel = computed(() => {
    if (!status.value) return '-'
    const t = status.value.lastSuccessEpochMs
    if (!t) return '未采集'
    return new Date(t).toLocaleTimeString('zh-CN', { hour12: false })
  })

  watch(range, () => {
    refreshSeriesOnly()
  })

  onBeforeUnmount(() => stopPolling())

  return {
    component,
    status, metrics, internalMeasurements, seriesData, latestData,
    range, rangeOptions, loading, lastError, componentError,
    refresh, startPolling, stopPolling,
    lastUpdateLabel,
    chartKey, pickPriorityMetrics, toMbPoints, fmtValue
  }
}
