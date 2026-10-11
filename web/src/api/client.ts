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
  /** 当前对话厂商的预设 id（用于在厂商表里高亮"当前"）。 */
  cloudChatProviderId: string | null
  /** 当前向量厂商的预设 id。 */
  cloudEmbeddingProviderId: string | null
  cloudChatModel: string | null
  cloudEmbeddingModel: string | null
  /** 云端对话端点是否可用（Key 与模型名齐备）。 */
  cloudReady: boolean
  /** 云端不可用的原因；可用时为 null。 */
  cloudBlockedReason: string | null
  /** 索引与当前向量模型不一致的提示；一致时为 null。 */
  indexWarning: string | null
  // ── P4 收尾：云端思考模式 ────────────────────────────────────────
  /**
   * 云端对话是否开启"思考模式"（当前生效值）。
   *
   * 它直接决定回答正确率：实测同一份 Prompt 下关思考会把资料里确实存在的答案
   * 答成"未找到"（flash 3 次全错），开思考 3 次全对，代价是首字多约 1 秒。
   */
  thinkingEnabled: boolean
  /** 配置里的默认值（重启后回到的那个值）。 */
  thinkingConfigured: boolean
  /** 是否被运行时改过（界面据此提示"重启后回到 X"）。 */
  thinkingOverridden: boolean
  /** 当前厂商预设是否声明了"怎么关思考"；为 false 时开关点了也没用，界面应置灰。 */
  thinkingSwitchable: boolean
  // ── v1.13：配置指纹与状态新鲜度 ──────────────────────────────────
  /**
   * 云端配置快照（含 Key 掩码，绝不含明文）。
   *
   * 用途是**发现"后端配置变了"**：改 .env 后必须重启后端，而浏览器页面不会自己刷新，
   * 页头页脚会一直显示重启前的旧值。前端把这个对象序列化后与上次比较，
   * 不一致就提示刷新 —— 否则界面就是在撒谎。
   */
  config: {
    chatProviderId: string | null
    chatBaseUrl: string | null
    chatModel: string | null
    /** Key 掩码（末四位），用于核对"现在用的是哪一把"；未配置时为 null。 */
    chatApiKeyMasked: string | null
    embeddingProviderId: string | null
    embeddingBaseUrl: string | null
    embeddingModel: string | null
    embeddingDimension: number | null
    embeddingApiKeyMasked: string | null
    dataDir: string
  }
  /** 配置里的向量模型是否与启动时装配的不一致（不一致必须重启）。 */
  embeddingConfigChanged: boolean
  /** 最近一次连通性自检的结论；从未自检时为 null。 */
  lastCloudCheck: {
    checkedAt: string
    allOk: boolean
    /** 结论是否已过期（自检之后配置又变了）。 */
    stale: boolean
    items: CloudCheckItem[]
  } | null
  /** 存储位置明细：长期记忆/对话在同一个 H2 文件里，向量库是内存态 + 落盘文件。 */
  storage: {
    databaseFile: string
    vectorStoreFile: string
    uploadDir: string
    dataDirSource: string
  }
  /** 当前生效的检索与分块参数。 */
  retrieval: {
    topK: number
    minScore: number
    queryExpansion: boolean
    extraQueries: number
    chunkSize: number
    chunkOverlap: number
  }
}

/** 内置厂商预设（只读展示用；Key 只有"配没配"的布尔，绝不回显内容）。 */
export interface CloudPreset {
  id: string
  label: string
  baseUrl: string
  chatModels: string[]
  embeddingModels: string[]
  embeddingDimensions: Record<string, number>
  notes: string | null
  apiKeyEnv: string | null
  apiKeyConfigured: boolean
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

/** 本地 OCR 能力（只读展示用）。 */
export interface OcrAvailability {
  available: boolean
  /** READY（可用）/ DISABLED（配置关闭）/ NO_PYTHON / NO_DEPENDENCY / NO_SCRIPT */
  state: 'READY' | 'DISABLED' | 'NO_PYTHON' | 'NO_DEPENDENCY' | 'NO_SCRIPT'
  maxPages: number
  dpi: number
  timeout: string
  /** 不可用时给用户的下一步指引（含 pip 安装命令）；可用时为 null。 */
  hint: string | null
}

/**
 * OCR 识别预览（**尚未入库**）。
 *
 * `lowQuality` 由后端判定（平均置信度低于 0.85，或识别出文字的页数不足总页数 60%），
 * 界面据此提示"务必核对后再入库" —— OCR 一定有错字，而漏页往往是静默的。
 */
export interface OcrPreview {
  documentId: number
  fileName: string
  totalPages: number
  pagesWithText: number
  /** 纳入识别的内嵌插图数（已排除图标/装饰等小图）。 */
  imageCount: number
  /** 其中识别出文字、且有增量内容的插图数。 */
  imagesWithText: number
  chars: number
  avgConfidence: number | null
  elapsedMs: number
  lowQuality: boolean
  engine: string
  engineVersion: string
  /** 预览在服务端的保留时长（秒），超时需重新识别。 */
  expiresInSeconds: number
  preview: string
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

