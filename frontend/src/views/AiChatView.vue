<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useAppStore } from '@/stores/app'

interface ChatMsg {
  id?: number
  role: string
  content: string
  createdAt?: string
}

interface Conversation {
  id: number
  title: string
  createdAt?: string
}

interface DiagnosisReport {
  id: number
  ruleName?: string
  alertLevel?: string
  triggerMetric?: string
  triggerValue?: number
  status?: string
  errorMsg?: string
  report?: string
  createdAt?: string
}

const appStore = useAppStore()
const conversations = ref<Conversation[]>([])
const currentConv = ref<number | null>(null)
const messages = ref<ChatMsg[]>([])
const input = ref('')
const streaming = ref(false)
const error = ref('')
const activeTab = ref<'chat' | 'diagnosis'>('chat')
const reports = ref<DiagnosisReport[]>([])
const reportsLoading = ref(false)

onMounted(() => {
  loadConversations()
})

watch(
  () => [activeTab.value, appStore.currentAppid],
  () => {
    if (activeTab.value === 'diagnosis') loadDiagnosis()
  }
)

async function loadDiagnosis() {
  const appid = appStore.currentAppid
  if (!appid) {
    reports.value = []
    return
  }
  reportsLoading.value = true
  try {
    const res = await fetch(`/api/ai/diagnosis?appid=${appid}&size=20`)
    const json = await res.json()
    reports.value = json.data?.rows ?? []
  } catch (e: any) {
    error.value = '诊断报告加载失败: ' + e.message
  } finally {
    reportsLoading.value = false
  }
}

async function loadConversations() {
  try {
    const res = await fetch('/api/ai/conversations')
    const json = await res.json()
    conversations.value = json.data?.rows ?? []
    if (conversations.value.length && !currentConv.value) {
      switchConv(conversations.value[0].id)
    }
  } catch (e: any) {
    error.value = '会话列表加载失败: ' + e.message
  }
}

async function createConversation(): Promise<number> {
  const res = await fetch('/api/ai/conversations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({})
  })
  const json = await res.json()
  const id = json.data?.id
  if (!id) throw new Error('会话创建失败')
  return id
}

async function newConversation() {
  try {
    const id = await createConversation()
    currentConv.value = id
    messages.value = []
    await loadConversations()
  } catch (e: any) {
    error.value = '新建会话失败: ' + e.message
  }
}

async function switchConv(id: number) {
  currentConv.value = id
  messages.value = []
  try {
    const res = await fetch(`/api/ai/conversations/${id}/messages`)
    const json = await res.json()
    messages.value = (json.data?.rows ?? []).map((m: ChatMsg) => ({
      role: m.role,
      content: m.content
    }))
  } catch (e: any) {
    error.value = '消息加载失败: ' + e.message
  }
}

async function send() {
  const text = input.value.trim()
  if (!text || streaming.value) return
  input.value = ''
  error.value = ''

  if (!currentConv.value) {
    currentConv.value = await createConversation()
  }

  messages.value.push({ role: 'user', content: text })
  const reply: ChatMsg = { role: 'assistant', content: '' }
  messages.value.push(reply)
  streaming.value = true

  const resp = await fetch('/api/ai/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ conversationId: currentConv.value, message: text })
  })
  if (!resp.ok || !resp.body) {
    error.value = '对话请求失败: HTTP ' + resp.status
    streaming.value = false
    return
  }
  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      const lines = buf.split('\n')
      buf = lines.pop() ?? ''
      for (const line of lines) {
        const trimmed = line.trim()
        if (!trimmed || trimmed.startsWith(':')) continue
        if (trimmed.startsWith('data:')) {
          const data = trimmed.slice(5).trim()
          if (data === '[DONE]') continue
          reply.content += data
          scrollToBottom()
        }
      }
    }
  } finally {
    reader.releaseLock()
    streaming.value = false
  }
}

const scrollBox = ref<HTMLElement | null>(null)
function scrollToBottom() {
  requestAnimationFrame(() => {
    if (scrollBox.value) scrollBox.value.scrollTop = scrollBox.value.scrollHeight
  })
}
</script>

