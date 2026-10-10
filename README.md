# qa-assistant · 智能问答助手

> 上传你的文档，用大模型问它 —— **回答只依据你上传的资料，并给出处；资料里没有就明说找不到**。

一个基于 RAG（检索增强生成）的知识库问答助手：文档切片 → 向量化入库 → 提问时检索相关片段 → 交给大模型生成**有出处**的回答。

支持**本地模型（Ollama，全程离线、数据不出内网）**与**云端模型（10 家国内厂商开箱可用）**，
并可在界面上**运行时一键切换**。

---

## 四步快速开始

```bash
# 1) 拉模型（一次性，约 8.5 GB）
ollama pull qwen3.5:9b && ollama pull qwen3-embedding:4b

# 2) 生成项目专用的向量模型（一次性，复用上面已拉取的权重，不额外下载）
ollama create qa-embed:4b -f deploy/ollama/Modelfile.qa-embed

# 3) 启动（Windows 双击 start.cmd；命令行见下）
java -jar qa-assistant-1.0.0-SNAPSHOT.jar

# 4) 打开浏览器
#    http://localhost:8080
```

或者直接双击 / 执行**一键启动脚本**，它会先体检再启动：

| 平台 | 命令 | 脚本会检查 |
|---|---|---|
| Windows | 双击 `start.cmd`（或 `start.cmd 8081` 换端口） | JDK 版本、程序包是否带前端、Ollama 是否在跑、`.env` 是否存在 |
| Linux / macOS | `./start.sh`（或 `./start.sh 8081`） | 同上 |

> **脚本刻意不自动拉模型。** 8.5 GB 的下载不该藏在"双击启动"背后偷偷跑；
> 它只把该执行的命令打印出来，由你决定什么时候拉。
>
> 脚本默认端口 8080，可用 `start.cmd 8081` 指定别的端口。

