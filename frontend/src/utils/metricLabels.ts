/**
 * 指标中文名映射:把后端 / 基础设施的英文指标名翻译成 UI 上更直观的中文词条。
 * 未命中时返回原 metric 名,不抛错、不强行翻译。
 *
 * 维护原则:
 * - spring.watch.* 走 SPRING_WATCH_LABELS,跟 SelfMonitorView 的 groupOf 保持同源。
 * - InfluxDB / Kafka exporter 出来的内建指标(go_、tsm1_、storage、write 等前缀)走 INFRA_LABELS,
 *   命名约定参看 InfluxData 官方文档(URL: docs.influxdata.com influxdb v2 reference internals metrics)。
 */

export const SPRING_WATCH_LABELS: Record<string, string> = {
  // 指标采集
  'spring.watch.consumer.metric.received': '指标接收条数',
  'spring.watch.consumer.metric.kept': '指标保留条数',
  'spring.watch.consumer.metric.parse_fail': '指标解析失败',
  'spring.watch.consumer.metric.write': '指标写入耗时',
  'spring.watch.consumer.metric.write_fail': '指标写入失败',

  // 日志采集
  'spring.watch.consumer.log.received': '日志接收条数',
  'spring.watch.consumer.log.kept': '日志保留条数',
  'spring.watch.consumer.log.deduped': '日志去重条数',
  'spring.watch.consumer.log.parse_fail': '日志解析失败',
  'spring.watch.consumer.log.alert_candidate': '日志告警候选',
  'spring.watch.consumer.log.write': '日志写入耗时',
  'spring.watch.consumer.log.write_fail': '日志写入失败',

  // DLQ
  'spring.watch.consumer.dlq.persisted': 'DLQ 落库条数',
  'spring.watch.consumer.dlq.persist_fail': 'DLQ 落库失败',

  // 指标查询
  'spring.watch.metric.query.latest': '最新值查询',
  'spring.watch.metric.query.series': '时序查询',
  'spring.watch.metric.query.grouped': '分组查询',
  'spring.watch.metric.query.histogram': '直方图查询',
  'spring.watch.metric.query.fail': '查询失败',
  'spring.watch.metric.query.empty_result': '空结果次数',

  // 日志查询
  'spring.watch.log.query.search': '关键词搜索',
  'spring.watch.log.query.patterns': '模式统计',
  'spring.watch.log.query.levels': '级别统计',
  'spring.watch.log.query.trace': '链路追踪',
  'spring.watch.log.query.context': '上下文查询',
  'spring.watch.log.query.dedup_top': '去重 Top',
  'spring.watch.log.query.fail': '查询失败',
  'spring.watch.log.query.search.latency': '搜索耗时',
  'spring.watch.log.query.patterns.latency': '模式统计耗时',
  'spring.watch.log.query.levels.latency': '级别统计耗时',
  'spring.watch.log.query.trace.latency': '链路追踪耗时',
  'spring.watch.log.query.context.latency': '上下文查询耗时',
  'spring.watch.log.query.dedup_top.latency': '去重 Top 耗时',

  // 日志聚合
  'spring.watch.aggregator.log.error_rate.latency': '错误率计算耗时',
  'spring.watch.aggregator.log.error_rate.query': '错误率查询',
  'spring.watch.aggregator.log.error_rate_series.latency': '错误率时序计算耗时',
  'spring.watch.aggregator.log.error_rate_series.query': '错误率时序查询',
  'spring.watch.aggregator.log.query_fail': '聚合查询失败',
  'spring.watch.aggregator.log.top_patterns.latency': 'Top 模式计算耗时',
  'spring.watch.aggregator.log.top_patterns.query': 'Top 模式查询',

  // 日志去重
  'spring.watch.ingest.log.dedup.keep': '去重保留条数',
  'spring.watch.ingest.log.dedup.drop': '去重丢弃条数',
  'spring.watch.ingest.log.dedup.flush': '去重刷盘条数',
  'spring.watch.ingest.log.dedup.flush_fail': '去重刷盘失败',

  // 日志指纹
  'spring.watch.ingest.log.fingerprint.digest.reused': '摘要复用次数',

  // HTTP 抓取
  'spring.watch.collector.http.active': '活跃抓取',
  'spring.watch.collector.http.success': '抓取成功',
  'spring.watch.collector.http.failure': '抓取失败',
  'spring.watch.collector.http.timeout': '抓取超时',
  'spring.watch.collector.http.non2xx': '非 2xx 响应',
  'spring.watch.collector.http.body.rejected': '响应体超限',
  'spring.watch.collector.http.request': 'HTTP 请求耗时',
  'spring.watch.collector.http.request.percentile': 'HTTP 请求分位数',

  // 重投队列
  'spring.watch.collector.retry.queue.size': '重投队列长度',
  'spring.watch.collector.retry.enqueued': '入队条数',
  'spring.watch.collector.retry.dropped': '丢弃条数',
  'spring.watch.collector.retry.rejected': '拒绝入队',

  // Kafka 兜底
  'spring.watch.collector.kafka.fallback.size': '兜底队列长度',
  'spring.watch.kafka.fallback.queue.size': '兜底队列长度',
  'spring.watch.kafka.fallback.rejected': '兜底被拒',
  'spring.watch.kafka.fallback.stale_dropped': '过期丢弃',
  'spring.watch.kafka.fallback.truncated': '截断丢弃',

  // 主机限流
  'spring.watch.collector.host_throttler.active': '已注册主机',

  // 告警评估
  'spring.watch.alerter.jexl.context.reused': 'JEXL 上下文复用',

  // 告警历史
  'spring.watch.alert.history.total_rows': '历史总条数',
  'spring.watch.alert.history.purged': '清理条数',
  'spring.watch.alert.history.purged.last_rows': '上次清理条数',
  'spring.watch.alert.history.purged.last_at_epoch': '上次清理时间',

  // InfluxDB 写入
  'spring.watch.influxdb.write.queue.size': '写入队列长度',
  'spring.watch.influxdb.write.point.queued': '写入点入队条数',

  // 基础设施采集
  'spring.watch.infra.poll.ok': '采集成功',
  'spring.watch.infra.poll.fail': '采集失败',
  'spring.watch.infra.last_poll_epoch_ms': '上次采集时间',
  'spring.watch.infra.last_success_epoch_ms': '上次成功时间',

  // Kafka lag
  'spring.watch.kafka.lag.poll.ok': 'Lag 采集成功',
  'spring.watch.kafka.lag.poll.fail': 'Lag 采集失败',
  'spring.watch.kafka.lag.last_success_epoch_ms': 'Lag 上次成功',

  // 自监控
  'spring.watch.self.metric.query.latest': '自身指标最新值查询',
  'spring.watch.self.metric.query.latest.fail': '自身指标查询失败',
  'spring.watch.self.metric.query.list': '自身指标列表查询',
  'spring.watch.self.metric.query.list.fail': '自身列表查询失败',
  'spring.watch.self.metric.query.series': '自身时序查询',
  'spring.watch.self.metric.query.series.fail': '自身时序查询失败',
  'spring.watch.self.monitor.capture.captured': '抓取条数',
  'spring.watch.self.monitor.capture.filtered': '过滤条数',
  'spring.watch.self.monitor.persist.ok': '自身指标落库成功',
  'spring.watch.self.monitor.persist.fail': '自身指标落库失败',
  'spring.watch.self.monitor.ring.size': '自身指标环形缓冲长度',

  // JVM
  'spring.watch.jvm.threads.current': '线程数',
  'spring.watch.jvm.threads.daemon': '守护线程',
  'spring.watch.jvm.threads.peak': '线程峰值',
  'spring.watch.jvm.classes.loaded': '已加载类',
  'spring.watch.jvm.g': 'GC 概要',

  // JVM G1 分代(来自 /actuator/metrics 的 jvm.memory.used 拆分)
  'spring.watch.jvm.g1.eden.max': 'G1 Eden 区最大',
  'spring.watch.jvm.g1.eden.used': 'G1 Eden 区已用',
  'spring.watch.jvm.g1.oldgen.max': 'G1 老年代最大',
  'spring.watch.jvm.g1.oldgen.used': 'G1 老年代已用',
  'spring.watch.jvm.g1.oldgen.pct': 'G1 老年代使用率',
  'spring.watch.jvm.g1.survivor.max': 'G1 Survivor 区最大',
  'spring.watch.jvm.g1.survivor.used': 'G1 Survivor 区已用'
}

