<script setup lang="ts">
import { onMounted, ref, reactive, computed } from 'vue'
import { api } from '@/api/client'
import { useToast } from '@/utils/toast'
import { shortJson } from '@/utils/format'
import Modal from '@/components/Modal.vue'
import EmptyState from '@/components/EmptyState.vue'

const toast = useToast()

interface AppItem {
  id: number
  appid: number
  appName: string
  endpoint?: string
  metricsPort?: number
  scheduleType?: string
  scrapeInterval?: number
  cronExpression?: string
  labels?: string
  status?: string
  lastHeartbeat?: string
}

const apps = ref<AppItem[]>([])
const loading = ref(false)
const total = ref(0)
const page = ref(0)
const size = ref(20)
const pageSizes = [10, 20, 50, 100]

const totalPages = computed(() => (size.value > 0 ? Math.max(1, Math.ceil(total.value / size.value)) : 1))
const pageRange = computed(() => {
  if (total.value === 0) return '0-0'
  const from = page.value * size.value + 1
  const to = Math.min(page.value * size.value + apps.value.length, total.value)
  return `${from}-${to}`
})

const showEdit = ref(false)
const showOtel = ref(false)
const editing = ref<AppItem | null>(null)

interface OtelConfig {
  appid: number | string
  endpoint?: string
  env?: string[]
  command?: string
}
const otelCtx = ref<OtelConfig | null>(null)

const form = reactive({
  id: 0,
  appName: '',
  endpoint: '',
  metricsPort: 9464,
  scheduleType: 'INTERVAL' as 'INTERVAL' | 'CRON',
  scrapeInterval: 15,
  cronExpression: '',
  labels: ''
})

const isCron = ref(false)

async function loadApps() {
  loading.value = true
  try {
    const res = await api.pageFull<AppItem>('/api/apps', { page: page.value, size: size.value })
    apps.value = res.items
    total.value = res.total
  } catch (e: any) {
    toast.error('加载失败: ' + e.message)
  } finally {
    loading.value = false
  }
}

function changeSize(v: number) {
  size.value = v
  page.value = 0
  loadApps()
}

function goPage(p: number) {
  const target = Math.max(0, Math.min(p, totalPages.value - 1))
  if (target === page.value) return
  page.value = target
  loadApps()
}

onMounted(loadApps)

function openEdit(id: number | null) {
  editing.value = id ? apps.value.find((a) => a.id === id) || null : null
  if (id && !editing.value) {
    toast.error('应用不存在')
    return
  }
  if (editing.value) {
    const a = editing.value
    form.id = a.id
    form.appName = a.appName || ''
    form.endpoint = a.endpoint || ''
    form.metricsPort = a.metricsPort || 9464
    form.scheduleType = (a.scheduleType as any) || 'INTERVAL'
    form.scrapeInterval = a.scrapeInterval || 15
    form.cronExpression = a.cronExpression || ''
    form.labels = a.labels || ''
  } else {
    form.id = 0
    form.appName = ''
    form.endpoint = ''
    form.metricsPort = 9464
    form.scheduleType = 'INTERVAL'
    form.scrapeInterval = 15
    form.cronExpression = ''
    form.labels = ''
  }
  isCron.value = form.scheduleType === 'CRON'
  showEdit.value = true
}

function onScheduleChange() {
  isCron.value = form.scheduleType === 'CRON'
}

async function save() {
  if (!form.appName.trim()) {
    toast.error('应用名称不能为空')
    return
  }
  if (form.labels.trim()) {
    try {
      JSON.parse(form.labels)
    } catch (e: any) {
      toast.error('标签不是合法 JSON: ' + e.message)
      return
    }
  }
  const body = {
    appName: form.appName.trim(),
    endpoint: form.endpoint.trim() || null,
    metricsPort: form.metricsPort,
    scheduleType: form.scheduleType,
    scrapeInterval: form.scrapeInterval,
    cronExpression: form.cronExpression.trim() || null,
    labels: form.labels.trim() || null
  }
  try {
    if (form.id) {
      await api.put('/api/apps/' + form.id, body)
      toast.success('已更新')
    } else {
      await api.post('/api/apps', body)
      toast.success('已创建')
    }
    showEdit.value = false
    await loadApps()
  } catch (e: any) {
    toast.error('保存失败: ' + e.message)
  }
}