**不需要**装 MySQL、不需要 Node、不需要 Python —— 默认使用内置 H2 文件库，数据落在
`%USERPROFILE%\.qa-assistant\`（Windows）或 `~/.qa-assistant/`（Linux/macOS）。
想整体搬到别的盘：设环境变量 `QA_DATA_DIR=D:/qa-data`（改后需重启）。

> 第 2 步虽然多了一行，但它把"每一问都慢"变成"载入一次就稳定"：
> 向量模型自带的上下文是 40960，不压下来它会独占约 **10 GB 显存**，把对话模型挤出去，
> 于是**每一次提问都要重新加载两个模型**。实测每一问 **7.7 秒 → 稳态 0.9~1.7 秒**。
> **多一行、少七秒**，值得留着。

---

## 界面速览

| 页面 | 用途 |
|---|---|
| **对话问答** | 提问、流式回答、引用来源（默认折叠，点标题行展开）、把某条回答「记住这条」 |
| **文档管理** | 上传/删除文档、重建索引、查看分块数与状态 |
| **记忆管理** | 查看 / 修改 / 删除 / 一键清空长期记忆；手动新增时可指定类型 |
| **系统状态** | 当前模式与模型、**思考模式开关**、生效参数、内置厂商表（Key 配没配）、存储路径、连通性自检 |

<!-- 截图放这里（拍好放进 docs/images/ 即可生效；缺失时不影响阅读） -->

<details>
<summary><b>截图清单（待补）</b></summary>

| 文件 | 内容 | 建议视口 |
|---|---|---|
| `docs/images/01-chat.png` | 对话页：一个带引用来源的回答 | 1440×900 |
| `docs/images/02-sources.png` | 引用来源展开后的样子 | 1440×900 |
| `docs/images/03-documents.png` | 文档管理页：上传区 + 文档列表 | 1440×900 |
| `docs/images/04-system.png` | 系统状态页：运行状态（含思考开关）+ 厂商表 | 1440×900 |

</details>

---

## 前置条件

| 项 | 要求 | 说明 |
|---|---|---|
| JDK | **17 或更高** | 已在 **17 与 25** 上各跑过一次验收；官方支持到 Java 26 |
| Ollama | 任意近期版本 | **仅在本地模式需要**；走云端模式可以不装 |
| 显卡 | 建议独显 ≥ 8 GB 显存 | 无独显也能用：`num-ctx` 会自动降级，或直接走云端模式 |

`qa.local.num-ctx` 默认为 **`auto`**：启动时读 `nvidia-smi` 的显存总量，
≥11 GB 用 16384、≥7 GB 用 8192、探不到（无独显/非 N 卡）用保守的 4096 并在日志里
**明确告诉你"你正跑在 4096，建议改用云端模式"** —— 让别人知道为什么慢，好过让他自己猜。

---

## 两种运行模式

由环境变量 `QA_PROFILES` 控制（默认 `h2,local`）：

| 组合 | 含义 |
|---|---|
| `h2,local` | **默认**：零安装 + 本地模型，完全离线 |
| `mysql,local` | 用本机 MySQL 存元数据 |
| `h2,cloud` | 本地库 + 云端模型（无显卡的机器走这条） |

> ⚠️ `QA_PROFILES` **只管数据源**。模型用本地还是云端由 `qa.mode` 决定，
> 且可以在界面上运行时切换（重启后回到 `qa.mode` 的值）。

云端模式需要 Key，写进项目根目录的 `.env`（**不要**写进配置文件，见下方"授权与合规"）：

```bash
set QA_PROFILES=h2,local
set QA_MODE=cloud
set DEEPSEEK_API_KEY=sk-xxx        # 对话
set SILICONFLOW_API_KEY=sk-xxx     # 向量
```

`.env` 已被 `.gitignore` 排除，**永远不会被提交**。界面上只显示"已配置 / 未配置"，
不回显内容；`/api/v1/system/status` 也只回**末四位掩码**（如 `***c747`），
用于核对"现在用的是哪一把 Key"。

---

## 特性

- **三层记忆体系**
  - L1 上下文窗口：按 token 分配预算，不让检索片段被挤掉
  - L2 会话记忆：同一会话内的多轮连贯，**落库，重启不丢**（切模型、刷新页面也接得上）
  - L3 长期记忆：跨会话记住你的偏好与事实，**看得见、改得掉、一键清空**
- **回答必须有依据**：只使用检索到的原文；资料里没有就明确说"未找到"，禁止编造
- **引用来源**：每条回答标注来自哪个文档的哪一段，可核对；默认折叠，不挤占正文
- **文档格式**：txt / md / docx / pdf / xlsx / html（扫描版 PDF 无文字层，需 OCR，本期不支持）
- **本地与云端可运行时切换**（对话模型立即生效；向量模型需重启并重建索引）
- **思考模式可运行时开关**：开＝更准（首字多约 1 秒），关＝更快。实测见设计文档
- **故障有话说**：连不上 / Key 无效 / 余额不足 / 被限流 / 模型名写错 …… 每种都告诉你**该做什么**
- **元问题与冒烟口令**：问"你是谁""你是什么模型""测试"直接给固定回复，不依赖模型

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

> ⚠️ **五家不能作向量源**（DeepSeek / Kimi / 方舟 / MiniMax / 阶跃）：它们的向量接口要么不存在、
> 要么不走 OpenAI 协议。配成向量源必然失败 —— 界面上会直接标红，别绕过去。
>
> 完整清单（各家可用模型名、向量维度、注意事项）在界面上：
> **系统状态 → 内置厂商**，并且能看到 Key 配没配。

---

## 技术栈

Spring Boot 4.1.1 · LangChain4j 1.20.2 · MyBatis · H2 / MySQL · Vue 3 · Element Plus · **编译目标 Java 17**

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
> 更隐蔽的一种：`target/classes` 里的 `index.html` 与 `assets/` 名字对不上，
> 打开页面**一片空白**且控制台 404。`start.cmd` / `start.sh` 会检查 jar 里有没有前端来提前拦住它。

**前端开发模式**（改代码即时热更新，不必反复打包）：

```bash
# 终端 1：后端
mvn spring-boot:run

