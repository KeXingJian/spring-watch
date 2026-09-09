<script setup lang="ts">
import { onMounted, ref, watch, nextTick } from 'vue'
import { useAppStore } from '@/stores/app'
import { useToast } from '@/utils/toast'
import { formatTime } from '@/utils/format'
import EmptyState from '@/components/EmptyState.vue'
import Markdown from '@/components/Markdown.vue'

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
const toast = useToast()

const conversations = ref<Conversation[]>([])
const currentConv = ref<number | null>(null)
const messages = ref<ChatMsg[]>([])
const input = ref('')
const streaming = ref(false)
const error = ref('')
const activeTab = ref<'chat' | 'diagnosis'>('chat')
const reports = ref<DiagnosisReport[]>([])
const reportsLoading = ref(false)
const expandedReport = ref<Record<number, boolean>>({})

const examplePrompts = [
  'app-1 过去1小时错误率怎么样?',
  '查询 app-1 的 JVM 堆内存最新值',
  '当前有哪些未恢复的告警?'
]

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
  error.value = ''
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
    toast.success('已创建新会话')
  } catch (e: any) {
    toast.error('新建会话失败: ' + e.message)
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
    try {
      currentConv.value = await createConversation()
    } catch (e: any) {
      error.value = '创建会话失败: ' + e.message
      return
    }
  }

  messages.value.push({ role: 'user', content: text })
  const reply: ChatMsg = { role: 'assistant', content: '' }
  messages.value.push(reply)
  streaming.value = true
  await scrollToBottom()

  try {
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

    // 处理一条 SSE data 行(JSON 事件流,协议同 HertzBeat /api/chat/stream)
    const applyData = async (raw: string) => {
      if (!raw || raw === '[DONE]') return
      let evt: any = null
      try {
        evt = JSON.parse(raw)
      } catch {
        evt = null
      }
      if (evt && typeof evt === 'object') {
        if (evt.type === 'message' && typeof evt.delta === 'string') {
          reply.content += evt.delta
        } else if (evt.type === 'error') {
          reply.content = (reply.content || '') + (evt.error || '')
          error.value = evt.error || '对话失败'
        }
      } else {
        reply.content += raw
      }
      await scrollToBottom()
    }

    const processLines = async (lines: string[]) => {
      for (const line of lines) {
        const trimmed = line.trim()
        if (!trimmed || trimmed.startsWith(':')) continue
        if (trimmed.startsWith('data:')) {
          await applyData(trimmed.slice(5).trim())
        }
      }
    }

    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      const lines = buf.split('\n')
      buf = lines.pop() ?? ''
      await processLines(lines)
    }
    // 冲刷末尾未换行收尾的残余数据(SSE 最后一帧可能无 \n)
    if (buf.trim()) {
      await processLines([buf])
    }
    reader.releaseLock()
    if (!reply.content) {
      reply.content = '(无响应内容)'
    }
  } catch (e: any) {
    error.value = '对话出错: ' + e.message
  } finally {
    streaming.value = false
    await scrollToBottom()
  }
}

function useExample(text: string) {
  input.value = text
}

function levelClass(level?: string): string {
  switch (level) {
    case 'CRITICAL': return 'critical'
    case 'WARNING': return 'warning'
    case 'INFO': return 'info'
    default: return 'info'
  }
}

function toggleReport(id: number) {
  expandedReport.value[id] = !expandedReport.value[id]
}

const scrollBox = ref<HTMLElement | null>(null)
async function scrollToBottom() {
  await nextTick()
  if (scrollBox.value) {
    scrollBox.value.scrollTop = scrollBox.value.scrollHeight
  }
}
</script>

