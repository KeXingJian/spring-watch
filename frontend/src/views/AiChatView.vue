<script setup lang="ts">
import { onMounted, ref } from 'vue'

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

const conversations = ref<Conversation[]>([])
const currentConv = ref<number | null>(null)
const messages = ref<ChatMsg[]>([])
const input = ref('')
const streaming = ref(false)
const error = ref('')

onMounted(() => {
  loadConversations()
})

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
      <span class="badge badge-primary badge-sm">工具调用</span>
      <span class="flex-1" />
      <select
        class="select select-sm select-bordered max-w-64"
        :value="currentConv ?? ''"
        @change="switchConv(Number(($event.target as HTMLSelectElement).value))"
      >
        <option v-for="c in conversations" :key="c.id" :value="c.id">{{ c.title }} (#{{ c.id }})</option>
      </select>
      <button class="btn btn-sm btn-outline" @click="newConversation">新会话</button>
    </div>

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
  </div>
</template>

<style scoped>
.ai-panel {
  min-height: 100%;
}
</style>