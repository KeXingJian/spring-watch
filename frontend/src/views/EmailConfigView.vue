<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue'
import { api } from '@/api/client'
import { useToast } from '@/utils/toast'
import { useAppStore } from '@/stores/app'
import { formatTime } from '@/utils/format'
import Modal from '@/components/Modal.vue'
import EmptyState from '@/components/EmptyState.vue'

const toast = useToast()
const appStore = useAppStore()

const configs = ref<any[]>([])
const showEdit = ref(false)

const form = reactive({
  id: 0,
  appid: '',
  target: '',
  status: 'enabled'
})

const testTo = ref('')
const testResult = ref<{ ok: boolean; msg: string } | null>(null)
const testSending = ref(false)

const apps = computed(() => appStore.apps)

async function loadApps() {
  try {
    const list = await api.page<any>('/api/apps/active')
    appStore.setApps(list || [])
  } catch {
    /* ignore */
  }
}

async function loadConfigs() {
  try {
    configs.value = await api.page<any>('/api/notification/configs')
  } catch (e: any) {
    toast.error('加载失败: ' + e.message)
  }
}

onMounted(() => {
  loadApps()
  loadConfigs()
})

function openEdit(id: number | null) {
  if (id) {
    const c = configs.value.find((x) => x.id === id)
    if (!c) { toast.error('配置不存在'); return }
    form.id = c.id
    form.appid = String(c.appid || '')
    form.target = c.target || ''
    form.status = c.status || 'enabled'
  } else {
    form.id = 0
    form.appid = ''
    form.target = ''
    form.status = 'enabled'
  }
  showEdit.value = true
}

async function save() {
  if (!form.appid) { toast.error('请选择应用'); return }
  if (!form.target.trim()) { toast.error('收件人不能为空'); return }
  const body = {
    appid: parseInt(form.appid, 10),
    target: form.target.trim(),
    status: form.status
  }
  try {
    if (form.id) {
      await api.put('/api/notification/configs/' + form.id, body)
      toast.success('已更新')
    } else {
      await api.post('/api/notification/configs', body)
      toast.success('已创建')
    }
    showEdit.value = false
    await loadConfigs()
  } catch (e: any) {
    toast.error('保存失败: ' + e.message)
  }
}

function doDelete(id: number) {
  if (!confirm('确定删除该收件人配置?')) return
  api.del('/api/notification/configs/' + id).then(async () => {
    toast.success('已删除')
    await loadConfigs()
  }).catch((e: any) => toast.error('删除失败: ' + e.message))
}

async function sendTest() {
  if (!testTo.value.trim()) { toast.error('请输入收件人地址'); return }
  testSending.value = true
  testResult.value = null
  try {
    const r = await api.post<any>('/api/notification/test', null, { to: testTo.value.trim() })
    const ok = r?.ok === true
    testResult.value = { ok, msg: (ok ? '发送成功 - ' : '发送失败 - ') + (r?.raw || '') }
  } catch (e: any) {
    testResult.value = { ok: false, msg: '调用失败: ' + e.message }
  } finally {
    testSending.value = false
  }
}

function appLabel(c: any) {
  const a = apps.value.find((x) => x.appid === c.appid)
  return a ? `${a.appName} (${a.appid})` : `appid=${c.appid}`
}
</script>

