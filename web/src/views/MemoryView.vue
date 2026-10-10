<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type MemoryItem } from '../api/client'
import { MEMORY_TYPE_OPTIONS, memoryTypeLabel, memoryTypeTag } from './memoryType'

const emit = defineEmits<{ (e: 'changed'): void }>()

const memories = ref<MemoryItem[]>([])
const loading = ref(false)
const newContent = ref('')
const newImportance = ref(4)

/** 新增记忆的类型；空字符串表示"让后端自动判定"（见 memoryType.ts 的说明）。 */
const newType = ref<MemoryItem['memoryType'] | ''>('')
const adding = ref(false)

const TYPE_OPTIONS = MEMORY_TYPE_OPTIONS
const typeLabel = memoryTypeLabel
const typeTag = memoryTypeTag

async function load() {
  loading.value = true
  try {
    const data = await api.listMemories()
    memories.value = data.items
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    loading.value = false
  }
}

async function handleAdd() {
  const content = newContent.value.trim()
  if (!content) {
    ElMessage.warning('请先输入要记住的内容')
    return
  }
  adding.value = true
  try {
    const saved = await api.createMemory(content, newImportance.value, newType.value || undefined)
    // 回显真实类型：自动判定选了什么，用户必须看得见，否则下次还是会怀疑"记了没生效"
    ElMessage.success(
        newType.value ? '已记住' : `已记住（自动判定为「${typeLabel(saved.memoryType)}」）`)
    newContent.value = ''
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    adding.value = false
  }
}

async function handleDelete(row: MemoryItem) {
  try {
    await api.deleteMemory(row.id)
    ElMessage.success('已删除')
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

async function handleClear() {
  await ElMessageBox.confirm(
      '这会永久删除你的全部长期记忆，且无法恢复。之后的对话不会再记得你的任何偏好或背景。',
      '清空全部记忆', { type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消' })
  try {
    const result = await api.clearMemories()
    ElMessage.success(`已清空 ${result.deleted} 条记忆`)
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

async function handleImportanceChange(row: MemoryItem) {
  try {
    await api.updateMemory(row.id, undefined, row.importance)
    ElMessage.success('已更新重要度')
    emit('changed')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 14px">
      <template #title>
        这里是你跨会话的长期记忆 —— 系统在每轮对话后异步抽取，答下一个新会话时自动生效。
      </template>
      <div class="alert-body">
        模型抽取一定会有抽错的时候，所以这一页必须存在：<b>看得见、改得掉、一键清空</b>。
        如果发现它记错了什么，删掉即可 —— 之后的回答立刻不再受它影响。
      </div>
    </el-alert>

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>手动记住一条</span>
          <span class="hint">比等模型自己抽更可靠 —— 你想让它记住的，直接写在这里</span>
        </div>
      </template>
      <div class="add-row">
        <el-input
            v-model="newContent"
            placeholder="例如：回答我时尽量简短，控制在三句话以内"
            maxlength="500"
            show-word-limit
            @keydown.enter.prevent="handleAdd" />
        <el-select v-model="newType" style="flex: 0 0 170px">
          <el-option
              v-for="opt in TYPE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value">
            <span>{{ opt.label }}</span>
            <span class="opt-hint">{{ opt.hint }}</span>
          </el-option>
        </el-select>
        <el-rate v-model="newImportance" :max="5" style="flex: 0 0 auto" />
        <el-button type="primary" :loading="adding" @click="handleAdd">记住</el-button>
      </div>
      <div class="add-hint">
        写"每次…/请…/不要…"这类要求时，类型务必是<b>偏好</b> ——
        只有偏好会在注入时写成"请严格遵守"，存成结论模型基本不会照做。
      </div>
    </el-card>

    <el-card shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>已记住的内容（{{ memories.length }}）</span>
          <div>
            <el-button size="small" :loading="loading" @click="load">刷新</el-button>
            <el-button size="small" type="danger" plain :disabled="memories.length === 0" @click="handleClear">
              清空全部
            </el-button>
          </div>
        </div>
      </template>

      <el-table :data="memories" v-loading="loading" empty-text="还没有记住任何内容">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="typeTag(row.memoryType)">{{ typeLabel(row.memoryType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="content" label="内容" min-width="280" show-overflow-tooltip />
        <el-table-column label="重要度" width="150">
          <template #default="{ row }">
            <el-rate v-model="row.importance" :max="5" @change="handleImportanceChange(row)" />
          </template>
        </el-table-column>
        <el-table-column label="置信度" width="90">
          <template #default="{ row }">
            <span class="mono">{{ row.confidence?.toFixed(2) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="hitCount" label="被用" width="70" />
        <el-table-column label="来源会话" width="130">
          <template #default="{ row }">
            <span v-if="row.sourceConversationId" class="mono small">
              {{ row.sourceConversationId.slice(0, 8) }}…
            </span>
            <span v-else class="muted">手动添加</span>
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
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.hint {
  font-size: 12px;
  color: #9aa0a6;
}

.alert-body {
  font-size: 12px;
  line-height: 1.7;
  margin-top: 4px;
}

.add-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.add-hint {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.7;
  color: #9aa0a6;
}

/* 下拉项里的补充说明；slot 内容在本组件编译，scoped 属性会带到选项节点上 */
.opt-hint {
  float: right;
  margin-left: 16px;
  font-size: 11px;
  color: #9aa0a6;
}

.small {
  font-size: 12px;
}

.muted {
  color: #c4c8ce;
  font-size: 12px;
}
</style>
