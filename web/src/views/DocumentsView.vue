<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import { api, ApiError, type DocumentItem, type OcrAvailability, type OcrPreview } from '../api/client'

const emit = defineEmits<{ (e: 'changed'): void }>()

const documents = ref<DocumentItem[]>([])
const total = ref(0)
const loading = ref(false)
const uploading = ref(false)
const reindexing = ref(false)
let pollTimer: number | undefined

// ── 本地 OCR（扫描件识别）────────────────────────────────────────────────
/**
 * 本机 OCR 能力。
 *
 * 单独查一次而不是跟着文档列表一起刷新：它是**进程级不变**的信息
 * （本机装没装 Python/依赖），跟列表混在一起会让"文档变了"与"能力变了"
 * 两件事纠缠，也让无谓的重复探测变多。
 */
const ocr = ref<OcrAvailability | null>(null)
/** 正在识别的文档 ID（防止同一份被连点，也用于按钮 loading）。 */
const ocrRunningId = ref<number | null>(null)
/** 识别完成、等待用户确认的结果。 */
const ocrPreview = ref<OcrPreview | null>(null)
const ocrConfirming = ref(false)

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

/**
 * 这一行能不能点「识别文字」。
 *
 * 三个条件缺一不可：是 PDF（OCR 只对图像型文档有意义）、不在处理中
 * （PENDING/PARSING 时文件还没落定）、且本机 OCR 可用。
 * 已入库的文档也允许再识别？——不允许：重复识别同一份会把内容入库两遍，
 * 而界面上没有办法区分这两份重复内容。想重来就先删除。
 *
 * 不可用时按钮**置灰而不是隐藏**：藏起来用户只会以为"这功能不存在"，
 * 置灰配 tooltip 才能告诉他缺什么、怎么补。
 */
function canRecognize(row: DocumentItem): boolean {
  return row.fileType === 'pdf'
      && row.status !== 'INDEXED'
      && row.status !== 'PARSING'
      && row.status !== 'PENDING'
      && ocr.value?.available === true
}

/** 识别按钮不可用时的原因（鼠标悬停可见）。 */
function recognizeTooltip(row: DocumentItem): string {
  if (row.fileType !== 'pdf') {
    return '「识别文字」只对 PDF 有效：其它格式本来就带文字层，直接上传即可被解析'
  }
  if (row.status === 'INDEXED') {
    return '该文档已入库；如需重新识别，请先删除再重新上传'
  }
  if (row.status === 'PARSING' || row.status === 'PENDING') {
    return '正在处理中，请稍候'
  }
  if (!ocr.value?.available) {
    return ocr.value?.hint || '本机 OCR 不可用'
  }
  return '在本机识别该扫描件的文字（不联网），识别完成后需要你确认才会入库'
}

async function loadOcrAvailability() {
  try {
    ocr.value = await api.ocrAvailability()
  } catch {
    // 探测失败不影响文档管理：只在界面上按"不可用"处理
    ocr.value = null
  }
}

/**
 * 识别文字。
 *
 * ⚠️ 识别是**同步阻塞**的一次请求（50 页约 2 分钟），所以按钮必须给出
 * 明确的进行中文案。没有进度感的等待，用户一定会以为卡死了然后去点第二次。
 */
async function handleRecognize(row: DocumentItem) {
  if (ocrRunningId.value !== null) {
    ElMessage.warning('已有识别任务在进行中，请稍候')
    return
  }
  ocrRunningId.value = row.id
  ElMessage.info(`正在本机识别《${row.fileName}》，${ocr.value?.maxPages ?? 50} 页以内约需 ${estimateHint()}，请稍候`)
  try {
    ocrPreview.value = await api.recognizeOcr(row.id)
  } catch (e) {
    const msg = e instanceof ApiError ? e.message : '识别失败'
    ElMessage.error(msg)
    // 依赖缺失这类问题可能是刚装好/刚卸载，重新探测一次让按钮状态跟上
    await loadOcrAvailability()
  } finally {
    ocrRunningId.value = null
  }
}

