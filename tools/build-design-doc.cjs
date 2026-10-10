// 从完整开发文档切片生成仓库内的精简版 docs/DESIGN.md
// 用法：node tools/build-design-doc.cjs [完整文档路径]
//
// 设计意图：
//  - **脚本生成而非手抄**：手抄必然走样，且完整文档每改一次就要人肉同步一遍。
//  - **生成即清理**：完整文档里有维护者本机的绝对路径与私有工程名，公开版必须去掉；
//    把清理放在生成流程里，比"记得手动删"可靠。
const fs = require('fs')
const path = require('path')

const SRC = process.argv[2] || 'C:\\Users\\Jiang\\dsh-test\\智能问答助手-开发文档.md'
const OUT = path.join(__dirname, '..', 'docs', 'DESIGN.md')

const lines = fs.readFileSync(SRC, 'utf8').split('\n')

function section(titlePrefix, stopTitles) {
  const start = lines.findIndex(l => (l.startsWith('## ') || l.startsWith('### '))
    && l.replace(/^#+\s*/, '').startsWith(titlePrefix))
  if (start < 0) throw new Error('找不到章节: ' + titlePrefix)
  let end = lines.length
  for (let i = start + 1; i < lines.length; i++) {
    if (stopTitles.some(t => lines[i].startsWith(t))) { end = i; break }
  }
  return lines.slice(start, end)
}

const parts = []

parts.push(`# qa-assistant 设计说明（精简版）

> 本文件是**面向读者/使用者的精简版**，只收录"理解实现、二次开发、排查问题"所必需的部分。
> 完整的施工文档（含逐步实测记录、施工期踩坑、逐轮决策过程）保存在项目维护者本地，
> 不随仓库分发 —— 其中包含具体机器的显存曲线、私有环境路径等信息，不适合公开。

**生成方式**：由完整开发文档**脚本切片**生成（\`tools/build-design-doc.cjs\`），不是手抄。
改了完整文档后请重新生成，避免两份说法不一致。

**版本对应**：精简自 完整开发文档 v1.13（2026-10-10）
**代码版本**：qa-assistant 1.0.0-SNAPSHOT

---

`)

parts.push('## 1. 项目概述\n')
parts.push(...section('1.1 背景', ['### 1.2']))
parts.push(...section('1.2 核心目标', ['### 1.3']))
parts.push('---\n')

parts.push('## 2. 需求规格\n')
parts.push(...section('2.1 功能需求', ['### 2.2']))
parts.push(...section('2.2 非功能需求', ['### 2.3']))
parts.push(...section('2.3 明确不做', ['## 3.']))
parts.push('---\n')

parts.push('## 3. 总体设计\n')
parts.push(...section('3.1 架构', ['### 3.2']))
parts.push(...section('3.2 技术选型', ['### 3.3']))
parts.push(...section('3.3 模块划分', ['### 3.4']))
parts.push(...section('3.4 编译目标', ['## 4.']))
parts.push('---\n')

parts.push('## 4. 数据模型与接口\n')
parts.push(...section('4.1 数据模型', ['### 4.2']))
parts.push(...section('4.2 接口设计', ['### 4.3']))
parts.push(...section('4.3 核心流程', ['### 4.4']))
parts.push('---\n')

parts.push('## 5. 双模式与向量库\n')
parts.push(...section('4.4 双模式设计', ['### 4.5']))
parts.push(...section('4.5 向量库抽象', ['### 4.6']))
parts.push('---\n')

parts.push('## 6. 记忆体系（核心章节）\n')
parts.push(...section('4.6 记忆体系设计', ['## 5.']))
parts.push('---\n')

parts.push('## 7. 关键实测数据\n')
parts.push('> 下面四组数据是设计的**依据**，不是结论的点缀。改参数前先看对应那节。\n')
parts.push(...section('9.2 检索召回评测', ['### 9.3']))
parts.push(...section('9.3 云端思考模式对照实测', ['### 9.4']))
parts.push(...section('9.4 P6-① 上下文预算压测', ['### 9.5']))
parts.push(...section('9.5 P6-② 异常回退矩阵', ['## 10.']))
parts.push('---\n')

parts.push('## 8. 已定稿决策清单\n')
parts.push('> 这些是**已经拍过板的取舍**。想改之前请先读对应那行的"为什么"——\n'
  + '> 多数看起来"绕"的地方，背后都是一次真实故障。\n')
parts.push(...section('11.1 决策表', ['### 11.2']))
parts.push('---\n')

parts.push('## 9. 开源与分发要点\n')
parts.push(...section('12.2 四个门槛与对策', ['### 12.3']))
parts.push(...section('12.4 授权与合规', ['### 12.5']))
parts.push(...section('12.5 分发形态', ['### 12.6']))
parts.push(...section('12.6 `num-ctx: auto`', ['### 12.7']))

let out = parts.join('\n').replace(/\n{4,}/g, '\n\n\n')

// ---- 公开版清理：维护者本机路径与私有工程名 ----
out = out.replace(
  /\| 1 \| 项目目录 \| `[^`]*` \| 实地确认[^|]*\|/,
  '| 1 | 项目目录 | 任意可写目录（本仓库自身所在目录） | 路径本身不影响功能；数据默认落在用户目录 `~/.qa-assistant`，与代码目录解耦 |'
)
out = out.replaceAll('C:\\Users\\Jiang\\projects\\qa-assistant', '<项目根目录>')
out = out.replaceAll('C:\\Users\\Jiang\\.qa-assistant', '<数据目录>')
out = out.replaceAll('C:\\Users\\Jiang', '<用户目录>')
out = out.replaceAll('web-ai-project', '<既有工程>')

fs.mkdirSync(path.dirname(OUT), { recursive: true })
fs.writeFileSync(OUT, out, 'utf8')

console.log(`已生成 ${OUT}`)
console.log(`字符 ${out.length}，行 ${out.split('\n').length}`)
const leaks = ['C:\\Users\\Jiang', 'web-ai-project', 'dsh-test']
leaks.forEach(k => {
  const n = out.split(k).length - 1
  console.log(`  私有信息「${k}」残留: ${n} 次`)
})