<template>
  <div class="page-container-narrow">
    <div class="toolbar">
      <button class="btn btn-primary btn-sm" @click="openEdit(null)">
        <svg viewBox="0 0 20 20" fill="currentColor" class="w-4 h-4"><path d="M10 3a1 1 0 0 1 1 1v5h5a1 1 0 1 1 0 2h-5v5a1 1 0 1 1-2 0v-5H4a1 1 0 1 1 0-2h5V4a1 1 0 0 1 1-1z"/></svg>
        新建收件人
      </button>
      <span class="right" />
      <button class="btn btn-ghost btn-sm" @click="loadConfigs">
        <svg viewBox="0 0 20 20" fill="currentColor" class="w-4 h-4"><path fill-rule="evenodd" d="M4 2a1 1 0 0 1 1 1v2.101a7.002 7.002 0 0 1 11.601 2.566 1 1 0 1 1-1.885.666A5.002 5.002 0 0 0 5.999 7H9a1 1 0 0 1 0 2H4a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1zm.008 9.057a1 1 0 0 1 1.276.61A5.002 5.002 0 0 0 14.001 13H11a1 1 0 1 1 0-2h5a1 1 0 0 1 1 1v5a1 1 0 1 1-2 0v-2.1a7.002 7.002 0 0 1-11.601-2.566 1 1 0 0 1 .61-1.277z" clip-rule="evenodd"/></svg>
        刷新
      </button>
    </div>

    <div class="card bg-base-100 border border-base-300 shadow-sm mb-4">
      <div class="card-body p-0">
        <div class="px-4 py-2.5 border-b border-base-300 flex items-center font-medium text-sm">
          <span>测试邮件连通性</span>
          <span class="ml-auto text-xs text-muted font-normal">用配置的 SMTP 发件,确认通道正常</span>
        </div>
        <div class="flex items-center gap-2 p-3 bg-base-200/50 border-b border-base-300">
          <input v-model="testTo" placeholder="输入测试收件人地址,支持多个(逗号分隔)" class="input input-bordered input-sm flex-1" />
          <button class="btn btn-primary btn-sm" :disabled="testSending" @click="sendTest">
            <span v-if="testSending" class="loading loading-spinner loading-xs" />
            {{ testSending ? '发送中…' : '发送测试' }}
          </button>
        </div>
        <div
          v-if="testResult"
          class="px-4 py-2.5 text-sm"
          :class="testResult.ok ? 'bg-success/10 text-success' : 'bg-error/10 text-error'"
        >{{ testResult.msg }}</div>
      </div>
    </div>

    <div class="card bg-base-100 border border-base-300 shadow-sm">
      <div class="card-body p-0">
        <div class="px-4 py-2.5 border-b border-base-300 flex items-center font-medium text-sm">
          <span>收件人配置</span>
          <span class="ml-auto text-xs text-muted font-normal">{{ configs.length }} 条</span>
        </div>
        <div class="overflow-x-auto">
          <table class="table table-sm table-zebra">
            <thead>
              <tr class="text-secondary">
                <th class="w-12">ID</th>
                <th class="w-48">应用</th>
                <th>收件人(target)</th>
                <th class="w-20">状态</th>
                <th class="w-36">创建时间</th>
                <th class="w-44">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="configs.length === 0">
                <td colspan="6"><EmptyState inline>暂无收件人,点击 [新建收件人] 开始</EmptyState></td>
              </tr>
              <tr v-for="c in configs" v-else :key="c.id">
                <td>{{ c.id }}</td>
                <td>{{ appLabel(c) }}</td>
                <td><code class="text-xs">{{ c.target || '-' }}</code></td>
                <td>
                  <span :class="['badge badge-sm', c.status === 'enabled' ? 'badge-success' : 'badge-ghost']">{{ c.status || '-' }}</span>
                </td>
                <td>{{ formatTime(c.createdAt) }}</td>
                <td>
                  <div class="row-actions">
                    <button class="btn btn-ghost btn-xs" @click="openEdit(c.id)">编辑</button>
                    <button class="btn btn-ghost btn-xs text-error" @click="doDelete(c.id)">删除</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>

  <Modal v-model="showEdit" :title="form.id ? '编辑收件人' : '新建收件人'" :width="560">
    <div class="form-row">
      <label>应用 <span class="required">*</span></label>
      <select class="select select-bordered select-sm w-full" v-model="form.appid">
        <option value="">-- 请选择 --</option>
        <option v-for="a in apps" :key="a.appid" :value="String(a.appid)">{{ a.appName }} ({{ a.appid }})</option>
      </select>
    </div>
    <div class="form-row">
      <label>收件人(target) <span class="required">*</span> <span class="hint">多个地址用逗号分隔</span></label>
      <input class="input input-bordered input-sm w-full" v-model="form.target" placeholder="a@x.com,b@y.com" />
    </div>
    <div class="form-row">
      <label>状态</label>
      <select class="select select-bordered select-sm w-full" v-model="form.status">
        <option value="enabled" selected>enabled</option>
        <option value="disabled">disabled</option>
      </select>
    </div>
    <template #footer>
      <button class="btn btn-ghost btn-sm" @click="showEdit = false">取消</button>
      <button class="btn btn-primary btn-sm" @click="save">保存</button>
    </template>
  </Modal>
</template>