<template>
  <div class="ai-panel h-full flex flex-col">
    <div class="flex items-center gap-2 p-3 border-b border-base-300">
      <h2 class="text-lg font-bold">AI 运维助手</h2>
      <div class="tabs tabs-boxed tabs-sm">
        <a :class="['tab', activeTab === 'chat' ? 'tab-active' : '']" @click="activeTab = 'chat'">对话</a>
        <a :class="['tab', activeTab === 'diagnosis' ? 'tab-active' : '']" @click="activeTab = 'diagnosis'">诊断报告</a>
      </div>
      <span class="flex-1" />
      <template v-if="activeTab === 'chat'">
        <select
          class="select select-sm select-bordered max-w-64"
          :value="currentConv ?? ''"
          @change="switchConv(Number(($event.target as HTMLSelectElement).value))"
        >
          <option v-for="c in conversations" :key="c.id" :value="c.id">{{ c.title }} (#{{ c.id }})</option>
        </select>
        <button class="btn btn-sm btn-outline" @click="newConversation">新会话</button>
      </template>
    </div>

    <template v-if="activeTab === 'chat'">
      <div ref="scrollBox" class="flex-1 overflow-y-auto p-4 space-y-3 bg-base-200/40">
      <div v-if="!messages.length" class="text-center text-sm text-base-content/50 mt-10">
        询问应用运行状态,例如:
        <div class="mt-2 space-y-1">
          <div class="chat chat-start"><div class="chat-bubble chat-bubble-info">app-1 过去1小时错误率怎么样?</div></div>
          <div class="chat chat-start"><div class="chat-bubble chat-bubble-info">查询 app-1 的 JVM 堆内存最新值</div></div>
          <div class="chat chat-start"><div class="chat-bubble chat-bubble-info">当前有哪些未恢复的告警?</div></div>
        </div>
      </div>
      <div v-for="(m, i) in messages" :key="i" :class="m.role === 'user' ? 'chat chat-end' : 'chat chat-start'">
        <div class="chat-bubble whitespace-pre-wrap" :class="m.role === 'user' ? 'chat-bubble-primary' : 'chat-bubble-neutral'">
          {{ m.content || (streaming ? '思考中...' : '') }}
        </div>
      </div>
    </div>

    <div class="p-3 border-t border-base-300">
        <div v-if="error" class="text-sm text-error mb-2">{{ error }}</div>
        <div class="flex gap-2">
          <textarea
            v-model="input"
            class="textarea textarea-bordered flex-1 resize-none"
            rows="2"
            placeholder="输入问题,回车发送(Shift+Enter 换行)"
            :disabled="streaming"
            @keydown.enter.exact.prevent="send"
          />
          <button class="btn btn-primary" :disabled="streaming || !input.trim()" @click="send">
            {{ streaming ? '生成中...' : '发送' }}
          </button>
        </div>
      </div>
    </template>

    <template v-else>
      <div class="flex-1 overflow-y-auto p-4 space-y-3 bg-base-200/40">
        <div v-if="!appStore.currentAppid" class="text-center text-sm text-base-content/50 mt-10">
          请先在顶部选择要查看诊断报告的应用
        </div>
        <div v-else-if="reportsLoading" class="text-center text-sm text-base-content/50 mt-10">加载中...</div>
        <div v-else-if="!reports.length" class="text-center text-sm text-base-content/50 mt-10">
          暂无诊断报告(告警 FIRING 时自动生成)
        </div>
        <div v-for="r in reports" :key="r.id" class="card bg-base-100 shadow-sm">
          <div class="card-body p-4">
            <div class="flex items-center gap-2">
              <span class="font-bold text-sm">{{ r.ruleName }}</span>
              <span v-if="r.alertLevel" class="badge badge-sm" :class="r.alertLevel === 'CRITICAL' ? 'badge-error' : r.alertLevel === 'WARNING' ? 'badge-warning' : 'badge-info'">
                {{ r.alertLevel }}
              </span>
              <span class="badge badge-sm" :class="r.status === 'success' ? 'badge-success' : 'badge-warning'">
                {{ r.status === 'success' ? '已诊断' : '降级' }}
              </span>
              <span class="flex-1" />
              <span class="text-xs text-base-content/50">{{ r.createdAt }}</span>
            </div>
            <div v-if="r.triggerMetric" class="text-xs text-base-content/50">
              指标: {{ r.triggerMetric }} = {{ r.triggerValue }}
            </div>
            <div class="text-sm whitespace-pre-wrap mt-1">{{ r.report }}</div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.ai-panel {
  min-height: 100%;
}
</style>