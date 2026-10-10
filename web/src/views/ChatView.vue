<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, chatStream, type Conversation, type MemoryItem, type Source } from '../api/client'
import { MEMORY_TYPE_OPTIONS, memoryTypeLabel } from './memoryType'

const emit = defineEmits<{ (e: 'changed'): void }>()

/**
 * 当前会话 id 在 localStorage 里的键名。
 *
 * 为什么必须持久化（这是一个真实故障）：第一版把 currentId 只放在组件内存里。
 * 用户在界面上切换模型来源时，App.vue 会 location.reload() 让新模型生效 ——
 * 刷新后组件重新挂载，currentId 变回 null，于是下一条问题开了个新会话：
 * 前几轮对话全在旧会话里，模型自然接不上话，
 * 用户看到的就是"切换到云端/本地之后上下文记忆丢了"。
 *
 * 多轮会话记忆在后端是按 conversationId 存的，所以只要 id 还在，
 * 刷新、重启、切模型都不影响上下文连续性。
 */
const STORAGE_KEY = 'qa.conversationId'
const LEGACY_KEY = 'qa.conversation.id'

/** 记忆内容上限，与后端 MemoryCreateRequest 的 @Size(max = 500) 对齐。 */
const MEMORY_MAX_CHARS = 500

/** 「记住这条」面板里的类型选项（与记忆管理页共用同一份文案）。 */
const TYPE_OPTIONS = MEMORY_TYPE_OPTIONS

interface Bubble {
  role: 'user' | 'assistant'
  content: string
  sources?: Source[]
  streaming?: boolean
  elapsedMs?: number
  /**
   * 引用来源是否展开。
   *
   * 默认收起：一次提问会命中 topK=8 条片段，每条带文件名、相似度与三行摘录，
   * 展开后轻松占掉两三屏 —— 真正要读的回答正文反而被挤到看不见。
   * 收起状态保留"引用来源（N）· 耗时 X ms"这一行，信息不丢、只把体积收起来。
   */
  sourcesOpen?: boolean
  /** 「记住这条」面板是否展开 */
  rememberOpen?: boolean
  /** 面板里的内容，默认取回答正文 */
  rememberContent?: string
  /** 面板里选的类型，空串 = 交给后端自动判定 */
  rememberType?: MemoryItem['memoryType'] | ''
  rememberImportance?: number
  rememberSaving?: boolean
}

const conversations = ref<Conversation[]>([])
const currentId = ref<string | null>(null)
const bubbles = ref<Bubble[]>([])
const question = ref('')
const sending = ref(false)
const listRef = ref<HTMLElement | null>(null)

// 会话切换即落盘：刷新页面前不需要任何"保存"动作
watch(currentId, id => {
  try {
    if (id) {
      localStorage.setItem(STORAGE_KEY, id)
    } else {
      localStorage.removeItem(STORAGE_KEY)
    }
  } catch {
    // 隐私模式等场景下 localStorage 不可用；记忆功能退化为"仅本次页面有效"，不影响问答
  }
})

function readStoredId(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY) ?? localStorage.getItem(LEGACY_KEY)
  } catch {
    return null
  }
}

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
    // 用户主动点开会话：强制到底，他要看的就是最新那一轮
    await scrollToBottom(true)
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
  // 显式清一次：currentId 本来就是 null 时 watch 不会触发，
  // 而旧会话 id 若留在 localStorage 里，下次刷新会把上一个会话又恢复出来
  clearStoredId()
}

function clearStoredId() {
  try {
    localStorage.removeItem(STORAGE_KEY)
    localStorage.removeItem(LEGACY_KEY)
  } catch {
    // 同 watch：localStorage 不可用时静默降级
  }
}