<template>
  <div class="page-container ai-page">
    <div class="page-header">
      <h2>AI 运维助手</h2>
      <span class="meta">对话与告警诊断</span>
      <span class="spacer" />
      <div class="tabs">
        <a :class="['tab', activeTab === 'chat' ? 'active' : '']" @click="activeTab = 'chat'">对话</a>
        <a :class="['tab', activeTab === 'diagnosis' ? 'active' : '']" @click="activeTab = 'diagnosis'">诊断报告</a>
      </div>
    </div>

    <div class="ai-panel card bg-base-100 border border-base-300 shadow-sm">
      <div class="panel-toolbar">
        <template v-if="activeTab === 'chat'">
          <span class="label">当前会话:</span>
          <select
            class="select select-bordered select-sm conv-select"
            :value="currentConv ?? ''"
            @change="switchConv(Number(($event.target as HTMLSelectElement).value))"
          >
            <option v-if="!conversations.length" value="" disabled>暂无会话</option>
            <option v-for="c in conversations" :key="c.id" :value="c.id">#{{ c.id }} · {{ c.title }}</option>
          </select>
          <span class="right" />
          <button class="btn btn-ghost btn-sm" @click="loadConversations">
            <svg viewBox="0 0 20 20" fill="currentColor" class="w-4 h-4"><path fill-rule="evenodd" d="M4 2a1 1 0 0 1 1 1v2.101a7.002 7.002 0 0 1 11.601 2.566 1 1 0 1 1-1.885.666A5.002 5.002 0 0 0 5.999 7H9a1 1 0 0 1 0 2H4a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1zm.008 9.057a1 1 0 0 1 1.276.61A5.002 5.002 0 0 0 14.001 13H11a1 1 0 1 1 0 2h5a1 1 0 0 1 1 1v5a1 1 0 1 1-2 0v-2.1a7.002 7.002 0 0 1-11.601-2.566 1 1 0 0 1 .61-1.277z" clip-rule="evenodd"/></svg>
            刷新
          </button>
          <button class="btn btn-primary btn-sm" @click="newConversation">
            <svg viewBox="0 0 20 20" fill="currentColor" class="w-4 h-4"><path fill-rule="evenodd" d="M10 3a1 1 0 0 1 1 1v5h5a1 1 0 1 1 0 2h-5v5a1 1 0 1 1-2 0v-5H4a1 1 0 1 1 0-2h5V4a1 1 0 0 1 1-1z" clip-rule="evenodd"/></svg>
            新建会话
          </button>
        </template>
        <template v-else>
          <span class="label">诊断应用:</span>
          <span class="font-mono text-sm">{{ appStore.currentAppid || '未选择' }}</span>
          <span class="right" />
          <button class="btn btn-ghost btn-sm" @click="loadDiagnosis">
            <svg viewBox="0 0 20 20" fill="currentColor" class="w-4 h-4"><path fill-rule="evenodd" d="M4 2a1 1 0 0 1 1 1v2.101a7.002 7.002 0 0 1 11.601 2.566 1 1 0 1 1-1.885.666A5.002 5.002 0 0 0 5.999 7H9a1 1 0 0 1 0 2H4a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1zm.008 9.057a1 1 0 0 1 1.276.61A5.002 5.002 0 0 0 14.001 13H11a1 1 0 1 1 0 2h5a1 1 0 0 1 1 1v5a1 1 0 1 1-2 0v-2.1a7.002 7.002 0 0 1-11.601-2.566 1 1 0 0 1 .61-1.277z" clip-rule="evenodd"/></svg>
            刷新
          </button>
        </template>
      </div>

      <template v-if="activeTab === 'chat'">
        <div ref="scrollBox" class="chat-scroll">
          <div v-if="!messages.length" class="chat-empty">
            <EmptyState icon="💬">
              开始与 AI 助手对话,询问应用运行状态
              <div class="hint">点击下方示例快速开始</div>
              <div class="example-list">
                <button v-for="(p, i) in examplePrompts" :key="i" class="example-chip" @click="useExample(p)">
                  {{ p }}
                </button>
              </div>
            </EmptyState>
          </div>
          <div v-for="(m, i) in messages" :key="i" :class="['msg-row', m.role === 'user' ? 'msg-user' : 'msg-assistant']">
            <div class="avatar">
              <span class="avatar-dot">{{ m.role === 'user' ? 'U' : 'AI' }}</span>
            </div>
            <div class="msg-bubble">
              <div class="msg-meta">
                <span class="role">{{ m.role === 'user' ? '我' : 'AI 助手' }}</span>
                <span v-if="streaming && i === messages.length - 1 && m.role === 'assistant'" class="streaming-tag">
                  <span class="dot-pulse" />生成中
                </span>
              </div>
              <div class="msg-content">
                <template v-if="m.role === 'assistant'">
                  <Markdown v-if="m.content" :content="m.content" />
                  <span v-else-if="streaming && i === messages.length - 1" class="thinking-text">思考中...</span>
                </template>
                <template v-else>{{ m.content }}</template>
              </div>
            </div>
          </div>
        </div>

        <div class="chat-input-bar">
          <div v-if="error" class="chat-error">{{ error }}</div>
          <div class="chat-input-wrap">
            <textarea
              v-model="input"
              class="chat-textarea"
              rows="2"
              placeholder="输入问题,Enter 发送,Shift+Enter 换行"
              :disabled="streaming"
              @keydown.enter.exact.prevent="send"
            />
            <button class="btn btn-primary send-btn" :disabled="streaming || !input.trim()" @click="send">
              <span v-if="!streaming">
                <svg viewBox="0 0 20 20" fill="currentColor" class="w-4 h-4"><path d="M3.105 2.289a.75.75 0 0 0-.826.95l1.414 4.949A1.5 1.5 0 0 0 5.135 9.25H10a.75.75 0 0 1 0 1.5H5.135a1.5 1.5 0 0 0-1.442 1.062L2.28 16.76a.75.75 0 0 0 .826.95 28.897 28.897 0 0 0 15.293-7.155.75.75 0 0 0 0-1.114A28.897 28.897 0 0 0 3.105 2.289z"/></svg>
                发送
              </span>
              <span v-else class="loading-dots">
                <span /><span /><span />
              </span>
            </button>
          </div>
        </div>
      </template>

      <template v-else>
        <div class="diag-scroll">
          <EmptyState v-if="!appStore.currentAppid" icon="📋">
            请先在顶部选择要查看诊断报告的应用
          </EmptyState>
          <div v-else-if="reportsLoading" class="state-loading">
            <span class="loading loading-spinner loading-md text-primary" />
            <span>加载中...</span>
          </div>
          <EmptyState v-else-if="!reports.length" icon="📑">
            暂无诊断报告
            <div class="hint">告警 FIRING 时会自动生成诊断报告</div>
          </EmptyState>
          <div v-else class="report-list">
            <div v-for="r in reports" :key="r.id" class="report-card">
              <div class="report-head">
                <span class="report-title">{{ r.ruleName || '(未命名规则)' }}</span>
                <span v-if="r.alertLevel" :class="['badge-level', levelClass(r.alertLevel)]">{{ r.alertLevel }}</span>
                <span :class="['status-pill', r.status === 'success' ? 'ok' : 'warn']">
                  {{ r.status === 'success' ? '已诊断' : '降级' }}
                </span>
                <span class="spacer" />
                <span class="report-time">{{ formatTime(r.createdAt) }}</span>
              </div>
              <div v-if="r.triggerMetric" class="report-meta">
                <span class="meta-key">触发指标</span>
                <span class="font-mono">{{ r.triggerMetric }} = {{ r.triggerValue }}</span>
              </div>
              <div class="report-body" :class="{ collapsed: !expandedReport[r.id] && (r.report?.length || 0) > 280 }">
                <pre v-if="r.errorMsg" class="code compact">{{ r.errorMsg }}</pre>
                <Markdown v-else :content="r.report || ''" />
              </div>
              <div v-if="(r.report?.length || 0) > 280" class="report-foot">
                <button class="btn btn-ghost btn-xs" @click="toggleReport(r.id)">
                  {{ expandedReport[r.id] ? '收起' : '展开全文' }}
                </button>
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.ai-page :deep(.tabs) { margin-bottom: 0; }
.ai-page :deep(.page-header) { margin-bottom: 12px; }