async function doAction(fn: () => Promise<unknown>, okMsg: string) {
  try {
    await fn()
    toast.success(okMsg)
    await loadApps()
  } catch (e: any) {
    toast.error('操作失败: ' + e.message)
  }
}

function doDelete(id: number) {
  if (!confirm('确定删除该应用?该操作不可恢复,关联的指标数据将不再被采集。')) return
  doAction(() => api.del('/api/apps/' + id), '已删除')
}

async function openOtel(id: number) {
  try {
    otelCtx.value = await api.get<OtelConfig>('/api/apps/' + id + '/otel-config')
    showOtel.value = true
  } catch (e: any) {
    toast.error('加载 OTel 配置失败: ' + e.message)
  }
}

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    toast.success('已复制')
  } catch {
    const ta = document.createElement('textarea')
    ta.value = text
    document.body.appendChild(ta)
    ta.select()
    try {
      document.execCommand('copy')
      toast.success('已复制')
    } catch {
      toast.error('复制失败')
    }
    document.body.removeChild(ta)
  }
}

function formatSchedule(a: AppItem) {
  if (a.scheduleType === 'CRON') return 'CRON ' + (a.cronExpression || '-')
  return (a.scrapeInterval || 15) + ' s'
}

function formatHeartbeat(t?: string) {
  if (!t) return { text: '无', cls: 'dead' }
  const ms = Date.now() - new Date(t).getTime()
  if (ms < 0 || ms < 60000) return { text: '刚刚', cls: '' }
  if (ms < 300000) return { text: Math.floor(ms / 60000) + ' 分钟前', cls: '' }
  if (ms < 3600000) return { text: Math.floor(ms / 60000) + ' 分钟前', cls: 'stale' }
  return { text: Math.floor(ms / 3600000) + ' 小时前', cls: 'dead' }
}
</script>

