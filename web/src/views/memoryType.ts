import type { MemoryItem } from '../api/client'

/**
 * 记忆类型的展示与选择文案（聊天页"记住这条"与记忆管理页共用）。
 *
 * 为什么单独抽一个文件：这两处必须给出**完全一致**的类型解释。
 * 用户在聊天页选了"偏好"，到记忆页看到的标签却叫别的名字，就会怀疑存错了类型 ——
 * 而"存错类型"正是这套长期记忆实测栽过的跟头（结论型注入是"可直接使用"，
 * 模型不会照做，用户看到的就是"记了等于没记"）。
 */

/** 记忆类型 → el-tag 颜色。 */
export function memoryTypeTag(type: MemoryItem['memoryType']) {
  switch (type) {
    case 'PREFERENCE': return 'warning'
    case 'FACT': return 'success'
    default: return 'info'
  }
}

/** 记忆类型 → 中文短标签。 */
export function memoryTypeLabel(type: MemoryItem['memoryType']) {
  switch (type) {
    case 'PREFERENCE': return '偏好'
    case 'FACT': return '事实'
    default: return '结论'
  }
}

/**
 * 新增记忆时的类型选项；`value` 为空串表示交给后端按措辞自动判定。
 *
 * 默认给"自动判定"是有意的：用户写"每次回答结束后加一句（回答完毕）"时，
 * 不会去想这算偏好还是结论，但存错类型的代价是这条记忆彻底不生效。
 * 同时保留手动选择 —— 措辞里没有祈使词的偏好（如"我是做后端的"）会被判成结论。
 */
export const MEMORY_TYPE_OPTIONS: { value: MemoryItem['memoryType'] | ''; label: string; hint: string }[] = [
  { value: '', label: '自动判定', hint: '按措辞自动分类，不确定就选它' },
  { value: 'PREFERENCE', label: '偏好（必须遵守）', hint: '回答方式：长度、语气、格式；会注入"请严格遵守"' },
  { value: 'FACT', label: '事实（背景）', hint: '关于你的背景信息，只作参考，不强制模型做什么' },
  { value: 'CONCLUSION', label: '结论（可直接引用）', hint: '已确认过的判断，模型可以直接引用' }
]
