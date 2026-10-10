<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
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

async function loadStatus() {
  try {
    status.value = await api.systemStatus()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

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

onMounted(loadStatus)
</script>

<template>
  <el-container style="height: 100%">
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
          <el-tag size="small" type="info">对话 {{ status.chatModel }}</el-tag>
          <el-tag size="small" type="info">向量 {{ status.embeddingModel }}</el-tag>
          <el-tag size="small">文档 {{ status.documentCount }}</el-tag>
          <el-tag size="small">分块 {{ status.chunkCount }}</el-tag>
          <el-tag size="small">记忆 {{ status.memoryCount }}</el-tag>
          <el-tag size="small" type="success" :title="userId">
            身份 {{ userId.slice(0, 8) }}…
          </el-tag>
        </template>
        <el-button size="small" text @click="loadStatus">刷新</el-button>
        <el-button size="small" text type="warning" @click="handleResetIdentity">重置身份</el-button>
      </div>
    </el-header>

    <el-container>
      <el-aside width="180px" class="app-aside">
        <el-menu :default-active="activeTab" @select="(k: string) => (activeTab = k as Tab)">
          <el-menu-item index="documents">文档管理</el-menu-item>
          <el-menu-item index="chat">对话问答</el-menu-item>
          <el-menu-item index="memories">记忆管理</el-menu-item>
          <el-menu-item index="system">系统状态</el-menu-item>
        </el-menu>

        <div v-if="status" class="footer-note">
          <div class="mono">{{ status.database }} · {{ status.vectorStore }}</div>
          <div class="mono" :title="status.dataDir">{{ status.dataDir }}</div>
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

        <DocumentsView v-if="activeTab === 'documents'" @changed="loadStatus" />
        <ChatView v-else-if="activeTab === 'chat'" @changed="loadStatus" />
        <MemoryView v-else-if="activeTab === 'memories'" @changed="loadStatus" />
        <SystemView v-else />
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
}

.footer-note {
  padding: 10px 14px;
  font-size: 11px;
  color: #9aa0a6;
  line-height: 1.6;
  word-break: break-all;
}

.app-main {
  padding: 18px;
  overflow: auto;
}

.main-alert {
  margin-bottom: 12px;
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