<template>
  <div class="page-container-narrow">
    <h2 class="text-xl font-semibold mb-4">监控应用</h2>

    <div class="toolbar">
      <button class="btn btn-primary btn-sm" @click="openEdit(null)">
        <svg viewBox="0 0 20 20" fill="currentColor" class="w-4 h-4"><path d="M10 3a1 1 0 0 1 1 1v5h5a1 1 0 1 1 0 2h-5v5a1 1 0 1 1-2 0v-5H4a1 1 0 1 1 0-2h5V4a1 1 0 0 1 1-1z"/></svg>
        新建应用
      </button>
      <span class="text-xs text-muted ml-1">共 {{ total }} 个应用</span>
      <span class="right" />
      <button class="btn btn-ghost btn-sm" @click="loadApps">
        <svg viewBox="0 0 20 20" fill="currentColor" class="w-4 h-4"><path fill-rule="evenodd" d="M4 2a1 1 0 0 1 1 1v2.101a7.002 7.002 0 0 1 11.601 2.566 1 1 0 1 1-1.885.666A5.002 5.002 0 0 0 5.999 7H9a1 1 0 0 1 0 2H4a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1zm.008 9.057a1 1 0 0 1 1.276.61A5.002 5.002 0 0 0 14.001 13H11a1 1 0 1 1 0-2h5a1 1 0 0 1 1 1v5a1 1 0 1 1-2 0v-2.1a7.002 7.002 0 0 1-11.601-2.566 1 1 0 0 1 .61-1.277z" clip-rule="evenodd"/></svg>
        刷新
      </button>
    </div>

    <div class="card bg-base-100 border border-base-300 shadow-sm">
      <div class="card-body p-0">
        <div class="overflow-x-auto">
          <table class="table table-sm table-zebra">
            <thead>
              <tr class="text-secondary">
                <th class="w-12">ID</th>
                <th>名称</th>
                <th class="w-32">appid</th>
                <th>endpoint</th>
                <th class="w-16">端口</th>
                <th class="w-28">采集间隔</th>
                <th class="w-20">状态</th>
                <th class="w-32">心跳</th>
                <th class="w-20">标签</th>
                <th class="w-60">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="loading">
                <td colspan="10">
                  <div class="flex items-center justify-center py-8 gap-2 text-secondary">
                    <span class="loading loading-spinner loading-sm" />
                    <span>加载中…</span>
                  </div>
                </td>
              </tr>
              <tr v-else-if="apps.length === 0">
                <td colspan="10">
                  <EmptyState icon="<svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.5'><path d='M4 5h16M4 10h16M4 15h10M4 20h8'/></svg>">暂无应用,点击 [新建应用] 开始</EmptyState>
                </td>
              </tr>
              <tr v-for="a in apps" v-else :key="a.id">
                <td>{{ a.id }}</td>
                <td><strong>{{ a.appName }}</strong></td>
                <td><code class="text-xs">{{ a.appid }}</code></td>
                <td class="truncate max-w-xs">{{ a.endpoint || '-' }}</td>
                <td>{{ a.metricsPort || '-' }}</td>
                <td>{{ formatSchedule(a) }}</td>
                <td>
                  <span :class="['badge badge-sm', a.status === 'active' ? 'badge-success' : 'badge-ghost']">
                    {{ a.status || '-' }}
                  </span>
                </td>
                <td>
                  <span class="heartbeat" :class="formatHeartbeat(a.lastHeartbeat).cls">
                    {{ formatHeartbeat(a.lastHeartbeat).text }}
                  </span>
                </td>
                <td>
                  <code v-if="a.labels" class="text-xs">{{ shortJson(a.labels) }}</code>
                  <span v-else class="muted">-</span>
                </td>
                <td>
                  <div class="row-actions">
                    <button class="btn btn-ghost btn-xs" @click="openOtel(a.id)">配置</button>
                    <button class="btn btn-ghost btn-xs" @click="openEdit(a.id)">编辑</button>
                    <button
                      v-if="a.status === 'active'"
                      class="btn btn-ghost btn-xs"
                      @click="doAction(() => api.post('/api/apps/' + a.id + '/pause'), '已暂停')"
                    >暂停</button>
                    <button
                      v-else
                      class="btn btn-ghost btn-xs"
                      @click="doAction(() => api.post('/api/apps/' + a.id + '/resume'), '已恢复')"
                    >恢复</button>
                    <button class="btn btn-ghost btn-xs text-error" @click="doDelete(a.id)">删除</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="!loading && total > 0" class="pagination-bar">
          <span class="text-xs text-muted">
            第 {{ pageRange }} 条 / 共 {{ total }} 条
          </span>
          <span class="right" />
          <div class="join">
            <select
              class="select select-bordered select-xs join-item"
              :value="size"
              @change="changeSize(Number(($event.target as HTMLSelectElement).value))"
            >
              <option v-for="s in pageSizes" :key="s" :value="s">{{ s }} / 页</option>
            </select>
            <button
              class="btn btn-xs join-item"
              :disabled="page <= 0"
              @click="goPage(0)"
            >«</button>
            <button
              class="btn btn-xs join-item"
              :disabled="page <= 0"
              @click="goPage(page - 1)"
            >‹</button>
            <button class="btn btn-xs join-item btn-active no-animation">
              {{ page + 1 }} / {{ totalPages }}
            </button>
            <button
              class="btn btn-xs join-item"
              :disabled="page >= totalPages - 1"
              @click="goPage(page + 1)"
            >›</button>
            <button
              class="btn btn-xs join-item"
              :disabled="page >= totalPages - 1"
              @click="goPage(totalPages - 1)"
            >»</button>
          </div>
        </div>
      </div>
    </div>

    <p class="info-tip">
      appid 是被监控应用在采集端配置时使用的标识(由后端雪花算法生成,不可改)。
      endpoint 是 OTel Collector 或应用自身暴露的指标端点。标签会以 K=V 形式附带在所有指标上。
    </p>
  </div>

  <Modal v-model="showEdit" :title="form.id ? '编辑应用' : '新建应用'" :width="560">
    <div class="form-row">
      <label>应用名称 <span class="required">*</span></label>
      <input class="input input-bordered input-sm w-full" v-model="form.appName" placeholder="例如 mock-test" />
    </div>
    <div class="form-row">
      <label>endpoint <span class="hint">(OTel/Prometheus 指标端点 URL)</span></label>
      <input class="input input-bordered input-sm w-full" v-model="form.endpoint" placeholder="http://host:port" />
    </div>
    <div class="form-row row-2">
      <div>
        <label>采集端口</label>
        <input class="input input-bordered input-sm w-full" type="number" min="1" max="65535" v-model.number="form.metricsPort" />
      </div>
      <div>
        <label>调度类型</label>
        <select class="select select-bordered select-sm w-full" v-model="form.scheduleType" @change="onScheduleChange">
          <option value="INTERVAL">INTERVAL(按秒)</option>
          <option value="CRON">CRON</option>
        </select>
      </div>
    </div>
    <div class="form-row row-2">
      <div>
        <label>{{ isCron ? '(INTERVAL 模式才生效)' : '采集间隔(秒)' }}</label>
        <input
          class="input input-bordered input-sm w-full"
          type="number"
          min="1"
          v-model.number="form.scrapeInterval"
          :disabled="isCron"
        />
      </div>
      <div>
        <label :style="!isCron ? 'visibility:hidden;' : ''">Cron 表达式</label>
        <input
          class="input input-bordered input-sm w-full"
          v-model="form.cronExpression"
          placeholder="0 */5 * * * *"
          :style="!isCron ? 'visibility:hidden;' : ''"
        />
      </div>
    </div>
    <div class="form-row">
      <label>标签 <span class="hint">(JSON 格式,例:{"env":"prod","team":"infra"})</span></label>
      <textarea class="textarea textarea-bordered w-full" rows="2" v-model="form.labels" placeholder='{"env":"prod"}'></textarea>
    </div>
    <template #footer>
      <button class="btn btn-ghost btn-sm" @click="showEdit = false">取消</button>
      <button class="btn btn-primary btn-sm" @click="save">保存</button>
    </template>
  </Modal>

  <Modal v-model="showOtel" title="OTel 采集配置" :width="720">
    <p class="text-sm mb-2">
      appid: <code class="text-xs">{{ otelCtx?.appid || '-' }}</code>
      &nbsp;&nbsp; endpoint: <code class="text-xs">{{ otelCtx?.endpoint || '-' }}</code>
    </p>
    <div class="form-row">
      <label>环境变量(env)</label>
      <div class="code-wrap">
        <pre class="code">{{ (otelCtx?.env || []).join('\n') }}</pre>
        <button class="btn btn-primary btn-xs copy-btn" @click="copyText((otelCtx?.env || []).join('\n'))">复制</button>
      </div>
    </div>
    <div class="form-row">
      <label>Java Agent 启动命令(java -javaagent:…)替换 <code>/path/to/opentelemetry-javaagent.jar</code></label>
      <div class="code-wrap">
        <pre class="code compact">{{ otelCtx?.command || '-' }}</pre>
        <button class="btn btn-primary btn-xs copy-btn" @click="copyText(otelCtx?.command || '')">复制</button>
      </div>
    </div>
    <p class="info-tip">
      在目标应用启动脚本中加入上面的 -javaagent 参数即可,后端会按此 appid 拉取指标。修改配置后可在监控应用列表点 [刷新] 重新拉取。
    </p>
    <template #footer>
      <button class="btn btn-primary btn-sm" @click="showOtel = false">关闭</button>
    </template>
  </Modal>
</template>