# 终端 2：前端（访问 http://localhost:5173，/api 会代理到 8080）
cd web && pnpm dev
```

**出发布包**：

```bash
package-release.cmd            # Windows
./package-release.sh           # Linux / macOS
# 产物：release/qa-assistant-1.0.0.zip + qa-assistant-1.0.0.zip.sha256
```

---

## 常见问题

| 问题 | 原因与处理 |
|---|---|
| 启动报 `UnsupportedClassVersionError` | JDK 版本低于 17。`java -version` 确认；`start.cmd` 会在启动前拦住 |
| 页面一片空白 / 控制台 404 | jar 里没有前端产物，或 `index.html` 与 `assets/` 名字对不上。重新 `cd web && pnpm build` 再 `mvn clean package` |
| 回答很慢、每个问题先"思考"十几秒 | 对话模型误用了推理模型。请用 `qwen3.5:9b`，**不要**用 `deepseek-r1:8b` |
| 每个问题都要等 7~10 秒，日志里 `load_duration` 一直是好几秒 | 两个模型在显存里互相挤：多半是漏了快速开始的第 2 步。执行 `ollama create qa-embed:4b -f deploy/ollama/Modelfile.qa-embed`，并把 `qa.local.embedding-model` 设为 `qa-embed:4b`。用 `ollama ps` 可确认两个模型是否同时在列 |
| 闲置一会儿后，第一个问题又变慢（之后恢复） | 正常现象：Ollama 默认闲置 5 分钟就卸载模型，下一问要重新载入显存（实测第 1 问约 7.9 s、第 2 问约 3.0 s，之后回到 1 秒内）。想避免可给 Ollama 设 `OLLAMA_KEEP_ALIVE=30m`（或 `-1` 永不卸载，代价是显存长期被占） |
| 提问后长时间无响应或显存溢出 | 显存不足。把 `qa.local.num-ctx` 显式降到 `8192`，或改走云端模式 |
| 回答偶尔"资料里明明有却说找不到" | 点「系统状态」看**思考模式**是否被关了。实测关掉思考会把资料里确实存在的答案答成"未找到"（本地/云端模型差异见设计文档 7. 关键实测数据）；把开关打开即可 |
| 提示"模型服务拒绝了当前 API Key（401）" | Key 不对/过期，或与端点地域不匹配（百炼的 Key 与地域绑定）。改 `.env` 后**必须重启应用**，再到「系统状态」点自检确认 |
| 提示"云端账户余额不足或额度已用尽（402）" | 去厂商控制台充值，或先在「模型来源」里切回本地模型 |
| 上传的扫描版 PDF 检索不到内容 | 扫描版是图片，没有文字层，需要 OCR（本期不支持） |
| 重启后知识库空了 | 检查 `%USERPROFILE%\.qa-assistant\index\` 是否存在且可写 |
| 改了 `.env` 但界面显示还是旧值 | 配置只在**启动时**读取，改完要重启应用；页面每 30 秒会检测到后端配置变化并提示你刷新 |
| `start.cmd` 双击后一堆"不是内部或外部命令" | 该文件被编辑器改存成了 UTF-8。见下方"贡献与约定" |

---

## 贡献与约定

- **`start.cmd` 必须以 GBK(936) 保存。** cmd.exe 按系统 ANSI 代码页解析 `.cmd`，
  存成 UTF-8 会让中文注释变成乱码命令并直接报错；同理**不要**在脚本里 `chcp 65001`。
  编辑后请确认文件仍是 GBK，否则 Windows 用户双击即报错。
- **`start.sh` 必须是 LF 换行**（`.gitattributes` 已声明）。
- 提交前请确认**没有把 `.env` 或任何真实 Key 带进仓库**（`.gitignore` 已排除，但别用 `git add -f`）。
- 代码注释用中文、标识符用英文；改行为时**同步改 `docs/DESIGN.md` 对应章节**。

---

## 文档与协议

- **设计说明（精简版）**：[`docs/DESIGN.md`](docs/DESIGN.md) —— 架构、数据模型、接口、
  记忆体系、四组关键实测数据、41 项已定稿决策。**读代码前建议先读它。**
- 开源协议：[Apache-2.0](LICENSE)
- 模型：由 Ollama 提供，本仓库**不包含**任何模型权重，请自行 `ollama pull`

---

## 状态

功能已完整（P0–P6 全部完成并实测验收）：

- ✅ 检索问答 + 引用来源、文档入库、三层记忆
- ✅ 本地 / 云端双模式 + 运行时切换、10 家厂商预设、思考模式开关
- ✅ 系统状态页、上下文预算压测（长文档 10 轮不截断）、异常回退矩阵（15 种故障逐条给自救指引）
- ⬜ 截图待补、干净机器验收清单见 `docs/ACCEPTANCE.md`
