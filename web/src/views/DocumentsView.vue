<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import { api, ApiError, type DocumentItem } from '../api/client'

const emit = defineEmits<{ (e: 'changed'): void }>()

const documents = ref<DocumentItem[]>([])
const total = ref(0)
const loading = ref(false)
const uploading = ref(false)
const reindexing = ref(false)
let pollTimer: number | undefined

/** 入参状态 → 展示用标签色。FAILED 必须一眼可见，所以用 danger 而不是 info。 */
function statusTag(status: DocumentItem['status']) {
  switch (status) {
    case 'INDEXED': return 'success'
    case 'PARSING': return 'warning'
    case 'PENDING': return 'info'
    default: return 'danger'
  }
}

function statusLabel(status: DocumentItem['status']) {
  switch (status) {
    case 'INDEXED': return '已入库'
    case 'PARSING': return '处理中'
    case 'PENDING': return '排队中'
    default: return '失败'
  }
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

async function load() {
  loading.value = true
  try {
    const data = await api.listDocuments()
    documents.value = data.items
    total.value = data.total
    schedulePollIfNeeded()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    loading.value = false
  }
}

/**
 * 有文档还在处理时自动轮询。
 *
 * 入库是异步的（大文件要几十秒），不轮询的话用户看到的永远停在"排队中"，
 * 只能手动刷新 —— 那是很糟糕的体验。
 */
function schedulePollIfNeeded() {
  const busy = documents.value.some(d => d.status === 'PENDING' || d.status === 'PARSING')
  if (busy && pollTimer === undefined) {
    pollTimer = window.setInterval(load, 2000)
  } else if (!busy && pollTimer !== undefined) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

async function handleUpload(options: UploadRequestOptions) {
  uploading.value = true
  try {
    const result = await api.uploadDocument(options.file as File)
    ElMessage.success(`已受理《${result.fileName}》，正在后台解析入库`)
    await load()
    emit('changed')
  } catch (e) {
    const msg = e instanceof ApiError ? e.message : '上传失败'
    ElMessage.error(msg)
    options.onError?.(e as Error)
  } finally {
    uploading.value = false
  }
}

async function handleDelete(row: DocumentItem) {
  await ElMessageBox.confirm(
      `删除《${row.fileName}》会同时清除它的向量与落盘文件，且不可恢复。`,
      '确认删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  try {
    await api.deleteDocument(row.id)
    ElMessage.success('已删除')
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

async function handleReindex() {
  await ElMessageBox.confirm(
      '全量重建会清空向量库并重新入库所有文档。仅在更换 embedding 模型后需要执行 —— ' +
      '新旧向量处于不同向量空间，混在一起会让检索结果悄悄变差。',
      '全量重建索引', { type: 'warning', confirmButtonText: '开始重建', cancelButtonText: '取消' })
  reindexing.value = true
  try {
    const result = await api.reindex()
    ElMessage.success(`已触发 ${result.reindexed} 份文档重建，请留意状态变化`)
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    reindexing.value = false
  }
}

onMounted(load)
onBeforeUnmount(() => {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer)
  }
})
</script>

<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>上传文档</span>
          <span class="hint">
            支持 txt / md / docx / pdf / xlsx / html，单文件不超过 50MB
          </span>
        </div>
      </template>

      <el-upload
          drag
          multiple
          :show-file-list="false"
          :http-request="handleUpload"
          accept=".txt,.md,.docx,.pdf,.xlsx,.html">
        <div class="drop-zone">
          <div class="drop-title">把文件拖到这里，或点击选择</div>
          <div class="drop-sub">
            扫描版 PDF（纯图片、没有文字层）会解析失败 —— 那是预期行为，会在下方表格里给出原因
          </div>
        </div>
      </el-upload>
    </el-card>

    <el-card shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>知识库文档（{{ total }}）</span>
          <div>
            <el-button size="small" :loading="loading" @click="load">刷新</el-button>
            <el-button
                size="small" type="warning" plain
                :loading="reindexing" :disabled="total === 0"
                @click="handleReindex">
              全量重建索引
            </el-button>
          </div>
        </div>
      </template>

      <el-table :data="documents" v-loading="loading" empty-text="还没有文档，先上传一份试试">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="fileName" label="文件名" min-width="200" show-overflow-tooltip />
        <el-table-column prop="fileType" label="类型" width="80" />
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column prop="chunkCount" label="分块" width="80" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="失败原因" min-width="240">
          <template #default="{ row }">
            <span v-if="row.errorMessage" class="error-text">{{ row.errorMessage }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="danger" text @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
/*
 * 页面自身滚动，外壳不滚。
 *
 * 外壳（App.vue 的 .app-shell）锁死了视口高度，所以滚动必须落在某一个元素上：
 * 给根元素 height:100% + overflow-y:auto，文档多、卡片长时只有这一块滚，
 * 页头与左侧菜单保持不动。min-height:0 是 flex 子项能真正滚动的前提。
 */
.page {
  height: 100%;
  min-height: 0;
  overflow-y: auto;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.hint {
  font-size: 12px;
  color: #9aa0a6;
}

.drop-zone {
  padding: 26px 10px;
  text-align: center;
}

.drop-title {
  font-size: 14px;
  color: #1f2329;
  margin-bottom: 6px;
}

.drop-sub {
  font-size: 12px;
  color: #9aa0a6;
  line-height: 1.7;
}

.error-text {
  color: #c0392b;
  font-size: 12px;
}

.muted {
  color: #c4c8ce;
}
</style>
