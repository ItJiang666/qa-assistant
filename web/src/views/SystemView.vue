<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type CloudCheckItem, type CloudPreset, type SystemStatus } from '../api/client'

/**
 * 系统状态页（P6）。
 *
 * 存在的理由：排查问题时最耗时间的不是"没数据"，而是**数据散在四处** ——
 * 模式在页头、模型名在弹窗、参数在 yml、Key 配没配只有启动日志知道。
 * 这一页把它收敛成三件事：**现在是什么状态、生效参数是多少、哪里不一致**。
 *
 * 刻意保持只读（除了两个明确的操作按钮：重建索引、连通性自检）：
 * 状态页改成"能改配置"的地方，就会变成一个难测试、难解释的配置编辑器。
 */
const status = ref<SystemStatus | null>(null)
const presets = ref<CloudPreset[]>([])
const checkItems = ref<CloudCheckItem[]>([])
const loading = ref(false)
const checking = ref(false)
const reindexing = ref(false)

async function load() {
  loading.value = true
  try {
    status.value = await api.systemStatus()
    presets.value = await api.cloudPresets()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    loading.value = false
  }
}

/** 连通性自检：会真的去请求云端 /models，因此只在用户点击时执行。 */
async function runCheck() {
  checking.value = true
  try {
    const result = await api.cloudCheck()
    checkItems.value = result.items
    ElMessage[result.items.every(i => i.ok) ? 'success' : 'warning'](
        result.items.every(i => i.ok) ? '两个端点都连通' : '有端点未通过，详见下表')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    checking.value = false
  }
}

