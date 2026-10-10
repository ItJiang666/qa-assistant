<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, getUserId, resetUserId, type CloudCheckItem, type SystemStatus } from './api/client'
import DocumentsView from './views/DocumentsView.vue'
import ChatView from './views/ChatView.vue'
import MemoryView from './views/MemoryView.vue'
import SystemView from './views/SystemView.vue'

/**
 * 应用外壳。
 *
 * 四个页面（文档 / 对话 / 记忆 / 系统状态），用状态切换而不是 vue-router ——
 * 少一层依赖、少一份路由配置；将来需要「分享链接直达某页」时再引入也不迟。
 * 这不是偷懒，是针对内部工具的有意取舍。
 *
 * P4 追加：模型来源开关（本地 ⇄ 云端）。
 * P6 追加：系统状态页 —— 把散在页头、弹窗、yml、启动日志里的状态收敛到一处。
 */
type Tab = 'documents' | 'chat' | 'memories' | 'system'

const activeTab = ref<Tab>('documents')
const status = ref<SystemStatus | null>(null)
const userId = ref(getUserId())

/** 模型来源对话框。 */
const modeDialogVisible = ref(false)
const targetMode = ref<'local' | 'cloud'>('local')
const switching = ref(false)
const checking = ref(false)
const checkItems = ref<CloudCheckItem[]>([])
const reindexing = ref(false)

const isCloud = computed(() => status.value?.runMode === 'cloud')

/**
 * 上一次看到的云端配置指纹。
 *
 * 存成字符串而不是对象：比对的语义就是"序列化后是否一模一样"，
 * 直接比字符串既简单又不会漏字段（多一个字段忘了比，就是漏掉一类"改了没生效"）。
 */
const lastConfigFingerprint = ref<string | null>(null)
/** 后端配置已变化、页面显示的是旧值（需要刷新）。 */
const configDrifted = ref(false)
/** 最近一次成功拉到状态的时间，让人知道"这屏数字有多旧"。 */
const lastLoadedAt = ref<string>('')

function fingerprintOf(s: SystemStatus): string {
  return JSON.stringify(s.config)
}

/**
 * 拉取系统状态，并顺带检测"后端配置是否变了"。
 *
 * <h2>为什么需要这个检测</h2>
 * 改 `.env`（换 Key、换模型）后必须重启后端；而浏览器页面不会自己刷新，
 * 页头页脚的模型名、厂商名会一直是重启前的旧值 —— 用户在"界面显示新模型、
 * 实际请求用旧模型"的状态下排查问题，方向从第一步就是错的。
 *
 * 后端把配置快照随 `/status` 一起返回，这里只做一次字符串比对：
 * 变了就挂提示条，而不是偷偷替换显示值（那会让人以为配置早就生效了）。
 */
