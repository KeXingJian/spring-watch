<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue'
import { api } from '@/api/client'
import { useToast } from '@/utils/toast'
import { useAppStore } from '@/stores/app'
import Modal from '@/components/Modal.vue'
import EmptyState from '@/components/EmptyState.vue'

const toast = useToast()
const appStore = useAppStore()

const rules = ref<any[]>([])

const showEdit = ref(false)
const isCron = ref(false)

const form = reactive({
  id: 0,
  appid: '',
  name: '',
  type: 'metric' as 'metric' | 'log_keyword' | 'log_error_rate' | 'log_new_pattern',
  level: 'warning',
  expression: '',
  threshold: '' as string | number,
  duration: 60,
  times: 1,
  status: 'enabled',
  channels: '',
  template: ''
})

const showExpr = ref(true)
const showThreshold = ref(false)
const showTimes = ref(true)
const exprRequired = ref(true)
const exprLabel = ref('JEXL 表达式 *')
const exprPlaceholder = ref("metric == 'jvm_memory_used_bytes' && value > 800000000")

const apps = computed(() => appStore.apps)

async function loadApps() {
  try {
    const list = await api.page<any>('/api/apps/active')
    appStore.setApps(list || [])
  } catch {
    /* ignore */
  }
}

async function loadRules() {
  try {
    rules.value = await api.page<any>('/api/alert/rules')
  } catch (e: any) {
    toast.error('加载失败: ' + e.message)
  }
}

onMounted(() => {
  loadApps()
  loadRules()
})

function onTypeChange() {
  showExpr.value = true
  showThreshold.value = false
  showTimes.value = false
  exprRequired.value = false
  exprLabel.value = 'JEXL 表达式 *'
  exprPlaceholder.value = "metric == 'jvm_memory_used_bytes' && value > 800000000"

  if (form.type === 'metric') {
    showThreshold.value = true
    showTimes.value = true
    exprRequired.value = true
    exprLabel.value = "JEXL 表达式 * (metric 类型必填)"
    exprPlaceholder.value = "metric == 'jvm_memory_used_bytes' && value > 800000000"
  } else if (form.type === 'log_error_rate') {
    showExpr.value = false
    showThreshold.value = true
    showTimes.value = true
  } else if (form.type === 'log_keyword') {
    showTimes.value = false
    exprRequired.value = true
    exprLabel.value = "关键字 * (log_keyword 类型必填,匹配 message/throwable 包含)"
    exprPlaceholder.value = 'OutOfMemoryError'
  } else if (form.type === 'log_new_pattern') {
    showExpr.value = false
    showThreshold.value = false
    showTimes.value = false
  }
}

function openEdit(id: number | null) {
  form.id = id || 0
  if (id) {
    const r = rules.value.find((x) => x.id === id)
    if (!r) { toast.error('规则不存在'); return }
    form.appid = r.app ? String(r.app.appid) : ''
    form.name = r.ruleName || ''
    form.type = r.ruleType || 'metric'
    form.level = r.level || 'warning'
    form.expression = r.expression || ''
    form.threshold = r.thresholdValue != null ? r.thresholdValue : ''
    form.duration = r.durationSeconds != null ? r.durationSeconds : 60
    form.times = r.times != null ? r.times : 1
    form.status = r.status || 'enabled'
    form.channels = r.notifyChannels || ''
    form.template = r.template || ''
  } else {
    form.id = 0
    form.appid = ''
    form.name = ''
    form.type = 'metric'
    form.level = 'warning'
    form.expression = ''
    form.threshold = ''
    form.duration = 60
    form.times = 1
    form.status = 'enabled'
    form.channels = ''
    form.template = ''
  }
  onTypeChange()
  showEdit.value = true
}

async function save() {
  if (!form.appid) { toast.error('请选择应用'); return }
  if (!form.name.trim()) { toast.error('规则名不能为空'); return }
  if (form.channels.trim()) {
    try { JSON.parse(form.channels) }
    catch (e: any) { toast.error('通知渠道不是合法 JSON'); return }
  }
  const body: any = {
    appid: parseInt(form.appid, 10),
    ruleName: form.name.trim(),
    ruleType: form.type,
    expression: form.expression.trim() || null,
    thresholdValue: form.threshold ? parseFloat(String(form.threshold)) : null,
    durationSeconds: parseInt(String(form.duration), 10) || 60,
    times: parseInt(String(form.times), 10) || 1,
    level: form.level,
    status: form.status,
    notifyChannels: form.channels.trim() || null,
    template: form.template.trim() || null
  }
  try {
    if (form.id) {
      await api.put('/api/alert/rules/' + form.id, body)
      toast.success('已更新')
    } else {
      await api.post('/api/alert/rules', body)
      toast.success('已创建')
    }
    showEdit.value = false
    await loadRules()
  } catch (e: any) {
    toast.error('保存失败: ' + e.message)
  }
}