.ai-panel {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 56px - 20px - 16px - 40px);
  min-height: 540px;
  overflow: hidden;
}

.panel-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid oklch(var(--b3));
  background: oklch(var(--b2) / 0.4);
  flex-wrap: wrap;
}
.panel-toolbar .label {
  color: var(--c-text-secondary);
  font-size: 0.82rem;
}
.panel-toolbar .conv-select { min-width: 220px; }
.panel-toolbar .right { margin-left: auto; }

.chat-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 18px 20px;
  background: oklch(var(--b2) / 0.3);
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.chat-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.example-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
  justify-content: center;
}
.example-chip {
  background: oklch(var(--b1));
  border: 1px dashed oklch(var(--bc) / 0.18);
  color: var(--c-text-secondary);
  padding: 6px 14px;
  border-radius: 9999px;
  font-size: 0.82rem;
  cursor: pointer;
  transition: all 0.15s;
}
.example-chip:hover {
  border-color: oklch(var(--p));
  color: oklch(var(--p));
  background: oklch(var(--p) / 0.06);
}

.msg-row {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  max-width: 78%;
}
.msg-user { margin-left: auto; flex-direction: row-reverse; }
.msg-assistant { margin-right: auto; }

.avatar { flex-shrink: 0; }
.avatar-dot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  font-size: 0.72rem;
  font-weight: 600;
  color: #fff;
  background: oklch(var(--bc) / 0.25);
}
.msg-user .avatar-dot { background: oklch(var(--p)); }
.msg-assistant .avatar-dot {
  background: linear-gradient(135deg, oklch(var(--in)), oklch(var(--p)));
}

