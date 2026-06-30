import { api } from '@/api/client'
import type { MetricRow, SeriesItem, GroupItem, QuantilePoint } from '@/composables/metrics'

/**
 * 应用指标 batch 查询的 spec 类型,对应后端 /api/metrics/batch。
 * 每个 pane 按 view 构造一组 specs,后端并发查 InfluxDB,合并返回。
 */
export type AppViewSpecType = 'latest' | 'series' | 'grouped' | 'histogram-quantile'

export interface AppViewSpec {
  key: string
  type: AppViewSpecType
  metric: string
  tagFilters?: Record<string, string>
  agg?: string
  every?: string
  groupBy?: string
  quantiles?: string
}

export interface AppViewBatchResponse {
  appid: number | string
  from: string
  to: string
  results: Record<string, unknown>
  errors: string[]
  elapsedMs: number
}

/** 一次 batch 请求,后端并发查 InfluxDB 后合并。 */
export async function fetchAppViewBatch(
  appid: string,
  specs: AppViewSpec[],
  opts: { from?: string; to?: string; every?: string } = {}
): Promise<AppViewBatchResponse> {
  const body: any = { specs }
  if (opts.from) body.from = opts.from
  if (opts.to) body.to = opts.to
  if (opts.every) body.every = opts.every
  return api.post<AppViewBatchResponse>('/api/metrics/batch', body, { appid })
}

/** 辅助:从 batch 结果里取 latest 的 rows。 */
export function latestRows<T = MetricRow>(resp: AppViewBatchResponse | null | undefined, key: string): T[] {
  const r = resp?.results?.[key] as { rows?: T[] } | undefined
  return (r?.rows ?? []) as T[]
}

/** 辅助:从 batch 结果里取 series 数组。 */
export function seriesItems<T = SeriesItem>(resp: AppViewBatchResponse | null | undefined, key: string): T[] {
  const r = resp?.results?.[key] as { series?: T[] } | undefined
  return (r?.series ?? []) as T[]
}

/** 辅助:从 batch 结果里取 grouped groups。 */
export function groupedItems<T = GroupItem>(resp: AppViewBatchResponse | null | undefined, key: string): T[] {
  const r = resp?.results?.[key] as { groups?: T[] } | undefined
  return (r?.groups ?? []) as T[]
}

/** 辅助:从 batch 结果里取 histogram-quantile points。 */
export function quantilePoints<T = QuantilePoint>(resp: AppViewBatchResponse | null | undefined, key: string): T[] {
  const r = resp?.results?.[key] as { points?: T[] } | undefined
  return (r?.points ?? []) as T[]
}

/** 构造 spec 的简短工厂,view 列表写起来更紧凑。 */
export const S = {
  l: (key: string, metric: string, tagFilters?: Record<string, string>): AppViewSpec => ({
    key, type: 'latest', metric, tagFilters
  }),
  s: (key: string, metric: string, tagFilters: Record<string, string> | undefined, agg: string, every: string): AppViewSpec => ({
    key, type: 'series', metric, tagFilters, agg, every
  }),
  g: (key: string, metric: string, groupBy: string, agg: string): AppViewSpec => ({
    key, type: 'grouped', metric, groupBy, agg
  }),
  q: (key: string, metric: string, tagFilters?: Record<string, string>, quantiles = '0.5,0.95,0.99', every = '30s'): AppViewSpec => ({
    key, type: 'histogram-quantile', metric, tagFilters, quantiles, every
  })
}

// ============= 各 view 的 spec 工厂函数 =============

/** JDBC view: 11 latest + 4 series + 3 quantile + 3 series(count) */
export function jdbcViewSpecs(): AppViewSpec[] {
  return [
    S.l('card_max',       'db_client_connections_max'),
    S.l('card_min',       'db_client_connections_idle_min'),
    S.l('card_idle',      'db_client_connections_usage', { state: 'idle' }),
    S.l('card_used',      'db_client_connections_usage', { state: 'used' }),
    S.l('card_pend',      'db_client_connections_pending_requests'),
    S.l('card_use_sum',   'db_client_connections_use_time_milliseconds_sum'),
    S.l('card_use_cnt',   'db_client_connections_use_time_milliseconds_count'),
    S.l('card_wait_sum',  'db_client_connections_wait_time_milliseconds_sum'),
    S.l('card_wait_cnt',  'db_client_connections_wait_time_milliseconds_count'),
    S.l('card_create_sum','db_client_connections_create_time_milliseconds_sum'),
    S.l('card_create_cnt','db_client_connections_create_time_milliseconds_count'),

    S.s('conn_idle', 'db_client_connections_usage',                   { state: 'idle' }, 'mean', '30s'),
    S.s('conn_used', 'db_client_connections_usage',                   { state: 'used' }, 'mean', '30s'),
    S.s('pending',   'db_client_connections_pending_requests',        {},                'mean', '30s'),
    S.s('qps',       'db_client_connections_use_time_milliseconds_count', {},           'rate', '30s'),

    S.q('q_use',    'db_client_connections_use_time_milliseconds',     {}),
    S.q('q_wait',   'db_client_connections_wait_time_milliseconds',    {}),
    S.q('q_create', 'db_client_connections_create_time_milliseconds',  {}),

    S.s('d_use',    'db_client_connections_use_time_milliseconds_count',  {}, 'mean', '30s'),
    S.s('d_wait',   'db_client_connections_wait_time_milliseconds_count', {}, 'mean', '30s'),
    S.s('d_create', 'db_client_connections_create_time_milliseconds_count',{},'mean', '30s')
  ]
}

