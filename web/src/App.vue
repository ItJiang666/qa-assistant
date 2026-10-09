<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, getUserId, resetUserId, type SystemStatus } from './api/client'
import DocumentsView from './views/DocumentsView.vue'
import ChatView from './views/ChatView.vue'
import MemoryView from './views/MemoryView.vue'

/**
 * 应用外壳。
 *
 * 只有三个页面（文档 / 对话 / 记忆），用状态切换而不是 vue-router ——
 * 少一层依赖、少一份路由配置；将来需要「分享链接直达某页」时再引入也不迟。
 * 这不是偷懒，是针对三页内部工具的有意取舍。
 */
type Tab = 'documents' | 'chat' | 'memories'

const activeTab = ref<Tab>('documents')
const status = ref<SystemStatus | null>(null)
const userId = ref(getUserId())

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

onMounted(loadStatus)
</script>

<template>
  <el-container style="height: 100%">
    <el-header height="56px" class="app-header">
      <div class="brand">
        <span class="logo">QA</span>
        <span class="title">智能问答助手</span>
      </div>

      <div class="status">
        <template v-if="status">
          <el-tag size="small" type="info">模式 {{ status.runMode }}</el-tag>
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
        </el-menu>

        <div v-if="status" class="footer-note">
          <div class="mono">{{ status.database }} · {{ status.vectorStore }}</div>
          <div class="mono" :title="status.dataDir">{{ status.dataDir }}</div>
        </div>
      </el-aside>

      <el-main class="app-main">
        <DocumentsView v-if="activeTab === 'documents'" @changed="loadStatus" />
        <ChatView v-else-if="activeTab === 'chat'" @changed="loadStatus" />
        <MemoryView v-else @changed="loadStatus" />
      </el-main>
    </el-container>
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
</style>