.msg-bubble {
  background: oklch(var(--b1));
  border: 1px solid oklch(var(--b3));
  border-radius: 10px;
  padding: 10px 14px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
  min-width: 80px;
}
.msg-user .msg-bubble {
  background: oklch(var(--p));
  color: oklch(var(--pc));
  border-color: oklch(var(--p));
}
.msg-user .msg-bubble .role,
.msg-user .msg-bubble .msg-meta { color: oklch(var(--pc) / 0.85); }

.msg-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.72rem;
  color: var(--c-text-muted);
  margin-bottom: 4px;
}
.msg-meta .role { font-weight: 600; }

.streaming-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: oklch(var(--in));
}
.dot-pulse {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: oklch(var(--in));
  animation: pulse 1.2s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 0.35; transform: scale(0.85); }
  50% { opacity: 1; transform: scale(1.1); }
}

.msg-content {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 0.9rem;
  line-height: 1.55;
}
.thinking-text { color: oklch(var(--bc) / 0.45); font-style: italic; }

.chat-input-bar {
  border-top: 1px solid oklch(var(--b3));
  padding: 12px 16px;
  background: oklch(var(--b1));
}
.chat-error {
  color: oklch(var(--er));
  font-size: 0.82rem;
  margin-bottom: 8px;
  padding: 6px 10px;
  background: oklch(var(--er) / 0.08);
  border-radius: 6px;
  border-left: 3px solid oklch(var(--er));
}
.chat-input-wrap {
  display: flex;
  gap: 10px;
  align-items: flex-end;
}
.chat-textarea {
  flex: 1;
  resize: none;
  border: 1px solid oklch(var(--b3));
  border-radius: 8px;
  padding: 10px 12px;
  font-size: 0.9rem;
  background: oklch(var(--b1));
  font-family: inherit;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.chat-textarea:focus {
  outline: none;
  border-color: oklch(var(--p));
  box-shadow: 0 0 0 3px oklch(var(--p) / 0.1);
}
.send-btn { min-width: 88px; }
.send-btn svg { display: inline-block; vertical-align: middle; margin-right: 4px; }

.loading-dots { display: inline-flex; gap: 3px; }
.loading-dots span {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  animation: bounce 1.2s ease-in-out infinite;
}
.loading-dots span:nth-child(2) { animation-delay: 0.15s; }
.loading-dots span:nth-child(3) { animation-delay: 0.3s; }
@keyframes bounce {
  0%, 80%, 100% { opacity: 0.3; transform: translateY(0); }
  40% { opacity: 1; transform: translateY(-3px); }
}

.diag-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 16px 18px;
  background: oklch(var(--b2) / 0.3);
}
.state-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 60px 20px;
  color: var(--c-text-muted);
  font-size: 0.9rem;
}

.report-list { display: flex; flex-direction: column; gap: 12px; }
.report-card {
  background: oklch(var(--b1));
  border: 1px solid oklch(var(--b3));
  border-radius: 8px;
  padding: 14px 16px;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.report-card:hover { border-color: oklch(var(--p) / 0.4); box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04); }

.report-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.report-head .spacer { flex: 1; }
.report-title { font-weight: 600; font-size: 0.92rem; color: var(--c-text); }
.report-time { font-size: 0.78rem; color: var(--c-text-muted); font-family: var(--font-mono); }

.report-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  font-size: 0.82rem;
  color: var(--c-text-secondary);
}
.meta-key {
  color: var(--c-text-muted);
  font-size: 0.72rem;
  padding: 1px 6px;
  background: oklch(var(--b2));
  border-radius: 3px;
}

.report-body { margin-top: 10px; }
.report-body.collapsed :deep(.md-body) {
  display: -webkit-box;
  -webkit-line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.report-foot { margin-top: 8px; text-align: right; }
</style>