async function toggle(id: number) {
  try {
    await api.post('/api/alert/rules/' + id + '/toggle')
    toast.success('已切换')
    await loadRules()
  } catch (e: any) {
    toast.error('切换失败: ' + e.message)
  }
}

function doDelete(id: number) {
  if (!confirm('确定删除该规则?')) return
  api.del('/api/alert/rules/' + id).then(async () => {
    toast.success('已删除')
    await loadRules()
  }).catch((e: any) => toast.error('删除失败: ' + e.message))
}

function appLabel(r: any) {
  return r.app ? `${r.app.appName} (${r.app.appid})` : '-'
}
function exprOrThreshold(r: any) {
  if (r.ruleType === 'log_error_rate') return '阈值 ' + (r.thresholdValue != null ? r.thresholdValue + '%' : '-')
  return r.expression || '-'
}
</script>

<template>
  <div class="page-container">
    <div class="toolbar">
      <button class="btn btn-primary btn-sm" @click="openEdit(null)">
        <svg viewBox="0 0 20 20" fill="currentColor" class="w-4 h-4"><path d="M10 3a1 1 0 0 1 1 1v5h5a1 1 0 1 1 0 2h-5v5a1 1 0 1 1-2 0v-5H4a1 1 0 1 1 0-2h5V4a1 1 0 0 1 1-1z"/></svg>
        新建规则
      </button>
      <span class="right" />
      <button class="btn btn-ghost btn-sm" @click="loadRules">
        <svg viewBox="0 0 20 20" fill="currentColor" class="w-4 h-4"><path fill-rule="evenodd" d="M4 2a1 1 0 0 1 1 1v2.101a7.002 7.002 0 0 1 11.601 2.566 1 1 0 1 1-1.885.666A5.002 5.002 0 0 0 5.999 7H9a1 1 0 0 1 0 2H4a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1zm.008 9.057a1 1 0 0 1 1.276.61A5.002 5.002 0 0 0 14.001 13H11a1 1 0 1 1 0-2h5a1 1 0 0 1 1 1v5a1 1 0 1 1-2 0v-2.1a7.002 7.002 0 0 1-11.601-2.566 1 1 0 0 1 .61-1.277z" clip-rule="evenodd"/></svg>
        刷新
      </button>
    </div>

    <div class="card bg-base-100 border border-base-300 shadow-sm">
      <div class="card-body p-0">
        <div class="px-4 py-2.5 border-b border-base-300 flex items-center font-medium text-sm">
          <span>告警规则列表</span>
          <span class="ml-auto text-xs text-muted font-normal">{{ rules.length }} 条</span>
        </div>
        <div class="overflow-x-auto">
          <table class="table table-sm table-zebra">
            <thead>
              <tr class="text-secondary">
                <th class="w-12">ID</th>
                <th class="w-48">名称</th>
                <th class="w-36">应用</th>
                <th class="w-32">类型</th>
                <th class="w-20">级别</th>
                <th>表达式 / 阈值</th>
                <th class="w-16">持续</th>
                <th class="w-16">命中</th>
                <th class="w-20">状态</th>
                <th class="w-56">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="rules.length === 0">
                <td colspan="10">
                  <EmptyState icon="<svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.5'><path d='M4 5h16M4 10h16M4 15h12M4 20h8'/></svg>">暂无规则,点击 [新建规则] 开始</EmptyState>
                </td>
              </tr>
              <tr v-for="r in rules" v-else :key="r.id">
                <td>{{ r.id }}</td>
                <td><strong>{{ r.ruleName }}</strong></td>
                <td>{{ appLabel(r) }}</td>
                <td><span :class="['rule-type', r.ruleType]">{{ r.ruleType }}</span></td>
                <td><span :class="['badge-level', r.level || 'warning']">{{ (r.level || 'warning').toUpperCase() }}</span></td>
                <td class="truncate max-w-xs" :title="exprOrThreshold(r)">{{ exprOrThreshold(r) }}</td>
                <td>{{ r.durationSeconds || 0 }}s</td>
                <td>{{ r.times || 1 }}</td>
                <td>
                  <span :class="['badge badge-sm', r.status === 'enabled' ? 'badge-success' : 'badge-ghost']">{{ r.status }}</span>
                </td>
                <td>
                  <div class="row-actions">
                    <button class="btn btn-ghost btn-xs" @click="openEdit(r.id)">编辑</button>
                    <button class="btn btn-ghost btn-xs" @click="toggle(r.id)">{{ r.status === 'enabled' ? '停用' : '启用' }}</button>
                    <button class="btn btn-ghost btn-xs text-error" @click="doDelete(r.id)">删除</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>

  <Modal v-model="showEdit" :title="form.id ? '编辑规则' : '新建规则'" :width="720">
    <div class="form-row row-2">
      <div>
        <label>应用 <span class="required">*</span></label>
        <select class="select select-bordered select-sm w-full" v-model="form.appid">
          <option value="">-- 请选择 --</option>
          <option v-for="a in apps" :key="a.appid" :value="String(a.appid)">{{ a.appName }} ({{ a.appid }})</option>
        </select>
      </div>
      <div>
        <label>规则名 <span class="required">*</span></label>
        <input class="input input-bordered input-sm w-full" v-model="form.name" placeholder="如:CPU 过高" />
      </div>
    </div>
    <div class="form-row row-2">
      <div>
        <label>类型 <span class="required">*</span></label>
        <select class="select select-bordered select-sm w-full" v-model="form.type" @change="onTypeChange">
          <option value="metric">metric - 指标阈值</option>
          <option value="log_keyword">log_keyword - 日志关键字</option>
          <option value="log_error_rate">log_error_rate - 日志错误率</option>
          <option value="log_new_pattern">log_new_pattern - 日志新模式</option>
        </select>
      </div>
      <div>
        <label>级别</label>
        <select class="select select-bordered select-sm w-full" v-model="form.level">
          <option value="info">INFO</option>
          <option value="warning" selected>WARNING</option>
          <option value="critical">CRITICAL</option>
        </select>
      </div>
    </div>
    <div class="form-row" v-show="showExpr">
      <label>{{ exprLabel }} <span class="hint" v-if="!exprRequired">(可选)</span></label>
      <textarea class="textarea textarea-bordered w-full" rows="2" v-model="form.expression" :placeholder="exprPlaceholder"></textarea>
    </div>
    <div class="form-row row-2" v-show="showThreshold">
      <div>
        <label>阈值 <span class="required" v-if="form.type === 'log_error_rate'">*</span> <span class="hint">(log_error_rate 类型,百分比 0~100)</span></label>
        <input class="input input-bordered input-sm w-full" type="number" step="0.1" v-model="form.threshold" placeholder="10" />
      </div>
      <div>
        <label>持续时长(秒) <span class="hint">(PENDING 防抖)</span></label>
        <input class="input input-bordered input-sm w-full" type="number" min="0" v-model.number="form.duration" />
      </div>
    </div>
    <div class="form-row row-2" v-show="showTimes">
      <div>
        <label>命中次数 <span class="hint">(PENDING 累计)</span></label>
        <input class="input input-bordered input-sm w-full" type="number" min="1" v-model.number="form.times" />
      </div>
      <div>
        <label>状态</label>
        <select class="select select-bordered select-sm w-full" v-model="form.status">
          <option value="enabled" selected>enabled</option>
          <option value="disabled">disabled</option>
        </select>
      </div>
    </div>
    <div class="form-row">
      <label>通知渠道 <span class="hint">(JSON,如:{"email":"a@x.com"},留空回退到 alert_notification_config)</span></label>
      <textarea class="textarea textarea-bordered w-full" rows="2" v-model="form.channels" placeholder='{"email":"a@x.com,b@y.com"}'></textarea>
    </div>
    <div class="form-row">
      <label>自定义模板 <span class="hint" v-pre>(支持 {{metric}} {{value}} {{rule}} 等占位符,留空用默认)</span></label>
      <textarea class="textarea textarea-bordered w-full" rows="2" v-model="form.template" placeholder="告警: {{rule}} 当前值 {{value}}"></textarea>
    </div>
    <template #footer>
      <button class="btn btn-ghost btn-sm" @click="showEdit = false">取消</button>
      <button class="btn btn-primary btn-sm" @click="save">保存</button>
    </template>
  </Modal>
</template>
