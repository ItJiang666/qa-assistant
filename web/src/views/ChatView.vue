<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, chatStream, type Conversation, type Source } from '../api/client'

const emit = defineEmits<{ (e: 'changed'): void }>()

interface Bubble {
  role: 'user' | 'assistant'
  content: string
  sources?: Source[]
  streaming?: boolean
  elapsedMs?: number
}

const conversations = ref<Conversation[]>([])
const currentId = ref<string | null>(null)
const bubbles = ref<Bubble[]>([])
const question = ref('')
const sending = ref(false)
const listRef = ref<HTMLElement | null>(null)

async function loadConversations() {
  try {
    conversations.value = await api.listConversations()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

/** 打开一个历史会话：消息从服务端拉，长期记忆由后端按 user_id 自动带上。 */
async function openConversation(id: string) {
  if (sending.value) {
    ElMessage.warning('正在生成回答，请稍候再切换会话')
    return
  }
  currentId.value = id
  bubbles.value = []
  try {
    const messages = await api.listMessages(id)
    bubbles.value = messages.map(m => ({
      role: m.role === 'user' ? 'user' : 'assistant',
      content: m.content
    }))
    await scrollToBottom()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

function startNewConversation() {
  if (sending.value) {
    ElMessage.warning('正在生成回答，请稍候')
    return
  }
  currentId.value = null
  bubbles.value = []
}

async function handleDelete(id: string) {
  await ElMessageBox.confirm('删除会话会同时清除它的消息，且不可恢复。', '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  try {
    await api.deleteConversation(id)
    if (currentId.value === id) {
      startNewConversation()
    }
    await loadConversations()
    emit('changed')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

async function scrollToBottom() {
  await nextTick()
  const el = listRef.value
  if (el) {
    el.scrollTop = el.scrollHeight
  }
}

/**
 * 流式增量专用：节流滚动。
 *
 * 模型每秒会产出几十个 delta，如果每个 delta 都 nextTick + 写 scrollTop，
 * 浏览器每帧都要重排一次，长回答下会明显掉帧。这里限制最多 80ms 滚一次，
 * 视觉上依旧跟手，重排次数降到十分之一左右。
 */
const SCROLL_THROTTLE_MS = 80
let scrollTimer: ReturnType<typeof setTimeout> | null = null

function scrollToBottomThrottled() {
  if (scrollTimer !== null) {
    return
  }
  scrollTimer = setTimeout(() => {
    scrollTimer = null
    void scrollToBottom()
  }, SCROLL_THROTTLE_MS)
}

// 组件卸载后定时器仍可能触发，届时 listRef 已失效；清掉避免操作已销毁的 DOM
onBeforeUnmount(() => {
  if (scrollTimer !== null) {
    clearTimeout(scrollTimer)
    scrollTimer = null
  }
})

async function send() {
  const text = question.value.trim()
  if (!text || sending.value) {
    return
  }
  question.value = ''
  sending.value = true

  bubbles.value.push({ role: 'user', content: text })
  bubbles.value.push({ role: 'assistant', content: '', streaming: true })
  // 关键：必须回读数组里的元素（Vue 的响应式代理）再改它。
  // 若攥着 push 进去的那个原始对象改，set 不会触发依赖通知，
  // 界面就只在最后被别的事件顺带刷新一次 —— 表现为"没有流式输出"。
  const answer = bubbles.value[bubbles.value.length - 1]
  await scrollToBottom()

  try {
    await chatStream(text, currentId.value, {
      onMeta: id => {
        currentId.value = id
      },
      onDelta: chunk => {
        answer.content += chunk
        scrollToBottomThrottled()
      },
      onDone: payload => {
        answer.streaming = false
        answer.sources = payload.sources
        answer.elapsedMs = payload.elapsedMs
        if (payload.conversationId) {
          currentId.value = payload.conversationId
        }
      },
      onError: message => {
        answer.streaming = false
        answer.content = answer.content || `（生成失败：${message}）`
        ElMessage.error(message)
      }
    })
  } catch (e) {
    answer.streaming = false
    answer.content = answer.content || '（请求异常）'
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    answer.streaming = false
    sending.value = false
    await loadConversations()
    emit('changed')
  }
}

onMounted(loadConversations)
</script>

<template>
  <div class="chat-layout">
    <el-card shadow="never" class="side">
      <template #header>
        <div class="side-header">
          <span>会话</span>
          <el-button size="small" type="primary" text @click="startNewConversation">新对话</el-button>
        </div>
      </template>

      <el-empty v-if="conversations.length === 0" description="还没有会话" :image-size="60" />

      <div v-else class="conv-list">
        <div
            v-for="c in conversations"
            :key="c.id"
            class="conv-item"
            :class="{ active: c.id === currentId }"
            @click="openConversation(c.id)">
          <div class="conv-title">{{ c.title || '未命名会话' }}</div>
          <div class="conv-meta">
            <span>{{ c.updatedAt?.replace('T', ' ').slice(5, 16) }}</span>
            <el-button size="small" text type="danger" @click.stop="handleDelete(c.id)">删除</el-button>
          </div>
        </div>
      </div>
    </el-card>

    <el-card shadow="never" class="main">
      <div ref="listRef" class="bubbles">
        <el-empty
            v-if="bubbles.length === 0"
            description="提问试试 —— 回答只依据你上传的文档，资料里没有就会如实说找不到"
            :image-size="80" />

        <div v-for="(b, i) in bubbles" :key="i" class="bubble-row" :class="b.role">
          <div class="bubble">
            <div class="preserve-whitespace">{{ b.content }}<span v-if="b.streaming" class="caret">▍</span></div>

            <div v-if="b.sources && b.sources.length" class="sources">
              <div class="sources-title">
                引用来源（{{ b.sources.length }}）· 耗时 {{ b.elapsedMs }} ms
              </div>
              <div v-for="(s, si) in b.sources" :key="si" class="source">
                <div class="source-head">
                  <el-tag size="small" type="info">{{ s.fileName || '未知文档' }}</el-tag>
                  <span class="score mono" v-if="s.score != null">相似度 {{ s.score.toFixed(3) }}</span>
                </div>
                <div class="source-text">{{ s.snippet }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="composer">
        <el-input
            v-model="question"
            type="textarea"
            :rows="2"
            resize="none"
            placeholder="问点什么…（Enter 发送，Shift+Enter 换行）"
            @keydown.enter.exact.prevent="send" />
        <el-button type="primary" :loading="sending" @click="send">发送</el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.chat-layout {
  display: flex;
  gap: 14px;
  height: calc(100vh - 100px);
}

.side {
  width: 230px;
  flex: 0 0 230px;
  display: flex;
  flex-direction: column;
}

.side :deep(.el-card__body) {
  overflow: auto;
  padding: 8px;
}

.side-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.conv-item {
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  margin-bottom: 4px;
}

.conv-item:hover {
  background: #f2f5fa;
}

.conv-item.active {
  background: #e8effa;
}

.conv-title {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: #9aa0a6;
  margin-top: 2px;
}

.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.main :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 0;
}

.bubbles {
  flex: 1;
  overflow: auto;
  padding: 16px;
}

.bubble-row {
  display: flex;
  margin-bottom: 12px;
}

.bubble-row.user {
  justify-content: flex-end;
}

.bubble {
  max-width: 82%;
  padding: 10px 14px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid #e3e6eb;
  line-height: 1.75;
}

.bubble-row.user .bubble {
  background: #2e5496;
  color: #fff;
  border-color: #2e5496;
}

.caret {
  animation: blink 1s step-start infinite;
}

@keyframes blink {
  50% { opacity: 0; }
}

.sources {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed #e3e6eb;
}

.sources-title {
  font-size: 12px;
  color: #9aa0a6;
  margin-bottom: 6px;
}

.source {
  background: #fafbfc;
  border: 1px solid #eef0f3;
  border-radius: 6px;
  padding: 8px 10px;
  margin-bottom: 6px;
}

.source-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.score {
  font-size: 11px;
  color: #9aa0a6;
}

.source-text {
  font-size: 12px;
  color: #5b6068;
  line-height: 1.7;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.composer {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  padding: 12px 16px;
  border-top: 1px solid #e3e6eb;
  background: #fff;
}
</style>