/**
 * Micrometer Timer/Gauge 在 publishPercentiles / publishMax 开启时会自动衍生
 * `xxx.percentile`、`xxx.max` 这类子 gauge,运行时才有具体 key。
 * 同时 Kafka / InfluxDB 内部指标多用 _bytes / _total / .rate 等后缀。
 * 这里给一份"主干名 → 中文主干 + 通用后缀词"的兜底,让用户新加指标也不至于看到裸英文。
 * 匹配规则:取 tail 末段(以 . / _ 切分)命中下列后缀,优先按最长匹配。
 * 注意:只在主表查不到时才走这里。
 */
const FALLBACK_SUFFIX_LABELS: Array<{ suffix: string; label: string }> = [
  // Spring / Micrometer 风格
  { suffix: '.percentile', label: '分位数' },
  { suffix: '.total_ms', label: '总耗时' },
  { suffix: '.latency', label: '耗时' },
  { suffix: '.received', label: '接收' },
  { suffix: '.enqueued', label: '入队' },
  { suffix: '.rejected', label: '被拒' },
  { suffix: '.dropped', label: '丢弃' },
  { suffix: '.success', label: '成功' },
  { suffix: '.failure', label: '失败' },
  { suffix: '.timeout', label: '超时' },
  { suffix: '.non2xx', label: '非 2xx' },
  { suffix: '.current', label: '当前' },
  { suffix: '.daemon', label: '守护' },
  { suffix: '.loaded', label: '已加载' },
  { suffix: '.active', label: '活跃' },
  { suffix: '.max', label: '峰值' },
  { suffix: '.used', label: '已用' },
  { suffix: '.peak', label: '峰值' },
  { suffix: '.size', label: '长度' },
  { suffix: '.kept', label: '保留' },
  { suffix: '.mean', label: '平均值' },
  { suffix: '.count', label: '次数' },
  { suffix: '.pct', label: '使用率' },
  { suffix: '.ok', label: '成功' },
  { suffix: '.fail', label: '失败' },

  // Go runtime / Prometheus 风格(snake_case)
  { suffix: '_bytes_total', label: '累计字节' },
  { suffix: '_bytes', label: '字节' },
  { suffix: '_total', label: '累计' },
  { suffix: '_seconds', label: '秒' },
  { suffix: '_count', label: '次数' },
  { suffix: '_rate', label: '速率' },
  { suffix: '_avg', label: '平均' },
  { suffix: '_max', label: '峰值' },
  { suffix: '_min', label: '最小' },
  { suffix: '_size', label: '大小' },
  { suffix: '_objects', label: '对象数' },
  { suffix: '_inuse', label: '占用' },
  { suffix: '_sys', label: '系统占用' },
  { suffix: '_alloc', label: '已分配' }
]

