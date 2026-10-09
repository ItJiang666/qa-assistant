/**
 * 后端接口客户端。
 *
 * 两件事集中在这里，别处不再重复：
 *  1. **匿名用户标识**（决策 19）：首次访问生成 UUID 存 localStorage，
 *     之后每个请求带 `X-User-Id`。长期记忆就是靠它归属到"人"——
 *     换浏览器等于换人，清掉 localStorage 等于重置记忆。
 *  2. **统一响应体解包**（决策 18）：`{code,message,data}`，
 *     code 非 0 一律抛 ApiError，调用方只需要 try/catch，不必逐层判断。
 */

const USER_ID_KEY = 'qa_user_id'

/** 取当前浏览器身份的匿名 UUID，不存在则生成。 */
export function getUserId(): string {
  let id = localStorage.getItem(USER_ID_KEY)
  if (!id) {
    id = typeof crypto !== 'undefined' && crypto.randomUUID
        ? crypto.randomUUID()
        : `u-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
    localStorage.setItem(USER_ID_KEY, id)
  }
  return id
}

/** 重置身份 —— 相当于"换个人用"，历史会话与长期记忆都不再可见。 */
export function resetUserId(): string {
  localStorage.removeItem(USER_ID_KEY)
  return getUserId()
}

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

/** 业务错误：code 为后端错误码，message 可直接展示给用户。 */
export class ApiError extends Error {
  constructor(public readonly code: number, message: string) {
    super(message)
    this.name = 'ApiError'
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'X-User-Id': getUserId(),
    ...(init.headers as Record<string, string> | undefined)
  }
  // FormData 必须让浏览器自己带 boundary，手动设 Content-Type 反而会坏
  if (init.body && !(init.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json'
  }

  const resp = await fetch(path, { ...init, headers })
  const text = await resp.text()

  let body: ApiResponse<T>
  try {
    body = JSON.parse(text)
  } catch {
    throw new ApiError(resp.status, `服务端响应异常（HTTP ${resp.status}）`)
  }
  if (body.code !== 0) {
    throw new ApiError(body.code, body.message || `请求失败（HTTP ${resp.status}）`)
  }
  return body.data
}

// ── 类型定义 ────────────────────────────────────────────────────────────

export interface SystemStatus {
  runMode: string
  chatModel: string
  embeddingModel: string
  numCtx: string
  dataDir: string
  database: string
  vectorStore: string
  documentCount: number
  chunkCount: number
  memoryCount: number
  userIdentified: boolean
  userId: string | null
  // ── P4：模型来源相关 ──────────────────────────────────────────────
  /** 配置里的默认模式（重启后回到的值）。 */
  configuredRunMode: string
  /** 当前模式是否与配置不同（即被运行时改过）。 */
  modeOverridden: boolean
  /** 向量模型所属模式（只读，换它需重启并重建索引）。 */
  embeddingRunMode: string
  cloudProvider: string | null
  cloudChatModel: string | null
  cloudEmbeddingModel: string | null
  /** 云端对话端点是否可用（Key 与模型名齐备）。 */
  cloudReady: boolean
  /** 云端不可用的原因；可用时为 null。 */
  cloudBlockedReason: string | null
  /** 索引与当前向量模型不一致的提示；一致时为 null。 */
  indexWarning: string | null
}

/** 连通性自检的单项结果。 */
export interface CloudCheckItem {
  target: string
  label: string
  url: string
  model: string
  reachable: boolean
  status: number | null
  latencyMs: number
  ok: boolean
  message: string
}

export interface DocumentItem {
  id: number
  fileName: string
  fileType: string
  fileSize: number
  chunkCount: number
  status: 'PENDING' | 'PARSING' | 'INDEXED' | 'FAILED'
  errorMessage: string | null
  embeddingModel: string
  createdAt: string
  updatedAt: string
}

export interface UploadResult {
  id: number
  fileName: string
  fileSize: number
  status: string
}

export interface Conversation {
  id: string
  title: string
  runMode: string
  createdAt: string
  updatedAt: string
}

export interface MessageItem {
  id: number
  role: string
  content: string
  sourcesJson: string | null
  createdAt: string
}

export interface MemoryItem {
  id: number
  memoryType: 'PREFERENCE' | 'FACT' | 'CONCLUSION'
  content: string
  importance: number
  confidence: number
  sourceConversationId: string | null
  hitCount: number
  updatedAt: string
}

export interface Source {
  documentId: number | null
  fileName: string | null
  snippet: string
  score: number | null
}

export interface ChatDone {
  conversationId: string
  elapsedMs: number
  model: string
  sources: Source[]
}

// ── 接口封装 ────────────────────────────────────────────────────────────

export const api = {
  systemStatus: () => request<SystemStatus>('/api/v1/system/status'),

  /**
   * 切换对话模式（本地 ⇄ 云端）。
   *
   * 只切对话模型：向量模型的来源在启动时定死（换它等于换向量空间，必须重建索引）。
   * 返回切换后的完整状态，调用方不必再请求一次 status。
   */
  switchChatMode: (mode: 'local' | 'cloud') =>
      request<SystemStatus>('/api/v1/system/chat-mode', {
        method: 'POST',
        body: JSON.stringify({ mode })
      }),

  /** 云端连通性自检：不消耗生成额度，用于区分"Key 不对"与"网络不通"。 */
  cloudCheck: () => request<{ items: CloudCheckItem[] }>(
      '/api/v1/system/cloud-check', { method: 'POST' }),

  listDocuments: (page = 1, size = 50) =>
      request<{ total: number; items: DocumentItem[] }>(`/api/v1/documents?page=${page}&size=${size}`),

  uploadDocument: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<UploadResult>('/api/v1/documents/upload', { method: 'POST', body: form })
  },

  deleteDocument: (id: number) => request<void>(`/api/v1/documents/${id}`, { method: 'DELETE' }),

  reindex: () => request<{ reindexed: number; embeddingModel: string }>(
      '/api/v1/documents/reindex', { method: 'POST' }),

  listConversations: () => request<Conversation[]>('/api/v1/conversations'),

  listMessages: (id: string) => request<MessageItem[]>(`/api/v1/conversations/${id}/messages`),

  deleteConversation: (id: string) =>
      request<void>(`/api/v1/conversations/${id}`, { method: 'DELETE' }),

  listMemories: () => request<{ total: number; items: MemoryItem[] }>('/api/v1/memories'),

  createMemory: (content: string, importance?: number) =>
      request<MemoryItem>('/api/v1/memories', {
        method: 'POST',
        body: JSON.stringify({ content, importance })
      }),

  updateMemory: (id: number, content?: string, importance?: number) =>
      request<MemoryItem>(`/api/v1/memories/${id}`, {
        method: 'PUT',
        body: JSON.stringify({ content, importance })
      }),

  deleteMemory: (id: number) => request<void>(`/api/v1/memories/${id}`, { method: 'DELETE' }),

  clearMemories: () => request<{ deleted: number }>('/api/v1/memories', { method: 'DELETE' })
}

/**
 * 流式问答。
 *
 * 用 `fetch` + ReadableStream 手写 SSE 解析，而不是 `EventSource`：
 * EventSource 只支持 GET，无法携带 JSON 请求体；把问题塞进 URL 既难看又有长度限制。
 */
export async function chatStream(
    question: string,
    conversationId: string | null,
    handlers: {
      onMeta?: (id: string) => void
      onDelta?: (text: string) => void
      onDone?: (payload: ChatDone) => void
      onError?: (message: string) => void
    }
): Promise<void> {
  const resp = await fetch('/api/v1/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-User-Id': getUserId() },
    body: JSON.stringify({ conversationId, question })
  })

  if (!resp.ok || !resp.body) {
    handlers.onError?.(`请求失败（HTTP ${resp.status}）`)
    return
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) {
      break
    }
    buffer += decoder.decode(value, { stream: true })

    // SSE 以空行分隔事件；最后一段可能是半个事件，留在 buffer 里等下一块
    const frames = buffer.split(/\r?\n\r?\n/)
    buffer = frames.pop() ?? ''

    for (const frame of frames) {
      let event = 'message'
      let data = ''
      for (const line of frame.split(/\r?\n/)) {
        if (line.startsWith('event:')) {
          event = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          data += line.slice(5).trim()
        }
      }
      if (!data) {
        continue
      }
      try {
        const payload = JSON.parse(data)
        if (event === 'meta') {
          handlers.onMeta?.(payload.conversationId)
        } else if (event === 'delta') {
          handlers.onDelta?.(payload.text)
        } else if (event === 'done') {
          handlers.onDone?.(payload as ChatDone)
        } else if (event === 'error') {
          handlers.onError?.(payload.message)
        }
      } catch {
        // 单个事件解析失败不该中断整条流
      }
    }
  }
}