async function handleDelete(id: string) {
  await ElMessageBox.confirm('删除会话会同时清除它的消息，且不可恢复。', '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  try {
    await api.deleteConversation(id)
    if (currentId.value === id) {
      startNewConversation()
    } else if (readStoredId() === id) {
      // 删掉的正好是"刷新后要恢复的那个"会话，落盘值也得跟着清
      clearStoredId()
    }
    await loadConversations()
    emit('changed')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

/**
 * 消息区当前是否"贴着底部"。
 *
 * <h2>为什么必须记这个状态</h2>
 * 外壳锁死视口高度之后，消息区是<b>唯一</b>可滚动的地方。此时如果每次增量都无条件
 * 把 scrollTop 顶到底，会出现一个很难受的体验：用户往上翻去读刚生成的中间段落，
 * 下一个 delta 到达就把他拽回底部 —— 长回答基本没法读。
 * 所以只在"用户原本就在底部"时才继续跟随。
 */
let stickToBottom = true

/** 距底部多少像素以内算"还在底部"（留一点余量，避免像素级抖动就把跟随关掉）。 */
const STICK_THRESHOLD_PX = 48

function onBubblesScroll() {
  const el = listRef.value
  if (!el) {
    return
  }
  stickToBottom = el.scrollHeight - el.scrollTop - el.clientHeight <= STICK_THRESHOLD_PX
}

/**
 * 滚到底部。
 *
 * @param force 忽略"是否贴底"，强制滚到底 —— 用户主动发问、切换会话时用，
 *              因为那两种情况用户明确期望看到最新内容
 */
async function scrollToBottom(force = false) {
  await nextTick()
  const el = listRef.value
  if (el && (force || stickToBottom)) {
    el.scrollTop = el.scrollHeight
    stickToBottom = true
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
  // 用户刚发出提问：无论此前滚到哪里，都强制拉到底并恢复跟随
  await scrollToBottom(true)

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

/**
 * 把历史版本可能写过的旧键迁到当前键。
 *
 * 键名只应有一个来源（STORAGE_KEY），旧键留着会让"到底读哪个"变成隐患，
 * 所以迁移后立刻删掉。
 */
function migrateLegacyKey() {
  try {
    const legacy = localStorage.getItem(LEGACY_KEY)
    if (legacy && !localStorage.getItem(STORAGE_KEY)) {
      localStorage.setItem(STORAGE_KEY, legacy)
    }
    localStorage.removeItem(LEGACY_KEY)
  } catch {
    // 忽略：迁移失败不影响后续流程
  }
}

/**
 * 恢复上次的会话，让"刷新/切换模型"之后上下文继续接得上。
 *
 * 恢复是尽力而为：会话可能已被删掉（接口会返回空列表），
 * 这时保持新会话状态即可，不报错也不清 id ——
 * 后端把会话记忆按 conversationId 存在同一个库里，
 * 数据还在时继续用它才能接上上下文。
 */
async function restoreLastConversation() {
  const id = readStoredId()
  if (!id) {
    return
  }
  currentId.value = id
  try {
    const messages = await api.listMessages(id)
    bubbles.value = messages.map(m => ({
      role: m.role === 'user' ? 'user' : 'assistant',
      content: m.content
    }))
    // 刷新/切模型后恢复会话：同样强制到底，让用户一眼看到最近的往来
    await scrollToBottom(true)
  } catch {
    // 拉历史失败不阻断提问：给出提示，让用户自己决定是否新开对话
    ElMessage.warning('未能恢复上次会话的历史消息，可以直接继续提问')
  }
}

/**
 * 打开/收起某条回答下的「记住这条」面板。
 *
 * 为什么这个按钮必须长在回答旁边，而不是只留在"记忆管理"页：
 * 用户真正想说"这个以后要按这么办"的时刻，就是刚看完这条回答的那一秒。
 * 让他切到另一个页面、再从零敲一遍内容，实际结果是没人会用，
 * 长期记忆就只剩模型自动抽取那一条（还会抽错）路径。
 *
 * @param b 目标回答气泡
 */
function toggleRemember(b: Bubble) {
  b.rememberOpen = !b.rememberOpen
  if (b.rememberOpen) {
    b.rememberType = ''
    b.rememberImportance = 4
    if (b.rememberContent === undefined) {
      b.rememberContent = b.content.slice(0, MEMORY_MAX_CHARS)
    }
  }
}

/**
 * 保存「记住这条」。
 *
 * 类型留空即自动判定；判定结果会回显给用户看 —— 正文默认截断到 500 字（后端上限），
 * 超长回答直接提交会被参数校验挡下，那时候用户看到的是一个莫名其妙的报错。
 *
 * @param b 目标回答气泡
 */
async function saveMemory(b: Bubble) {
  const content = (b.rememberContent ?? '').trim()
  if (!content) {
    ElMessage.warning('内容不能为空')
    return
  }
  b.rememberSaving = true
  try {
    const saved = await api.createMemory(content, b.rememberImportance, b.rememberType || undefined)
    ElMessage.success(b.rememberType
        ? '已记住，下个会话开始生效'
        : `已记住（自动判定为「${memoryTypeLabel(saved.memoryType)}」），下个会话开始生效`)
    b.rememberOpen = false
    emit('changed')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    b.rememberSaving = false
  }
}

onMounted(async () => {
  migrateLegacyKey()
  await loadConversations()
  await restoreLastConversation()
})
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
      <!-- 监听滚动以判断"用户是否还在底部"，决定流式输出要不要继续跟随 -->
      <div ref="listRef" class="bubbles" @scroll.passive="onBubblesScroll">
        <el-empty
            v-if="bubbles.length === 0"
            description="提问试试 —— 回答只依据你上传的文档，资料里没有就会如实说找不到"
            :image-size="80" />

        <div v-for="(b, i) in bubbles" :key="i" class="bubble-row" :class="b.role">
          <div class="bubble">
            <div class="preserve-whitespace">{{ b.content }}<span v-if="b.streaming" class="caret">▍</span></div>

            <!--
              引用来源做成折叠面板：默认收起，点标题行才展开。
              收起时那一行把"几条 + 耗时"都写清楚，所以不会因为折叠而丢信息。
            -->
            <div v-if="b.sources && b.sources.length" class="sources">
              <div class="sources-toggle" @click="b.sourcesOpen = !b.sourcesOpen">
                <span class="sources-caret">{{ b.sourcesOpen ? '▾' : '▸' }}</span>
                <span>引用来源（{{ b.sources.length }}）</span>
                <span v-if="b.elapsedMs != null" class="sources-elapsed">· 耗时 {{ b.elapsedMs }} ms</span>
                <span class="sources-action">{{ b.sourcesOpen ? '收起' : '展开' }}</span>
              </div>

              <div v-if="b.sourcesOpen" class="sources-body">
                <div v-for="(s, si) in b.sources" :key="si" class="source">
                  <div class="source-head">
                    <el-tag size="small" type="info">{{ s.fileName || '未知文档' }}</el-tag>
                    <span class="score mono" v-if="s.score != null">相似度 {{ s.score.toFixed(3) }}</span>
                  </div>
                  <div class="source-text">{{ s.snippet }}</div>
                </div>
              </div>
            </div>

            <!-- 记住这条：只在回答完成后出现（生成中按钮会闪，反而干扰阅读） -->
            <div v-if="b.role === 'assistant' && !b.streaming && b.content" class="remember">
              <el-button size="small" text type="primary" @click="toggleRemember(b)">
                {{ b.rememberOpen ? '取消' : '记住这条' }}
              </el-button>

              <div v-if="b.rememberOpen" class="remember-panel">
                <el-input
                    v-model="b.rememberContent"
                    type="textarea"
                    :rows="3"
                    resize="none"
                    :maxlength="MEMORY_MAX_CHARS"
                    show-word-limit />
                <div class="remember-row">
                  <el-select v-model="b.rememberType" size="small" style="width: 170px">
                    <el-option
                        v-for="opt in TYPE_OPTIONS"
                        :key="opt.value"
                        :label="opt.label"
                        :value="opt.value" />
                  </el-select>
                  <el-rate v-model="b.rememberImportance" :max="5" size="small" />
                  <el-button
                      size="small"
                      type="primary"
                      :loading="b.rememberSaving"
                      @click="saveMemory(b)">保存到长期记忆</el-button>
                </div>
                <div class="remember-hint">
                  长期记忆在<b>下一个新会话</b>开始时注入；当前会话靠上面的多轮上下文。
                  写"每次…/请…/不要…"这类要求时，类型选<b>偏好</b>才会被当作硬性要求。
                </div>
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
  /*
   * 高度由父容器分配，不再用 calc(100vh - 100px) 猜。
   *
   * 原来那 100px 是按"页头 56 + 一点余量"估的，而实际占掉的是
   * 页头 56px + el-main 上下各 18px 内边距 + 卡片自身内边距 —— 加起来超过 100px，
   * 于是整页比视口高出一截，输入框落在屏幕外，必须先下滑才能打字。
   * 估高度这件事总会随界面微调而失效（改个内边距就又对不上），
   * 所以改成"父容器给多少就用多少"：外壳锁死视口高度，这里 flex:1 自适应。
   */
  flex: 1;
  min-height: 0;
}

.side {
  width: 230px;
  flex: 0 0 230px;
  display: flex;
  flex-direction: column;
  min-height: 0;
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
  min-height: 0;
}

.main :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 0;
  /* 同 .app-main > *：flex 里的滚动容器必须归零 min-height，否则内部滚动不生效 */
  min-height: 0;
}

.bubbles {
  flex: 1;
  /* 归零之后这个 overflow 才真正生效：消息多时只有消息区滚动，输入框不动 */
  min-height: 0;
  overflow-y: auto;
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

/* 折叠标题行：整行可点，hover 时给一点反馈，让人知道它是可展开的 */
.sources-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #6b7280;
  cursor: pointer;
  user-select: none;
  padding: 2px 0;
  border-radius: 4px;
}

.sources-toggle:hover {
  color: #2e5496;
  background: #f5f7fb;
}

.sources-caret {
  width: 12px;
  font-size: 11px;
  color: #9aa0a6;
}

.sources-elapsed {
  color: #9aa0a6;
}

.sources-action {
  margin-left: auto;
  color: #2e5496;
  font-size: 11px;
}

/* 展开后的片段列表：给个上限高度，条数多时内部滚动，不再把正文顶走 */
.sources-body {
  margin-top: 8px;
  max-height: 320px;
  overflow: auto;
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

.remember {
  margin-top: 6px;
  border-top: 1px dashed #e3e6eb;
  padding-top: 4px;
}

.remember-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px 0 4px;
}

.remember-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.remember-hint {
  font-size: 11px;
  line-height: 1.7;
  color: #9aa0a6;
}

.composer {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  padding: 12px 16px;
  border-top: 1px solid #e3e6eb;
  background: #fff;
  /* 输入区永不被压缩：它是这块区域里唯一必须始终可见的部件 */
  flex-shrink: 0;
}
</style>