const FALLBACK_PREFIX_LABELS: Record<string, string> = {
  'spring.watch.jvm.g1.eden': 'G1 Eden 区',
  'spring.watch.jvm.g1.oldgen': 'G1 老年代',
  'spring.watch.jvm.g1.survivor': 'G1 Survivor 区',
  'spring.watch.jvm': 'JVM',
  'spring.watch.kafka': 'Kafka',
  'spring.watch.alert': '告警',
  'spring.watch.collector': '采集器',
  'spring.watch.consumer': '消费',
  'spring.watch.ingest': '接入',
  'spring.watch.aggregator': '聚合',
  'spring.watch.alerter': '告警',
  'spring.watch.metric': '指标',
  'spring.watch.log': '日志',
  'spring.watch.influxdb': 'InfluxDB',
  'spring.watch.infra': '基础设施',
  'spring.watch.self': '自身',
  'kafka': 'Kafka',
  'producer': 'Producer',
  'broker': 'Broker',
  'go_memstats': 'Go 内存',
  'go_gc': 'Go GC',
  'go_gc_duration': 'Go GC 耗时',
  'tsm1_engine': 'TSM 引擎',
  'tsm1_wal': 'WAL 写前日志',
  'storage': '存储',
  'query_control': '查询控制',
  'httpd': 'HTTP 入口',
  'write': '写入',
  'consumer': '消费者',
  'network': '网络',
  'request': '请求',
  'replication': '副本'
}