function estimateHint(): string {
  return '1~3 分钟'
}

/** 确认入库。 */
async function handleConfirmOcr() {
  const preview = ocrPreview.value
  if (!preview) {
    return
  }
  ocrConfirming.value = true
  try {
    const result = await api.confirmOcr(preview.documentId)
    ElMessage.success(`已入库，生成 ${result.chunkCount} 个分块`)
    ocrPreview.value = null
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    ocrConfirming.value = false
  }
}

/** 取消：明确告诉用户"什么都没发生"，避免他担心已经悄悄入库了。 */
function handleCancelOcr() {
  ocrPreview.value = null
  ElMessage.info('已放弃本次识别结果，文档未被修改')
}

/** 预览文本的前若干字（后端已截断，这里再限一层显示高度）。 */
const previewText = computed(() => ocrPreview.value?.preview ?? '')

function confidenceText(value: number | null): string {
  return value == null ? '—' : value.toFixed(3)
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

onMounted(() => {
  void load()
  void loadOcrAvailability()
})
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
            支持 txt / md / docx / doc / pdf / xlsx / html，单文件不超过 50MB
          </span>
        </div>
      </template>

      <el-upload
          drag
          multiple
          :show-file-list="false"
          :http-request="handleUpload"
          accept=".txt,.md,.docx,.doc,.pdf,.xlsx,.html">
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
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <!--
              按钮只在 PDF 行出现：对 txt/docx 这类本来就带文字层的格式，
              一个永远置灰的按钮纯属视觉噪音，还会让人怀疑"是不是我这份文件有问题"。
              不可用的原因（未装依赖/已入库/处理中）仍用 tooltip 说明。
            -->
            <el-tooltip
                v-if="row.fileType === 'pdf'"
                :content="recognizeTooltip(row)" placement="top" :show-after="300">
              <span>
                <el-button
                    size="small" type="primary" text
                    :disabled="!canRecognize(row)"
                    :loading="ocrRunningId === row.id"
                    @click="handleRecognize(row)">
                  {{ ocrRunningId === row.id ? '识别中…' : '识别文字' }}
                </el-button>
              </span>
            </el-tooltip>
            <el-button size="small" type="danger" text @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!--
        OCR 能力说明。刻意用 el-alert 而不是自定义图标区：
        全项目都用 el-alert 表达这类"需要读一眼"的提示，保持一致；
        而且 @element-plus/icons-vue 并不是本项目的直接依赖（只是 element-plus
        的传递依赖），为一个图标去依赖它，将来 element-plus 升级换实现就会断。
      -->
      <el-alert
          v-if="ocr && !ocr.available"
          type="info" :closable="false" show-icon class="ocr-alert">
        <template #title>本机未启用扫描件文字识别（可选功能）</template>
        <div class="ocr-alert-body">{{ ocr.hint }}</div>
      </el-alert>
      <el-alert
          v-else-if="ocr && ocr.available"
          type="success" :closable="false" show-icon class="ocr-alert">
        <template #title>本机 OCR 可用（识别全程在本机完成，不联网）</template>
        <div class="ocr-alert-body">
          单次最多 {{ ocr.maxPages }} 页、超时 {{ ocr.timeout }}；
          识别结果需你确认后才会入库。
        </div>
      </el-alert>
    </el-card>

    <!--
      识别结果确认对话框。
      刻意做成"必须显式确认"：OCR 一定有错字，而错的文本一旦入库会被检索、被引用、
      被模型当成依据 —— 收拾它比确认一次麻烦得多。
    -->
    <el-dialog
        :model-value="ocrPreview !== null"
        title="确认识别结果"
        width="720px"
        :close-on-click-modal="false"
        @update:model-value="() => handleCancelOcr()">
      <template v-if="ocrPreview">
        <el-alert
            v-if="ocrPreview.lowQuality"
            type="warning" :closable="false" show-icon class="ocr-alert"
            title="本次识别质量偏低，请务必核对后再入库" />

        <el-descriptions :column="2" border size="small" class="ocr-desc">
          <el-descriptions-item label="文件">{{ ocrPreview.fileName }}</el-descriptions-item>
          <el-descriptions-item label="识别页数">
            {{ ocrPreview.pagesWithText }} / {{ ocrPreview.totalPages }}
            <span v-if="ocrPreview.pagesWithText < ocrPreview.totalPages" class="warn-text">
              （有页面没识别出文字，可能漏内容）
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="识别字数">{{ ocrPreview.chars }}</el-descriptions-item>
          <el-descriptions-item label="平均置信度">
            {{ confidenceText(ocrPreview.avgConfidence) }}
          </el-descriptions-item>
          <el-descriptions-item label="内嵌插图">
            <!--
              只在"确实有插图"时才展示这一项：logo、分隔线那样的小图会被后端按尺寸
              过滤掉，不进入统计，所以 imageCount=0 是常态而非异常。
              ⚠️ 措辞刻意避开"图里有字"这种说法：整页渲染本来就能读到插图中的文字，
              那种情况下图片路径会因为"内容重复"被丢弃，imagesWithText 就是不增加的。
              写成"图里有字"会让用户以为识别失败，所以这里说"额外文字"。
            -->
            <template v-if="ocrPreview.imageCount > 0">
              发现 {{ ocrPreview.imageCount }} 张
              <span v-if="ocrPreview.imagesWithText > 0">
                ，其中 {{ ocrPreview.imagesWithText }} 张读出了额外文字
              </span>
              <span v-else class="muted">
                （其文字已包含在页面识别结果中）
              </span>
            </template>
            <span v-else class="muted">未发现需要单独识别的插图</span>
          </el-descriptions-item>
          <el-descriptions-item label="引擎">
            {{ ocrPreview.engine }} {{ ocrPreview.engineVersion }}
          </el-descriptions-item>
          <el-descriptions-item label="耗时">
            {{ (ocrPreview.elapsedMs / 1000).toFixed(1) }} 秒
          </el-descriptions-item>
        </el-descriptions>

        <div class="ocr-preview-title">
          识别文本预览（前若干字，完整内容共 {{ ocrPreview.chars }} 字）
        </div>
        <pre class="ocr-preview">{{ previewText }}</pre>

        <div class="ocr-tip">
          确认后会把这批文本分块入库，并在文档列表里标注
          <b>「正文来自本机 OCR 识别」</b> —— 它不是原件文字层，可能有错字。
        </div>
      </template>

      <template #footer>
        <el-button @click="handleCancelOcr">放弃，不入库</el-button>
        <el-button type="primary" :loading="ocrConfirming" @click="handleConfirmOcr">
          确认入库
        </el-button>
      </template>
    </el-dialog>
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

/* ── 本地 OCR ─────────────────────────────────────────────────────────── */

.ocr-alert {
  margin-top: 12px;
}

/* hint 里含 pip 安装命令，会换行；保留换行并按需断开，避免命令被截断看不清 */
.ocr-alert-body {
  font-size: 12px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}

.ocr-alert-body :deep(code) {
  font-family: Consolas, "Courier New", monospace;
}

.ocr-desc {
  margin-bottom: 12px;
}

.ocr-preview-title {
  font-size: 13px;
  color: #5b6068;
  margin-bottom: 6px;
}

/*
 * 预览框：固定高度 + 内部滚动。
 * 识别文本可能几千字，让它把对话框顶出屏幕，用户就看不到底部的"确认入库"按钮了。
 */
.ocr-preview {
  max-height: 320px;
  overflow: auto;
  margin: 0;
  padding: 10px 12px;
  background: #fafbfc;
  border: 1px solid #eef0f3;
  border-radius: 6px;
  font-family: Consolas, "Courier New", monospace;
  font-size: 12px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}

.ocr-tip {
  margin-top: 12px;
  font-size: 12px;
  line-height: 1.8;
  color: #9aa0a6;
}

.warn-text {
  color: #e6a23c;
  font-size: 12px;
}
</style>