/** 重建索引：触发后轮询状态，直到不一致提示消失。 */
async function reindex() {
  await ElMessageBox.confirm(
      '将用当前向量模型重新生成全部文档的向量，期间检索结果不完整。',
      '重建索引', { confirmButtonText: '开始重建', cancelButtonText: '取消', type: 'warning' })
  reindexing.value = true
  try {
    const r = await api.reindex()
    ElMessage.success(`已开始重建 ${r.reindexed} 份文档`)
    for (let i = 0; i < 10; i++) {
      await new Promise(resolve => setTimeout(resolve, 3000))
      await load()
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

/** 能否作向量源：没有 embedding 模型即为否（预设的 notes 里也写明了原因）。 */
function canEmbed(preset: CloudPreset): boolean {
  return preset.embeddingModels.length > 0
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="system-page">
    <el-alert v-if="status?.indexWarning" type="error" :closable="false" show-icon class="block">
      <template #title>
        <span>索引与向量模型不一致</span>
        <el-button size="small" type="danger" text :loading="reindexing" @click="reindex">
          立即重建索引
        </el-button>
      </template>
      <div class="detail">{{ status.indexWarning }}</div>
    </el-alert>

    <el-alert
        v-if="status?.runMode === 'cloud'"
        type="warning" :closable="false" show-icon class="block"
        title="当前为云端模式：问题与检索到的文档片段会发送给云端服务商，请勿提问敏感内容" />

    <el-card shadow="never" class="block">
      <template #header><span class="card-title">运行状态</span></template>
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="对话模式">
          <el-tag size="small" :type="status?.runMode === 'cloud' ? 'warning' : 'info'">
            {{ status?.runMode }}
          </el-tag>
          <span v-if="status?.modeOverridden" class="hint">
            已被临时切换，重启后回到 {{ status?.configuredRunMode }}
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="当前对话模型">{{ status?.chatModel }}</el-descriptions-item>
        <el-descriptions-item label="向量模型">
          {{ status?.embeddingModel }}
          <span class="hint">来源 {{ status?.embeddingRunMode }}（切换需重启）</span>
        </el-descriptions-item>
        <el-descriptions-item label="云端可用性">
          <el-tag size="small" :type="status?.cloudReady ? 'success' : 'danger'">
            {{ status?.cloudReady ? '可用' : '不可用' }}
          </el-tag>
          <span v-if="!status?.cloudReady" class="hint">{{ status?.cloudBlockedReason }}</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header><span class="card-title">数据与存储</span></template>
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="数据目录">
          <span class="mono">{{ status?.dataDir }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="数据库 / 向量库">
          {{ status?.database }} · {{ status?.vectorStore }}
        </el-descriptions-item>
        <el-descriptions-item label="文档 / 分块">
          {{ status?.documentCount }} 份 · {{ status?.chunkCount }} 片段
        </el-descriptions-item>
        <el-descriptions-item label="长期记忆">
          {{ status?.memoryCount }} 条
          <span v-if="!status?.userIdentified" class="hint">（本次请求未带身份，故为 0）</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>
        <span class="card-title">生效参数</span>
        <span class="hint">这些是**跑起来实际生效**的值，可能与 yml 不同（环境变量可覆盖）</span>
      </template>
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="检索条数 topK">
          {{ status?.retrieval.topK }}
          <span class="hint">实测拐点，见开发文档 9.2</span>
        </el-descriptions-item>
        <el-descriptions-item label="相似度阈值 minScore">
          {{ status?.retrieval.minScore }}
        </el-descriptions-item>
        <el-descriptions-item label="查询改写">
          <el-tag size="small" :type="status?.retrieval.queryExpansion ? 'success' : 'info'">
            {{ status?.retrieval.queryExpansion ? '开' : '关' }}
          </el-tag>
          <span class="hint">
            关=单路检索；开则每问多一次模型调用（本知识库实测零增益，故默认关）
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="分块">
          {{ status?.retrieval.chunkSize }} 字 / 重叠 {{ status?.retrieval.chunkOverlap }} 字
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>
        <span class="card-title">内置厂商</span>
        <span class="hint">换厂商需改配置并重启；这里只做展示，不会写回配置文件</span>
      </template>
      <el-table :data="presets" size="small" border>
        <el-table-column label="厂商" width="180">
          <template #default="{ row }">
            {{ row.label }}
            <el-tag v-if="row.id === status?.cloudChatProviderId" size="small" type="warning">对话</el-tag>
            <el-tag v-if="row.id === status?.cloudEmbeddingProviderId" size="small" type="success">向量</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="对话模型" min-width="180">
          <template #default="{ row }">
            <span v-if="row.chatModels.length">{{ row.chatModels.slice(0, 2).join('、') }}</span>
            <span v-else class="hint">—</span>
          </template>
        </el-table-column>
        <el-table-column label="向量模型" min-width="200">
          <template #default="{ row }">
            <template v-if="canEmbed(row)">
              <div v-for="m in row.embeddingModels.slice(0, 2)" :key="m" class="mono">
                {{ m }}<span v-if="row.embeddingDimensions[m]">（{{ row.embeddingDimensions[m] }} 维）</span>
              </div>
            </template>
            <el-tag v-else size="small" type="danger">不能作向量源</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Key" width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="row.apiKeyConfigured ? 'success' : 'info'">
              {{ row.apiKeyConfigured ? '已配置' : '未配置' }}
            </el-tag>
            <div class="mono hint">{{ row.apiKeyEnv }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="notes" label="注意事项" min-width="260" show-overflow-tooltip />
      </el-table>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>
        <span class="card-title">连通性自检</span>
        <el-button size="small" :loading="checking" @click="runCheck">开始自检</el-button>
        <span class="hint">探 /models，不消耗生成额度；能区分"Key 不对（401）"与"网络不通"</span>
      </template>
      <el-table v-if="checkItems.length" :data="checkItems" size="small" border>
        <el-table-column label="端点" width="80">
          <template #default="{ row }">{{ row.target === 'chat' ? '对话' : '向量' }}</template>
        </el-table-column>
        <el-table-column prop="label" label="厂商" width="150" />
        <el-table-column prop="model" label="模型" width="200" />
        <el-table-column label="结果" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.ok ? 'success' : row.reachable ? 'warning' : 'danger'">
              {{ row.ok ? '连通' : row.reachable ? `HTTP ${row.status}` : '不可达' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="latencyMs" label="耗时" width="90">
          <template #default="{ row }">{{ row.latencyMs }} ms</template>
        </el-table-column>
        <el-table-column prop="message" label="说明" min-width="260" />
      </el-table>
      <el-empty v-else description="点「开始自检」检查云端端点是否可达" :image-size="60" />
    </el-card>
  </div>
</template>

<style scoped>
.system-page {
  display: flex;
  flex-direction: column;
}

.block {
  margin-bottom: 14px;
}

.card-title {
  font-weight: 600;
  margin-right: 8px;
}

.detail {
  font-size: 12px;
  line-height: 1.7;
}

.hint {
  color: #9aa0a6;
  font-size: 12px;
  margin-left: 6px;
}

.mono {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  word-break: break-all;
}
</style>