/**
 * InfluxDB 自身 + Kafka exporter 等第三方指标的常用名映射。
 * 命名风格:InfluxData 官方 Prometheus 暴露名 + Kafka JMX exporter。
 */
export const INFRA_LABELS: Record<string, string> = {
  // TSM 引擎 (InfluxDB 1.x 旧指标)
  'tsm1_engine.cacheSizeBytes': 'TSM 引擎缓存',
  'tsm1_wal.size': 'WAL 写前日志大小',
  'tsm1_engine.compactionDurationSeconds': 'TSM 压缩耗时',

  // Go runtime — 新版 Prometheus 命名(go_ 前缀 + snake_case)
  'go_goroutines': 'Go 协程数',
  'go_threads': 'Go 线程数',
  // _internal 桶里 go_gc.* 新命名在多数 InfluxDB 版本上都是空表(数据走老格式 go_gc_duration_seconds.*),
  // 这里只翻译有数据的那套,新命名走兜底,标签自然区分开。
  'go_gc_duration_seconds.count': 'GC 次数',
  'go_gc_duration_seconds.sum': 'GC 总耗时',
  // Go 内存类指标统一用 MB 显示(panel 里 fmtValue 已经把 B/KB/GB 全部归一为 MB),
  // 中文标签就不重复写单位,只描述语义,避免和右边值列单位打架。
  'go_memstats.alloc_bytes': 'Go 已分配',
  'go_memstats.alloc_bytes_total': 'Go 累计分配',
  'go_memstats.buck_hash_sys_bytes': 'Go Bucket Hash 系统占用',
  'go_memstats.frees_total': 'Go 累计释放',
  'go_memstats.gc_sys_bytes': 'Go GC 系统占用',
  'go_memstats.heap_alloc_bytes': 'Go 堆已分配',
  'go_memstats.heap_idle_bytes': 'Go 堆空闲',
  'go_memstats.heap_inuse_bytes': 'Go 堆占用',
  'go_memstats.heap_objects': 'Go 堆对象数',
  'go_memstats.heap_released_bytes': 'Go 堆已释放',
  'go_memstats.heap_sys_bytes': 'Go 堆系统占用',
  'go_memstats.last_gc_time_seconds': '上次 GC 时间',
  'go_memstats.lookups_total': 'Go 查找次数',
  'go_memstats.mallocs_total': 'Go 分配次数',
  'go_memstats.mcache_inuse_bytes': 'Go mcache 占用',
  'go_memstats.mcache_sys_bytes': 'Go mcache 系统占用',
  'go_memstats.mspan_inuse_bytes': 'Go mspan 占用',
  'go_memstats.mspan_sys_bytes': 'Go mspan 系统占用',
  'go_memstats.next_gc_bytes': '下次 GC 触发阈值',
  'go_memstats.other_sys_bytes': 'Go 其他系统占用',
  'go_memstats.stack_inuse_bytes': 'Go 栈占用',
  'go_memstats.stack_sys_bytes': 'Go 栈系统占用',
  'go_memstats.sys_bytes': 'Go 运行时占用',

  // Go runtime — 旧版 camelCase 命名(早期 Promhttp 暴露)
  'go_memstats.heapInuseBytes': 'Go 堆占用',
  'go_memstats.heapAllocBytes': 'Go 堆已分配',
  'go_memstats.heapObjects': 'Go 堆对象数',
  'go_memstats.sysBytes': 'Go 运行时占用',
  'go_runtime.Goroutines': 'Go 协程数',

  // InfluxDB 存储 / 查询 / HTTP
  'storage.shards': '分片数',
  'storage.series': '时间序列数',
  'query_control.activeQueries': '活跃查询数',
  'httpd.activeConnections': '活跃 HTTP 连接',

  // InfluxDB 写入
  'write.pointsWritten': '写入点数',
  'write.writeOk': '写入成功',
  'write.writeError': '写入失败',
  'write.writeTimeout': '写入超时',
  'write.writePointReq': '写入点请求数',

  // Kafka JMX exporter(consumer.*)
  'consumer.lag': '消费者滞后',
  'consumer.lag.total': '消费者总滞后',
  'consumer.partitions': '消费者分配分区数',
  'consumer.fetch-rate': '拉取速率',
  'consumer.records-consumed-rate': '消费速率',
  'consumer.records-lag-max': '最大滞后记录数',
  'consumer.bytes-consumed-rate': '消费字节速率',
  'consumer.request-rate': '请求速率',
  'consumer.request-size-avg': '平均请求大小',
  'consumer.request-size-max': '最大请求大小',
  'consumer.response-rate': '响应速率',

  // KafkaHealthMonitor 自采指标(AdminClient + 自身 producer 计数)
  'kafka.brokers': 'Kafka 集群节点数',
  'kafka.controller_id': 'Controller broker id',
  'kafka.topics': 'Topic 数',
  'kafka.partitions': 'Partition 总数',
  'kafka.under_replicated_partitions': '副本不足 Partition 数(URP)',
  'kafka.offline_partitions': '离线 Partition 数',
  'kafka.replicas': '副本分配总数',
  'kafka.isr': 'In-Sync 副本总数',
  'kafka.log_size_bytes': '日志目录占用字节',
  'kafka.producer.sent': 'Producer 发送成功累计',
  'kafka.producer.failed': 'Producer 发送失败累计',

  // KafkaProducerJmxCollector: spring-watch 自己的 producer 客户端 JMX 内部指标
  'producer.record_send_rate': 'Producer 发送速率(条/s)',
  'producer.record_error_rate': 'Producer 失败速率(条/s)',
  'producer.record_retry_rate': 'Producer 重试速率(条/s)',
  'producer.byte_rate': 'Producer 总字节速率',
  'producer.outgoing_byte_rate': 'Producer 出站字节速率',
  'producer.batch_size_avg': 'Producer 平均批大小',
  'producer.batch_size_max': 'Producer 最大批大小',
  'producer.records_per_request_avg': 'Producer 平均每请求记录数',
  'producer.compression_rate': 'Producer 压缩率',
  'producer.buffer_available_bytes': 'Producer 可用缓冲字节',
  'producer.buffer_total_bytes': 'Producer 缓冲总字节',
  'producer.buffer_exhausted_rate': 'Producer 缓冲耗尽速率',
  'producer.request_rate': 'Producer 请求速率',
  'producer.request_latency_avg': 'Producer 请求平均延迟',
  'producer.request_latency_max': 'Producer 请求最大延迟',
  'producer.waiting_threads': 'Producer 等待线程数',
  'producer.io_wait_time_avg': 'Producer IO 平均等待',
  'producer.metadata_age': 'Producer 元数据年龄',

  // KafkaBrokerJmxMonitor: broker 端 JMX 指标
  'broker.messages_in_rate': 'Broker 入站消息速率',
  'broker.produce_requests_rate': 'Broker 生产请求速率',
  'broker.bytes_in_rate': 'Broker 入站字节速率',
  'broker.bytes_out_rate': 'Broker 出站字节速率',
  'broker.produce_failed_rate': 'Broker 生产失败速率',
  'broker.fetch_failed_rate': 'Broker 拉取失败速率',
  'broker.invalid_message_total': 'Broker 无效消息累计',
  'broker.under_replicated_partitions': 'Broker URP(副本不足)',
  'broker.offline_partitions': 'Broker 离线 Partition',
  'broker.active_controller': 'Active Controller(0/1)',
  'broker.request_queue_produce': '生产请求队列长度',
  'broker.request_queue_fetch': 'Fetch 请求队列长度',
  'broker.request_queue_fetch_consumer': 'Consumer Fetch 队列长度',
  'broker.purgatory_produce': '生产 Purgatory 堆积',
  'broker.purgatory_fetch': 'Fetch Purgatory 堆积',
  'broker.jvm.heap_used': 'Broker JVM 堆已用',
  'broker.jvm.heap_max': 'Broker JVM 堆上限',
  'broker.jvm.nonheap_used': 'Broker JVM 非堆已用',
  'broker.jvm.gc.count': 'Broker GC 累计次数',
  'broker.jvm.gc.time_ms': 'Broker GC 累计耗时',

  // Kafka 网络/请求
  'network.request-rate': '网络请求速率',
  'network.request-total': '网络请求总数'
}

