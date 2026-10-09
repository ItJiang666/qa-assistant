# qa-assistant · 智能问答助手

> 上传你的文档，用本地大模型问它 —— **全程离线，数据不出内网**。

一个基于 RAG（检索增强生成）的知识库问答助手：文档切片 → 向量化入库 → 提问时检索相关片段 → 交给大模型生成**有出处**的回答。

---

## 三步快速开始

```bash
# 1) 拉模型（一次性，约 8.5 GB）
ollama pull qwen3.5:9b && ollama pull qwen3-embedding:4b

# 2) 跑起来（需要 JDK 17 或更高）
java -jar qa-assistant-1.0.0-SNAPSHOT.jar

# 3) 打开浏览器
#    http://localhost:8080
```

**不需要**装 MySQL、不需要 Node、不需要 Python —— 默认使用内置 H2 文件库，数据落在
`%USERPROFILE%\.qa-assistant\`（Windows）或 `~/.qa-assistant/`（Linux/macOS）。

---

## 前置条件

| 项 | 要求 | 说明 |
|---|---|---|
| JDK | **17 或更高** | 已在 17 / 25 上验证；官方支持到 Java 26 |
| Ollama | 任意近期版本 | 仅在本地模式需要 |
| 显卡 | 建议独立显卡 ≥ 8 GB 显存 | 无独显可用云端模式（见下） |

---

## 两种运行模式

由环境变量 `QA_PROFILES` 控制（默认 `h2,local`）：

| 组合 | 含义 |
|---|---|
| `h2,local` | **默认**：零安装 + 本地模型，完全离线 |
| `mysql,local` | 用本机 MySQL 存元数据 |
| `h2,cloud` | 本地库 + 云端模型（无显卡的机器走这条） |

云端模式需要两个 Key，见 `.env.example`：

```bash
set QA_PROFILES=h2,cloud
set DEEPSEEK_API_KEY=sk-xxx        # 对话
set SILICONFLOW_API_KEY=sk-xxx     # 向量
```

---

## 特性

- **三层记忆体系**
  - L1 上下文窗口：按 token 分配预算，不让检索片段被挤掉
  - L2 会话记忆：同一会话内的多轮连贯，**落库，重启不丢**
  - L3 长期记忆：跨会话记住你的偏好与事实，**看得见、改得掉、一键清空**
- **回答必须有依据**：只使用检索到的原文，资料里没有就明确说"未找到"，禁止编造
- **引用来源**：每条回答标注来自哪个文档的哪一段，可核对
- **文档格式**：txt / md / docx / pdf / xlsx / html

---

## 技术栈

Spring Boot 4.1.1 · LangChain4j 1.20.2 · MyBatis · H2 / MySQL · Vue 3 · **编译目标 Java 17**

---

## 从源码构建

```bash
git clone <repo-url>
cd qa-assistant
mvn clean package
java -jar target/qa-assistant-1.0.0-SNAPSHOT.jar
```

---

## 常见问题

| 问题 | 原因与处理 |
|---|---|
| 启动报 `UnsupportedClassVersionError` | JDK 版本低于 17。`java -version` 确认 |
| 回答很慢、每个问题先"思考"十几秒 | 对话模型误用了推理模型。请用 `qwen3.5:9b`，**不要**用 `deepseek-r1:8b` |
| 提问后长时间无响应或显存溢出 | 显卡显存不足。把 `qa.local.num-ctx` 降到 `8192` 或改走云端模式 |
| 上传的扫描版 PDF 检索不到内容 | 扫描版是图片，没有文字层，需要 OCR（本期不支持，见开发文档第 9 章） |
| 重启后知识库空了 | 检查 `%USERPROFILE%\.qa-assistant\index\` 是否存在且可写 |

---

## 文档与协议

- 设计文档：`docs/开发文档.md`（20 项已锁定决策）
- 开源协议：[Apache-2.0](LICENSE)
- 模型：由 Ollama 提供，本仓库**不包含**任何模型权重，请自行 `ollama pull`

---

## 状态

开发中 —— 当前进度见开发文档第 7 章「开发计划」。