/** JVM view: 6 latest + 3 series + 2 latest + 3 grouped + 1 quantile + 1 latest + 1 grouped + 2 series */
export function jvmViewSpecs(): AppViewSpec[] {
  return [
    S.l('card_cpu',     'jvm_cpu_recent_utilization'),
    S.l('card_cls_cur', 'jvm_class_count'),
    S.l('card_cls_load','jvm_class_loaded_total'),
    S.l('card_cls_unld','jvm_class_unloaded_total'),
    S.l('card_threads', 'jvm_thread_count'),
    S.l('card_cpu_time','jvm_cpu_time_seconds_total'),

    S.s('heap',          'jvm_memory_used_bytes',     { jvm_memory_type: 'heap' },     'mean', '30s'),
    S.s('nonheap',       'jvm_memory_used_bytes',     { jvm_memory_type: 'non_heap' }, 'mean', '30s'),
    S.s('heap_committed','jvm_memory_committed_bytes',{ jvm_memory_type: 'heap' },     'mean', '30s'),
    S.l('mem_used',      'jvm_memory_used_bytes'),
    S.l('mem_limit',     'jvm_memory_limit_bytes'),

    S.g('gc_count', 'jvm_gc_duration_seconds_count', 'jvm_gc_name,jvm_gc_action', 'last'),
    S.g('gc_sum',   'jvm_gc_duration_seconds_sum',   'jvm_gc_name',               'last'),
    S.g('gc_cnt',   'jvm_gc_duration_seconds_count', 'jvm_gc_name',               'last'),
    S.q('gc_quantile', 'jvm_gc_duration_seconds', {}),

    S.l('after_gc', 'jvm_memory_used_after_last_gc_bytes'),
    S.g('thread_state', 'jvm_thread_count', 'jvm_thread_state,jvm_thread_daemon', 'last'),

    S.s('cls_load_rate',   'jvm_class_loaded_total',   {}, 'rate', '30s'),
    S.s('cls_unload_rate', 'jvm_class_unloaded_total', {}, 'rate', '30s')
  ]
}

/** OS view: 4 latest + 4 series + 1 latest + 5 grouped */
export function osViewSpecs(): AppViewSpec[] {
  return [
    S.l('card_cpu', 'jvm_cpu_count'),
    S.l('card_mem', 'system_memory_utilization', { state: 'used' }),
    S.l('card_rss', 'runtime_java_memory_bytes', { type: 'rss' }),
    S.l('card_vms', 'runtime_java_memory_bytes', { type: 'vms' }),

    S.s('mem_used', 'system_memory_usage_bytes',           { state: 'used' },  'mean', '30s'),
    S.s('mem_free', 'system_memory_usage_bytes',           { state: 'free' },  'mean', '30s'),
    S.s('cpu_user', 'runtime_java_cpu_time_milliseconds',  { type: 'user' },   'mean', '30s'),
    S.s('cpu_sys',  'runtime_java_cpu_time_milliseconds',  { type: 'system' }, 'mean', '30s'),

    S.l('mem_pie', 'system_memory_usage_bytes'),

    S.g('disk_io',   'system_disk_io_bytes_total',      'device,direction', 'rate'),
    S.g('disk_iops', 'system_disk_operations_total',    'device,direction', 'rate'),
    S.g('net_io',    'system_network_io_bytes_total',   'device,direction', 'rate'),
    S.g('net_pkts',  'system_network_packets_total',    'device,direction', 'rate'),
    S.g('net_errs',  'system_network_errors_total',     'device,direction', 'rate')
  ]
}

/** HTTP view 第一阶段(overview + 路由列表): 3 grouped + 1 grouped + 1 quantile + 1 grouped(route detail) */
export function httpOverviewSpecs(): AppViewSpec[] {
  return [
    S.g('grp_route',    'http_server_request_duration_seconds_count', 'http_route',                'last'),
    S.g('grp_status',   'http_server_request_duration_seconds_count', 'http_response_status_code', 'last'),
    S.g('grp_method',   'http_server_request_duration_seconds_count', 'http_request_method',        'last'),
    S.g('grp_p99',      'http_server_request_duration_seconds_sum',   'http_route',                'last'),
    S.q('rt_quantile',  'http_server_request_duration_seconds', {}),
    S.g('route_detail', 'http_server_request_duration_seconds_count', 'http_request_method,http_response_status_code,http_route', 'last')
  ]
}

/**
 * HTTP view 第二阶段(per-route): 输入路由列表,生成 4 specs/route。
 * 合并成一个 batch call,不再 N*4 个 Promise.all。
 */
export function httpRouteSpecs(
  routes: Array<{ method: string; status: string; route: string }>
): AppViewSpec[] {
  const specs: AppViewSpec[] = []
  routes.forEach((r, i) => {
    const tags = { http_request_method: r.method, http_response_status_code: r.status, http_route: r.route }
    specs.push(S.l(`r${i}_count`, 'http_server_request_duration_seconds_count', tags))
    specs.push(S.l(`r${i}_sum`,   'http_server_request_duration_seconds_sum',   tags))
    specs.push(S.q(`r${i}_q`,     'http_server_request_duration_seconds',        tags))
    specs.push(S.s(`r${i}_qps`,   'http_server_request_duration_seconds_count', tags, 'rate', '30s'))
  })
  return specs
}