async function loadStatus() {
  try {
    const next = await api.systemStatus()
    const fingerprint = fingerprintOf(next)
    if (lastConfigFingerprint.value !== null && lastConfigFingerprint.value !== fingerprint) {
      configDrifted.value = true
    }
    lastConfigFingerprint.value = fingerprint
    status.value = next
    lastLoadedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

/** 提示条上的"立即刷新"：整页重载，让所有组件按新配置重新初始化。 */
function reloadPage() {
  window.location.reload()
}

/** 自检结论是否新鲜（新 Key 到底生效了没有，只能靠它回答）。 */
const lastCheckHint = computed(() => {
  const check = status.value?.lastCloudCheck
  if (!check) {
    return null
  }
  const time = check.checkedAt.replace('T', ' ').slice(5, 19)
  if (check.stale) {
    return { type: 'warning' as const, text: `上次自检 ${time}（结论已过期：配置在那之后又变了，请重新自检）` }
  }
  return {
    type: check.allOk ? ('success' as const) : ('danger' as const),
    text: `上次自检 ${time}：${check.allOk ? '全部通过' : '有端点未通过'}`
  }
})

/** 重置身份：等于换个"人"用，历史会话与长期记忆都不再可见。 */
async function handleResetIdentity() {
  await ElMessageBox.confirm(
      '将生成一个新的匿名身份。当前身份的会话与长期记忆不会丢失，但在这个浏览器里将不再可见。',
      '重置身份',
      { confirmButtonText: '重置', cancelButtonText: '取消', type: 'warning' })
  userId.value = resetUserId()
  ElMessage.success('已切换为新的匿名身份')
  await loadStatus()
}

/** 打开模型来源面板：把"将要切到哪个模式"预置为当前模式。 */
function openModeDialog() {
  targetMode.value = (status.value?.runMode as 'local' | 'cloud') ?? 'local'
  checkItems.value = []
  modeDialogVisible.value = true
}

/**
 * 应用模式切换。
 *
 * 切换成功后**自动刷新页面**：模式决定的是"谁来回答"，而页面上已经有会话列表、
 * 消息气泡、可能还有一个正在进行的 SSE 流 —— 逐个去改它们的状态既麻烦又容易漏，
 * 整页重载让所有组件基于新模式重新初始化，是这里最稳的做法。
 */
async function applyMode() {
  if (!status.value || targetMode.value === status.value.runMode) {
    modeDialogVisible.value = false
    return
  }
  switching.value = true
  try {
    status.value = await api.switchChatMode(targetMode.value)
    modeDialogVisible.value = false
    const label = targetMode.value === 'cloud' ? '云端' : '本地'
    ElMessage.success(`已切换到${label}模式，页面即将刷新`)
    setTimeout(() => window.location.reload(), 800)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    switching.value = false
  }
}

/** 连通性自检：区分"Key 不对"与"网络不通"。 */
async function runCloudCheck() {
  checking.value = true
  try {
    const result = await api.cloudCheck()
    checkItems.value = result.items
    const bad = result.items.filter(i => !i.ok)
    if (bad.length === 0) {
      ElMessage.success('两个端点都连通')
    } else {
      ElMessage.warning(`${bad.length} 个端点未通过，详见下表`)
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    checking.value = false
  }
}

/**
 * 重建索引。
 *
 * 触发后入库是异步的，所以这里轮询几次状态，等 indexWarning 消失再收工 ——
 * 否则用户会看到"点了重建但提示还在"，以为没生效。
 */
async function handleReindex() {
  await ElMessageBox.confirm(
      '将用当前向量模型重新生成全部文档的向量。已有的向量会被清空后重灌，期间检索结果不完整。',
      '重建索引',
      { confirmButtonText: '开始重建', cancelButtonText: '取消', type: 'warning' })
  reindexing.value = true
  try {
    const r = await api.reindex()
    ElMessage.success(`已开始重建 ${r.reindexed} 份文档的向量，请稍候…`)
    for (let i = 0; i < 10; i++) {
      await new Promise(resolve => setTimeout(resolve, 3000))
      await loadStatus()
      if (!status.value?.indexWarning) {
        ElMessage.success('索引已与当前向量模型一致')
        break
      }
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    reindexing.value = false
  }
}

/**
 * 挂载时拉一次状态，并起一个低频轮询。
 *
 * <h2>为什么需要轮询（30 秒一次）</h2>
 * 这个页面显示的是**后端进程的状态**，而后端随时可能被重启（改 .env 后必须重启）。
 * 没有轮询的话，页面会一直停在重启前的数字上，用户没有任何途径察觉 ——
 * 直到他自己想起来按 F5。30 秒是个折中：足够及时地发现"后端变了"，
 * 又不会给状态接口带来实质压力（它只做几个计数查询）。
 *
 * <h2>为什么不用 SSE 推送</h2>
 * 那需要在后端多维护一条长连接与一套广播，而这里要的只是"发现变化"，
 * 一次 HTTP 拉取足够；真到了需要实时推送的时候，再换也不迟。
 */
let pollTimer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  void loadStatus()
  pollTimer = setInterval(() => void loadStatus(), 30_000)
})

onBeforeUnmount(() => {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
})
</script>

<template>
  <el-container class="app-shell" style="height: 100%">
    <el-header height="56px" class="app-header">
      <div class="brand">
        <span class="logo">QA</span>
        <span class="title">智能问答助手</span>
        <el-button size="small" :type="isCloud ? 'warning' : 'info'" plain @click="openModeDialog">
          模型来源：{{ isCloud ? '云端' : '本地' }}
        </el-button>
      </div>

      <div class="status">
        <template v-if="status">
          <el-tag size="small" type="info" :title="`当前对话模型：${status.chatModel}（${status.runMode === 'cloud' ? '云端' : '本地'}）`">
            对话 {{ status.chatModel }}
          </el-tag>
          <el-tag size="small" type="info" :title="`向量模型：${status.embeddingModel}；来源 ${status.embeddingRunMode}，换它需重启并重建索引`">
            向量 {{ status.embeddingModel }}
          </el-tag>
          <el-tag size="small" :title="`已入库文档总数（全局，所有身份共享）`">
            文档 {{ status.documentCount }}
          </el-tag>
          <el-tag size="small" :title="`已入库分块总数（全局）`">分块 {{ status.chunkCount }}</el-tag>
          <el-tag
              size="small"
              :title="`当前身份（${userId}）的长期记忆条数 —— 记忆按身份隔离，重置身份后这个数字会变`">
            记忆 {{ status.memoryCount }}
          </el-tag>
          <el-tag size="small" type="success" :title="userId">
            身份 {{ userId.slice(0, 8) }}…
          </el-tag>
          <el-tag v-if="lastCheckHint" size="small" :type="lastCheckHint.type" :title="lastCheckHint.text">
            自检 {{ status.lastCloudCheck?.allOk ? '通过' : '未通过' }}
          </el-tag>
        </template>
        <el-button size="small" text :title="lastLoadedAt ? `上次刷新 ${lastLoadedAt}（每 30 秒自动刷新一次）` : '拉取最新状态'" @click="loadStatus">
          刷新
        </el-button>
        <el-button size="small" text type="warning" @click="handleResetIdentity">重置身份</el-button>
      </div>
    </el-header>

    <!--
      配置漂移提示：只在"页面显示的值"与"后端当前配置"不一致时出现。
      这类不一致几乎必然来自"改了 .env 并重启了后端，但浏览器没刷新"——
      此时页头页脚的模型名、厂商名全是旧的，用户会拿旧信息排查新问题。
    -->
    <el-alert
        v-if="configDrifted"
        type="warning"
        :closable="false"
        show-icon
        class="drift-alert">
      <template #title>
        <span>后端配置已变化，本页显示的是旧值</span>
        <el-button size="small" type="warning" text @click="reloadPage">立即刷新页面</el-button>
      </template>
      <div class="alert-detail">
        当前后端：对话 {{ status?.cloudChatModel || status?.chatModel }} ·
        向量 {{ status?.cloudEmbeddingModel || status?.embeddingModel }}
        （Key 尾号：对话 {{ status?.config?.chatApiKeyMasked || '未配置' }}、
        向量 {{ status?.config?.embeddingApiKeyMasked || '未配置' }}）
      </div>
    </el-alert>

    <el-container class="app-body">
      <el-aside width="200px" class="app-aside">
        <el-menu :default-active="activeTab" @select="(k: string) => (activeTab = k as Tab)">
          <el-menu-item index="documents">文档管理</el-menu-item>
          <el-menu-item index="chat">对话问答</el-menu-item>
          <el-menu-item index="memories">记忆管理</el-menu-item>
          <el-menu-item index="system">系统状态</el-menu-item>
        </el-menu>

        <!--
          左下角写清楚"东西都存在哪"。此前只显示"库类型 + 数据目录"，
          用户看完仍会问三个问题：长期记忆在哪、对话在哪、向量在哪。
          事实是前两者在同一个文件库（表 qa_memory / qa_message），
          向量库是内存态 + 落盘文件。

          这里只写相对路径、完整路径放 tooltip：侧栏只有 200px 宽，
          写全路径会被折成三四行，比不写还难读。
        -->
        <div v-if="status" class="footer-note">
          <div class="footer-line">库 <span class="mono">{{ status.database }}</span></div>
          <div class="footer-line" :title="`长期记忆与会话消息都在这个文件里：\n${status.storage.databaseFile}`">
            记忆+对话 → <span class="mono">data/qa.mv.db</span>
          </div>
          <div class="footer-line" :title="`向量库为内存态，重启从这个文件载入：\n${status.storage.vectorStoreFile}`">
            向量 → <span class="mono">index/embedding-store.json</span>
          </div>
          <div class="footer-line" :title="`上传原件目录：\n${status.storage.uploadDir}`">
            原件 → <span class="mono">uploads/</span>
          </div>
          <div class="footer-line mono footer-dir" :title="status.dataDir">
            {{ status.dataDir }}
          </div>
          <div class="footer-line footer-src">{{ status.storage.dataDirSource }}</div>
        </div>
      </el-aside>

      <el-main class="app-main">
        <el-alert v-if="status?.indexWarning" type="error" :closable="false" show-icon class="main-alert">
          <template #title>
            <span>索引与向量模型不一致</span>
            <el-button size="small" type="danger" text :loading="reindexing" @click="handleReindex">
              立即重建索引
            </el-button>
          </template>
          <div class="alert-detail">{{ status.indexWarning }}</div>
        </el-alert>

        <el-alert
            v-if="isCloud"
            type="warning"
            :closable="false"
            show-icon
            class="main-alert"
            title="云端模式：你的问题与检索到的文档片段会发送给云端模型服务商，请勿提问敏感内容" />

        <!--
          view-host 是给页面提供"确定高度"的那一层。
          没有它，ChatView 的 flex:1 找不到 flex 父容器，就只能退回"按内容撑高"，
          输入框又会被顶出去 —— 这正是这次要修的病根。
        -->
        <div class="view-host">
          <DocumentsView v-if="activeTab === 'documents'" @changed="loadStatus" />
          <ChatView v-else-if="activeTab === 'chat'" @changed="loadStatus" />
          <MemoryView v-else-if="activeTab === 'memories'" @changed="loadStatus" />
          <SystemView v-else />
        </div>
      </el-main>
    </el-container>

    <el-dialog v-model="modeDialogVisible" title="模型来源" width="640px">
      <el-radio-group v-model="targetMode" class="mode-radio">
        <el-radio-button value="local">本地（Ollama）</el-radio-button>
        <el-radio-button value="cloud" :disabled="!status?.cloudReady">
          云端（{{ status?.cloudProvider || '未配置' }}）
        </el-radio-button>
      </el-radio-group>

      <el-alert
          v-if="status && !status.cloudReady"
          type="info"
          :closable="false"
          show-icon
          class="dialog-alert"
          :title="`云端暂不可用：${status.cloudBlockedReason || '未配置'}`" />

      <!--
        自检失败必须在这里也说一遍：云端"可用"判断的是**配置齐备**（base-url + 模型名 + Key
        非空），它回答不了"这把 Key 真的能用吗"。实测换错 Key 时 cloudReady 仍是 true，
        于是弹窗显示"云端可用"、切过去提问却 401。把最近一次自检结论带进来，
        让用户在切之前就看到真相。
      -->
      <el-alert
          v-if="status?.lastCloudCheck && !status.lastCloudCheck.allOk"
          type="error"
          :closable="false"
          show-icon
          class="dialog-alert"
          :title="`最近一次连通性自检未通过（${status.lastCloudCheck.checkedAt.replace('T', ' ').slice(5, 19)}）：端点或 Key 有问题，切到云端后提问会失败`">
        <div class="dialog-alert-detail">
          <div v-for="item in status.lastCloudCheck.items" :key="item.target">
            {{ item.target === 'chat' ? '对话' : '向量' }}：{{ item.message }}
          </div>
        </div>
      </el-alert>

      <el-descriptions :column="1" border size="small" class="dialog-desc">
        <el-descriptions-item label="当前对话模型">
          {{ status?.chatModel }}
          <el-tag size="small" :type="isCloud ? 'warning' : 'info'">
            {{ status?.runMode }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="云端对话模型">
          {{ status?.cloudChatModel || '未配置' }}
        </el-descriptions-item>
        <el-descriptions-item label="向量模型（切换需重启）">
          {{ status?.embeddingModel }}
          <span class="hint">来源：{{ status?.embeddingRunMode }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="配置默认模式">
          {{ status?.configuredRunMode }}
          <span v-if="status?.modeOverridden" class="hint">
            （已被临时切换，重启后回到配置值）
          </span>
        </el-descriptions-item>
      </el-descriptions>

      <div class="dialog-tools">
        <el-button size="small" :loading="checking" @click="runCloudCheck">连通性自检</el-button>
        <span class="hint">自检只请求 /models 列表，不消耗生成额度</span>
      </div>

      <el-table v-if="checkItems.length" :data="checkItems" size="small" border class="check-table">
        <el-table-column label="端点" width="90">
          <template #default="{ row }">{{ row.target === 'chat' ? '对话' : '向量' }}</template>
        </el-table-column>
        <el-table-column prop="label" label="厂商" width="130" />
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.ok ? 'success' : row.reachable ? 'warning' : 'danger'">
              {{ row.ok ? '连通' : row.reachable ? `HTTP ${row.status}` : '不可达' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="latencyMs" label="耗时" width="80">
          <template #default="{ row }">{{ row.latencyMs }} ms</template>
        </el-table-column>
        <el-table-column prop="message" label="说明" show-overflow-tooltip />
      </el-table>

      <template #footer>
        <el-button @click="modeDialogVisible = false">取消</el-button>
        <el-button
            type="primary"
            :loading="switching"
            :disabled="!status || targetMode === status.runMode"
            @click="applyMode">
          应用并刷新页面
        </el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<style scoped>
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #e3e6eb;
  padding: 0 18px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: #2e5496;
  color: #fff;
  font-weight: 700;
  font-size: 13px;
}

.title {
  font-size: 16px;
  font-weight: 600;
}

.status {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.app-aside {
  background: #fff;
  border-right: 1px solid #e3e6eb;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  /* 页脚信息较长时让侧栏自己滚，不要把整个页面撑长 */
  overflow: auto;
}

.footer-note {
  padding: 10px 14px;
  font-size: 11px;
  color: #9aa0a6;
  line-height: 1.6;
}

.footer-line {
  margin-bottom: 3px;
}

/* 数据目录那一行会很长，只在这里允许断开，其他行保持可读 */
.footer-dir {
  word-break: break-all;
  color: #b6bcc4;
}

.footer-src {
  color: #c4c8ce;
  font-size: 10px;
}

.drift-alert {
  margin: 0;
  border-radius: 0;
}

/*
 * 应用外壳不滚动，滚动交给各页自己。
 *
 * 原先 el-main 是 overflow:auto，页面可以整体上下滚 —— 于是"输入框被顶到屏幕外、
 * 得先下滑才能打字"成了必然：任何一处内容变高（引用来源展开、提示条出现、
 * 侧栏页脚行数变化）都会把整页推长。锁死外壳高度之后，页头、提示条占多少，
 * 中间区域就自动少多少，输入框永远贴在可见区域的底部。
 */
.app-shell {
  overflow: hidden;
}

/*
 * 侧栏 + 主区这一层必须也被约束住，否则上面那句"锁死外壳"是假的。
 *
 * 实测踩到的坑（无头 Edge，视口 808px，往对话页塞 24 条气泡后）：
 *   .app-shell 高 808（被 overflow:hidden 锁住，看着没问题）
 *   .app-main  高 1339 ← 破了
 *   .view-host 高 1251
 *   .composer  top 1299 / bottom 1376 ← 输入框跑到视口下方 568px，整个看不见
 * 原因是这一层 el-container 默认 min-height:auto，被内容撑多高就多高，
 * flex 只负责"分空间"、拦不住内容溢出。它一破，里面所有 flex 与 min-height:0
 * 全部失去意义 —— 那时消息区高度等于内容高度，overflow-y:auto 永远不触发，
 * 于是既滚不动消息、也够不到输入框。
 *
 * flex:1 + min-height:0 把它钉回"页头之外的那点高度"，下面各层的约束才开始生效。
 */
.app-body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.app-main {
  padding: 18px;
  overflow: hidden;
  /* 让 .view-host 用 flex:1 拿到"页头与提示条之外的全部高度" */
  display: flex;
  flex-direction: column;
  /* 主区是这一行里的 flex 子项，同样必须归零，否则它也会被内容撑破 */
  min-height: 0;
}

/*
 * flex 布局里滚动容器的必备项：默认 min-height:auto 会让"内容多高它就多高"，
 * overflow 永远不会生效，于是它反过来把父容器撑出屏幕。必须显式归零。
 */
.app-main > * {
  min-height: 0;
}

/*
 * 页面宿主：占满 el-main 的剩余高度，并把"确定的高度"传给页面根元素。
 *
 * 各页面的根元素（.page / .system-page / .chat-layout）都靠它拿到高度：
 * 要么 flex:1 撑开，要么 height:100% 填满 —— 两者都以父容器有确定高度为前提。
 */
.view-host {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.main-alert {
  margin-bottom: 12px;
  /* 提示条保持自身高度，不被压缩：它是要被读的，压扁了等于没提示 */
  flex-shrink: 0;
}

.alert-detail {
  font-size: 12px;
  line-height: 1.7;
}

.mode-radio {
  margin-bottom: 12px;
}

.dialog-alert {
  margin-bottom: 12px;
}

.dialog-alert-detail {
  font-size: 12px;
  line-height: 1.7;
  margin-top: 4px;
}

.dialog-desc {
  margin-bottom: 12px;
}

.dialog-tools {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.check-table {
  margin-top: 4px;
}

.hint {
  color: #9aa0a6;
  font-size: 12px;
  margin-left: 6px;
}
</style>
