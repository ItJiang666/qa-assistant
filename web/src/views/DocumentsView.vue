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

/**
 * 状态标签色。
 *
 * `ocrPending` 必须排在最前：那种文档的 `status` 是 FAILED（解析确实失败了），
 * 但后端已经自动识别好、只差用户点确认 —— 显示成红色"失败"会让人以为坏了。
 * FAILED 用 danger 而不是 info，是因为真失败必须一眼可见。
 */
function statusTag(row: DocumentItem) {
  if (row.ocrPending) {
    return 'warning'
  }
  switch (row.status) {
    case 'INDEXED': return 'success'
    case 'PARSING': return 'warning'
    case 'PENDING': return 'info'
    default: return 'danger'
  }
}

function statusLabel(row: DocumentItem) {
  if (row.ocrPending) {
    return '待确认'
  }
  switch (row.status) {
    case 'INDEXED': return '已入库'
    case 'PARSING': return '处理中'
    case 'PENDING': return '排队中'
    default: return '失败'
  }
}

/**
 * 这一行的格式能不能走 OCR（识别文字）。
 *
 * 判断依据来自后端下发的 `recognizableExtensions`（扫描版 PDF + 各种图片），
 * **不在前端写死**：后端加一种格式而前端忘了同步，症状是"功能明明在，
 * 界面上却没有入口"。拿不到配置时（接口还没返回）保守返回 false ——
 * 宁可晚半秒出现按钮，也不要出现一个点下去必然报错的按钮。
 */
function isRecognizableType(row: DocumentItem): boolean {
  const types = ocr.value?.recognizableExtensions
  if (!types || types.length === 0) {
    return false
  }
  return types.includes((row.fileType || '').toLowerCase())
}

/**
 * 这一行能不能点「识别文字」。
 *
 * 三个条件缺一不可：格式可识别（图像型文档）、不在处理中
 * （PENDING/PARSING 时文件还没落定）、且本机 OCR 可用。
 * 已入库的文档也允许再识别？——不允许：重复识别同一份会把内容入库两遍，
 * 而界面上没有办法区分这两份重复内容。想重来就先删除。
 *
 * 不可用时按钮**置灰而不是隐藏**：藏起来用户只会以为"这功能不存在"，
 * 置灰配 tooltip 才能告诉他缺什么、怎么补。
 */
function canRecognize(row: DocumentItem): boolean {
  return isRecognizableType(row)
      && row.status !== 'INDEXED'
      && row.status !== 'PARSING'
      && row.status !== 'PENDING'
      && ocr.value?.available === true
}