export function labelOf(name: string): string {
  return SPRING_WATCH_LABELS[name] ?? INFRA_LABELS[name] ?? fallbackLabel(name)
}

/**
 * 后备翻译:按"主干前缀 + 末段后缀"拼出一个勉强可读的中文名,用于补全那些
 * 没有手工登记的指标。命中规则:
 *   1) 沿 FALLBACK_PREFIX_LABELS 从长到短找最长前缀;
 *   2) 剩余 tail 再按 FALLBACK_SUFFIX_LABELS 从长到短匹配末尾后缀;
 *   3) 拼出"主干·子段·后缀词"格式;都拼不出就回退原名。
 */
function fallbackLabel(name: string): string {
  const prefixKeys = Object.keys(FALLBACK_PREFIX_LABELS).sort((a, b) => b.length - a.length)
  let prefix = ''
  let prefixLabel = ''
  for (const key of prefixKeys) {
    if (name === key || name.startsWith(key + '.') || name.startsWith(key + '_')) {
      prefix = key
      prefixLabel = FALLBACK_PREFIX_LABELS[key]
      break
    }
  }
  if (!prefixLabel) return name

  const tail = name.slice(prefix.length).replace(/^[._]/, '')
  if (!tail) return prefixLabel

  const sortedSuffixes = [...FALLBACK_SUFFIX_LABELS].sort((a, b) => b.suffix.length - a.suffix.length)
  for (const { suffix, label } of sortedSuffixes) {
    if (tail === suffix.slice(1) || tail.endsWith(suffix)) {
      const stem = tail.slice(0, tail.length - suffix.length)
      return prefixLabel + (stem ? '·' + camelToReadable(stem) : '') + label
    }
  }
  return prefixLabel + '·' + camelToReadable(tail)
}

/**
 * 把驼峰或下划线分隔的尾段折成可读中文骨架(只去分隔符,不做语义翻译,留给前缀/后缀表去拼)。
 *  e.g. allocBytes → alloc bytes; metric_query_fail → metric query fail
 */
function camelToReadable(s: string): string {
  return s
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[._]+/g, ' ')
    .trim()
}
