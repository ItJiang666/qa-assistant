# qa-assistant · 智能问答助手

> 上传你的文档，用本地大模型问它 —— **全程离线，数据不出内网**。

一个基于 RAG（检索增强生成）的知识库问答助手：文档切片 → 向量化入库 → 提问时检索相关片段 → 交给大模型生成**有出处**的回答。

---

## 四步快速开始

```bash
# 1) 拉模型（一次性，约 8.5 GB）
ollama pull qwen3.5:9b && ollama pull qwen3-embedding:4b

# 2) 生成项目专用的向量模型（一次性，复用上面已拉取的权重，不额外下载）
#    把向量模型的上下文窗口从自带的 40960 压到 2048：不改这一步，
#    它会独占约 10 GB 显存并把对话模型挤出去，导致每次提问都要重新加载两个模型
#    （实测每一问 7.7 秒 → 改后稳态 0.9~1.7 秒）
ollama create qa-embed:4b -f deploy/ollama/Modelfile.qa-embed

# 3) 跑起来（需要 JDK 17 或更高）
java -jar qa-assistant-1.0.0-SNAPSHOT.jar

# 4) 打开浏览器
#    http://localhost:8080
```

**不需要**装 MySQL、不需要 Node、不需要 Python —— 默认使用内置 H2 文件库，数据落在
`%USERPROFILE%\.qa-assistant\`（Windows）或 `~/.qa-assistant/`（Linux/macOS）。

> 第 2 步虽然多了一行，但它把"每一问都慢"变成"载入一次就稳定"：实测每一问 7.7 秒 →
> 稳态 0.9~1.7 秒（首字 0.5~0.7 秒）。**多一行、少七秒**，值得留着。

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
- **本地与云端可运行时切换**：页面上点一下即切换（对话模型立即生效；向量模型需重启并重建索引）
- **多家厂商开箱可用**：云端以 OpenAI 兼容协议为准，换厂商 = 改一行 `provider` + 填对应 Key

---

## 支持的模型厂商

本地模式用 Ollama；云端模式内置 **10 家**预设。换厂商只需改 `qa.cloud.chat.provider` /
`qa.cloud.embedding.provider`，再把对应 Key 填进 `.env`。

| 厂商 | provider | 对话 | 向量 | 注意事项 |
|---|---|---|---|---|
| DeepSeek（深度求索） | `deepseek` | ✅ | ❌ | 无 embedding 接口；`deepseek-chat` / `deepseek-reasoner` 已停用，现役 `deepseek-flash` |
| 阿里云百炼 | `dashscope` | ✅ | ✅ 1024 维 | Key 与地域绑定，跨地域报 401；单次最多 10 条 |
| 硅基流动 | `siliconflow` | ✅ | ✅ 2560 维 | 官方未标注默认维度，必须显式配 `dimension` |
| 智谱 BigModel（GLM） | `zhipu` | ✅ | ✅ 2048 维 | base-url 是 `/api/paas/v4`，**不是** `/v1` |
| 月之暗面 Kimi | `kimi` | ✅ | ❌ | 无 embedding；`moonshot-v1-*` 已下线 |
| 火山方舟（豆包） | `ark` | ✅ | ❌ | embedding **不走 OpenAI 协议**，必须用方舟 SDK |
| 腾讯混元 | `hunyuan` | ✅ | ✅ 固定 1024 | 不要传 `dimensions`；`stop` 语义与 OpenAI 相反 |
| MiniMax | `minimax` | ✅ | ❌ | 部分 OpenAI 参数被忽略；`n` 只能为 1 |
| 阶跃星辰 StepFun | `stepfun` | ✅ | ❌ | 无 embedding |
| 百度千帆 v2 | `qianfan` | ✅ | ✅ 维度固定 | 鉴权是 Bearer，**不是** AK/SK 签名 |

> ⚠️ **四家不能作向量源**（DeepSeek / Kimi / 方舟 / MiniMax / 阶跃）：它们的向量接口要么不存在、
> 要么不走 OpenAI 协议。配成向量源必然失败 —— 界面上会直接标红，别绕过去。
>
> 完整清单（各家可用模型名、向量维度、注意事项）在界面上：
> **系统状态 → 内置厂商**，并且能看到 Key 配没配（只显示"已配置/未配置"，不回显内容）。

---

## 技术栈

Spring Boot 4.1.1 · LangChain4j 1.20.2 · MyBatis · H2 / MySQL · Vue 3 · **编译目标 Java 17**

---

## 从源码构建

```bash
git clone <repo-url>
cd qa-assistant

# 1) 前端（仓库里已含构建产物，只在改前端时需要重新执行）
cd web && pnpm install && pnpm build && cd ..

# 2) 后端
mvn clean package

# 3) 运行
java -jar target/qa-assistant-1.0.0-SNAPSHOT.jar
```

> ⚠️ **顺序不能反**：Vite 的产物输出到 `src/main/resources/static/`，
> 必须先构建前端再 `mvn package`，否则打进 jar 的还是上一次的界面 ——
> 表现为"我明明改了前端，运行起来却没变化"。

**前端开发模式**（改代码即时热更新，不必反复打包）：

```bash
# 终端 1：后端
mvn spring-boot:run

# 终端 2：前端（访问 http://localhost:5173，/api 会代理到 8080）
cd web && pnpm dev
```

---

## 常见问题

| 问题 | 原因与处理 |
|---|---|
| 启动报 `UnsupportedClassVersionError` | JDK 版本低于 17。`java -version` 确认 |
| 回答很慢、每个问题先"思考"十几秒 | 对话模型误用了推理模型。请用 `qwen3.5:9b`，**不要**用 `deepseek-r1:8b` |
| 每个问题都要等 7~10 秒，日志里 `load_duration` 一直是好几秒 | 两个模型在显存里互相挤：多半是漏了上面第 2 步。执行 `ollama create qa-embed:4b -f deploy/ollama/Modelfile.qa-embed`，并把 `qa.local.embedding-model` 设为 `qa-embed:4b`。用 `ollama ps` 可确认两个模型是否同时在列 |
| 闲置一会儿后，第一个问题又变慢（之后恢复） | 正常现象：Ollama 默认闲置 5 分钟就卸载模型，下一问要重新载入显存（实测第 1 问约 7.9 s、第 2 问约 3.0 s，之后回到 1 秒内）。想避免可给 Ollama 设 `OLLAMA_KEEP_ALIVE=30m`（或 `-1` 永不卸载，代价是显存长期被占） |
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