/** 识别按钮不可用时的原因（鼠标悬停可见）。 */
function recognizeTooltip(row: DocumentItem): string {
  if (!isRecognizableType(row)) {
    // 走到这里只剩"解析失败、需要靠 OCR 救回来"的行（见 showRecognizeButton）
    return '该文件没有可提取的文字层，可用「识别文字」在本机 OCR 后入库'
  }
  if (row.ocrPending) {
    return '本机已自动识别完成，点一下核对并确认入库'
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
  if (row.status === 'FAILED') {
    // 上传后会自动识别一次；失败时提示里已经写明原因，
    // 这里只说明"为什么还要你点一下"——因为上次没成功
    return '上次自动识别未成功，点这里在本机重试（不联网）'
  }
  return '在本机识别该文件的文字（不联网），识别完成后需要你确认才会入库'
}

/** 识别按钮上的文字。待确认时改成「查看识别结果」，因为它其实是打开核对对话框。 */
function recognizeButtonText(row: DocumentItem): string {
  if (ocrRunningId.value === row.id) {
    return '识别中…'
  }
  return row.ocrPending ? '查看识别结果' : '识别文字'
}

/**
 * 这一行要不要显示「识别文字」按钮。
 *
 * 判据是"**点了有没有意义**"，而不是"格式是不是图像型"：
 * ① 格式可识别（扫描版 PDF / 图片）——本来就该给入口；
 * ② 解析失败——文件在库里却用不了，这时哪怕格式不在清单里也要给按钮，
 *    用置灰 + tooltip 告诉他"这份没有文字层，可以试试点这里"，总好过让他对着
 *    一个红色"失败"发呆。这条路径上用户最需要的就是下一步动作。
 * 其余情况（txt/docx 等正常入库的行）不显示：一个永远置灰的按钮纯属视觉噪音，
 * 还会让人怀疑"是不是我这份文件有问题"。
 */
function showRecognizeButton(row: DocumentItem): boolean {
  return isRecognizableType(row) || (row.status === 'FAILED' && ocr.value?.available === true)
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
 * ⚠️ 识别是**同步阻塞**的一次请求（100 页 CPU 约 2 分钟、GPU 约 45 秒），所以按钮必须给出
 * 明确的进行中文案。没有进度感的等待，用户一定会以为卡死了然后去点第二次。
 *
 * <p>为什么不做**逐页**进度条：那需要把 OCR 改成异步任务 + 轮询/SSE，是把一个
 * 已有功能整体重构，而收益只是"进度更细"。当前做法（按钮转圈 + 量级时间提示）
 * 已经解决了"是不是卡死了"这个真正的问题，重构的性价比不足。
 * 逐页进度目前进的是**后端日志**（`qa-assistant.log` 里能看到"第 N 页"），
 * 用户反馈慢时可以据此判断卡在哪一步。
 */
async function handleRecognize(row: DocumentItem) {
  if (ocrRunningId.value !== null) {
    ElMessage.warning('已有识别任务在进行中，请稍候')
    return
  }
  ocrRunningId.value = row.id
  ElMessage.info(`正在本机识别《${row.fileName}》，${ocr.value?.maxPages ?? 100} 页以内约需 ${estimateHint()}，请稍候`)
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

/**
 * 识别耗时的粗略预估。
 *
 * 刻意**只给量级**（"几十秒"而不是"37 秒"）：实际耗时取决于页数、有没有 GPU、
 * CPU 忙不忙，给一个具体数字必然频繁失准，而失准的倒计时比没有倒计时更让人焦虑。
 *
 * 依据（实测同一份 38 页扫描件）：
 *   * 优化前 CPU 128 s；优化后 CPU 47 s、GPU 17 s
 *   * 上限 100 页时，CPU 约 2 分钟、GPU 约 45 秒
 * 所以"几十秒到两分钟"覆盖了绝大多数情况。
 */
function estimateHint(): string {
  return '几十秒到两分钟'
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
            支持 txt / md / docx / doc / pdf / xlsx / html / jpg / jpeg / png / webp / bmp，单文件不超过 50MB
          </span>
        </div>
      </template>

      <el-upload
          drag
          multiple
          :show-file-list="false"
          :http-request="handleUpload"
          accept=".txt,.md,.docx,.doc,.pdf,.xlsx,.html,.jpg,.jpeg,.png,.webp,.bmp">
        <div class="drop-zone">
          <div class="drop-title">把文件拖到这里，或点击选择</div>
          <div class="drop-sub">
            扫描版 PDF、图片（jpg / png 等）没有文字层，上传后会自动在本机识别一次，
            状态变为「待确认」时点「查看识别结果」核对后入库
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
            <el-tag size="small" :type="statusTag(row)">{{ statusLabel(row) }}</el-tag>
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
              按钮的条件见 showRecognizeButton：可识别的格式（扫描版 PDF / 图片）
              一律给入口；解析失败的行也给，因为那正是"下一步该点这里"的场景。
              文案随状态变（见 recognizeButtonText）：自动识别完成后是「查看识别结果」，
              因为那时点它其实是打开核对对话框，而不是再识别一遍。
              不可用的原因（未装依赖/已入库/处理中）仍用 tooltip 说明。
            -->
            <el-tooltip
                v-if="showRecognizeButton(row)"
                :content="recognizeTooltip(row)" placement="top" :show-after="300">
              <span>
                <el-button
                    size="small" type="primary" text
                    :disabled="!canRecognize(row)"
                    :loading="ocrRunningId === row.id"
                    @click="handleRecognize(row)">
                  {{ recognizeButtonText(row) }}
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
        <template #title>本机未启用扫描件与图片的文字识别（可选功能）</template>
        <div class="ocr-alert-body">{{ ocr.hint }}</div>
      </el-alert>
      <el-alert
          v-else-if="ocr && ocr.available"
          type="success" :closable="false" show-icon class="ocr-alert">
        <template #title>本机 OCR 可用（识别全程在本机完成，不联网）</template>
        <div class="ocr-alert-body">
          扫描版 PDF 与图片上传后会<b>自动识别一次</b>，状态变成「待确认」时点
          「查看识别结果」核对；单次最多 {{ ocr.maxPages }} 页、超时 {{ ocr.timeout }}。
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