  /**
   * 切换云端"思考模式"，立即生效、无需刷新页面。
   *
   * 与对话模式不同：这里不需要 reload() —— 后端切换时只丢弃已装配的模型，
   * 下一次提问按新设置重新装配；页面上的状态用返回值就地更新即可。
   */
  switchThinkingMode: (enabled: boolean) =>
      request<SystemStatus>('/api/v1/system/thinking-mode', {
        method: 'POST',
        body: JSON.stringify({ enabled })
      }),

  /** 云端连通性自检：不消耗生成额度，用于区分"Key 不对"与"网络不通"。 */
  cloudCheck: () => request<{ items: CloudCheckItem[] }>(
      '/api/v1/system/cloud-check', { method: 'POST' }),

  /** 内置厂商预设清单（只读）：用于在界面上看清"有哪些厂商、Key 配没配、能不能当向量源"。 */
  cloudPresets: () => request<CloudPreset[]>('/api/v1/system/cloud-presets'),

  listDocuments: (page = 1, size = 50) =>
      request<{ total: number; items: DocumentItem[] }>(`/api/v1/documents?page=${page}&size=${size}`),

  uploadDocument: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<UploadResult>('/api/v1/documents/upload', { method: 'POST', body: form })
  },

  deleteDocument: (id: number) => request<void>(`/api/v1/documents/${id}`, { method: 'DELETE' }),

  /**
   * 查询本机 OCR 能力。
   *
   * 注意它**不返回错误状态**：`available: false` 是正常答案（本机没装 Python 或依赖），
   * 界面据此决定按钮是可用、置灰还是给一行 pip 命令。
   */
  ocrAvailability: () => request<OcrAvailability>('/api/v1/documents/ocr/availability'),

  /**
   * 识别扫描件的文字（**只返回预览，不入库**）。
   *
   * 入库要用户核对后调 `confirmOcr` —— OCR 一定有错字，错了的文本一旦入库会被检索、
   * 被引用、被当成依据，收拾起来比确认一次麻烦得多。
   */
  recognizeOcr: (id: number) =>
      request<OcrPreview>(`/api/v1/documents/${id}/ocr`, { method: 'POST' }),

  /** 确认把识别结果入库。 */
  confirmOcr: (id: number) =>
      request<{ documentId: number; chunkCount: number }>(
          `/api/v1/documents/${id}/ocr/confirm`, { method: 'POST' }),

  reindex: () => request<{ reindexed: number; embeddingModel: string }>(
      '/api/v1/documents/reindex', { method: 'POST' }),

  listConversations: () => request<Conversation[]>('/api/v1/conversations'),

  listMessages: (id: string) => request<MessageItem[]>(`/api/v1/conversations/${id}/messages`),

  deleteConversation: (id: string) =>
      request<void>(`/api/v1/conversations/${id}`, { method: 'DELETE' }),

  listMemories: () => request<{ total: number; items: MemoryItem[] }>('/api/v1/memories'),

  /**
   * 手动新增一条长期记忆。
   *
   * memoryType 留空时由后端按措辞自动判定 —— 一律存成"结论"会让
   * "每次回答都要…"这类要求完全不生效（注入语气是"可直接使用"，不是"必须遵守"）。
   * 界面显式选择时会覆盖自动判定。
   */
  createMemory: (content: string, importance?: number, memoryType?: MemoryItem['memoryType']) =>
      request<MemoryItem>('/api/v1/memories', {
        method: 'POST',
        body: JSON.stringify({ content, importance, memoryType })
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
