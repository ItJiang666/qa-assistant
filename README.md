# qa-assistant · 智能问答助手

把文档变成可以对话的知识库。上传资料后直接提问，回答基于检索到的原文生成，并标注引用出处；资料里没有的内容会明确说明找不到。

支持两种模型来源：本地 Ollama（全程离线，数据不出内网）和云端厂商（内置 10 家国内主流服务商），可以在界面上运行时切换。

---

## 项目介绍

这是一个面向**内部资料检索**的知识库问答系统。它解决的是"资料很多、查起来麻烦"这类问题：制度文件、操作手册、产品文档、会议纪要上传之后，用自然语言提问就能拿到答案，不用逐页翻。

工作流程分四步：文档解析为纯文本 → 按语义切成片段 → 向量化后存入本地索引 → 提问时先检索相关片段，再交给大模型组织答案。

系统在检索和生成之间加了一道约束：模型只能看到检索命中的片段，回答需要为每段结论标注来源。这样答案可以核对，而不是只凭模型记忆生成。

两类模型来源的特点：

| 模式 | 数据流向 | 需要的条件 |
|---|---|---|
| 本地（Ollama） | 全程在本机，不联网 | 有独立显卡更好，8 GB 显存以上体验较好 |
| 云端（API） | 请求发往所选厂商 | 网络可达 + 对应厂商的 API Key |

同一个实例可以随时切换，会话、文档和记忆都保留。

---

## 主要功能

### 知识库问答

- 提问后流式返回答案，边生成边显示
- 每条回答标注引用来源，可展开查看来自哪个文档的哪一段
- 混合提问（概念 + 本单位规定）分两段作答，分别标注依据
- 与知识库无关的问题（常识、计算、闲聊）正常回答，并在开头说明"以下内容不来自知识库"

### 文档管理

- 支持 txt、md、docx、doc、pdf、xlsx、html，以及 jpg、jpeg、png、webp、bmp 图片
- 上传后自动解析、分块、向量化，列表显示分块数与处理状态
- 支持删除文档、重建索引、查看失败原因

### 三层记忆

| 层 | 作用 | 是否持久化 |
|---|---|---|
| L1 上下文窗口 | 按 token 预算分配，保证检索片段不被挤出 | 会话内 |
| L2 会话记忆 | 多轮对话的上下文延续 | 落库，重启不丢 |
| L3 长期记忆 | 跨会话记住偏好与事实 | 落库，可查看、修改、删除、清空 |

### 扫描件识别

扫描版 PDF、手机拍照、聊天工具转存的图片没有文字层，普通解析无法提取内容。系统内置本地 OCR（RapidOCR + ONNX Runtime），上传后自动识别一次，结果经人工确认后入库。可选启用 GPU 加速。

### 其他

- 思考模式开关：开启后回答更充分，首字延迟约多 1 秒
- 系统状态页：当前模式与模型、生效参数、厂商 Key 配置情况、存储路径、连通性自检
- 故障提示：连不上、Key 无效、余额不足、模型名错误等情况会给出对应的处理建议

---

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Spring Boot 4.1.1、LangChain4j 1.20.2、MyBatis |
| 存储 | H2 文件库（默认）、MySQL（可选）、本地向量索引 |
| 前端 | Vue 3、Vite、Element Plus |
| 本地模型 | Ollama |
| OCR | RapidOCR、ONNX Runtime、PyMuPDF（Python 3.10+） |
| 编译目标 | Java 17 |

---

## 界面速览

| 页面 | 用途 |
|---|---|
| 对话问答 | 提问、流式回答、引用来源、把某条回答存入记忆 |
| 文档管理 | 上传、删除、重建索引、查看状态 |
| 记忆管理 | 查看、修改、删除、清空长期记忆 |
| 系统状态 | 模式与模型、思考开关、参数、厂商表、连通性自检 |

截图放在 `docs/images/`，命名 `01-chat.png`、`02-sources.png`、`03-documents.png`、`04-system.png` 后在下方展示。

---

## 快速开始

### 前置条件

按使用方式选择，最小只需要 JDK 17。

| 使用方式 | 需要安装 | 一次性下载 |
|---|---|---|
| 纯云端 | JDK 17+、云端 API Key | 约 180 MB |
| 本地离线 | JDK 17+、Ollama、两个模型 | 约 9 GB |
| 本地 + 扫描件识别 | 上一行全部 + Python 3.10+ | 再加约 35 MB |

各组件说明：

| 组件 | 要求 | 说明 |
|---|---|---|
| JDK | 17 或更高 | 必需，已在 17 和 25 上验收 |
| Ollama | 近期版本 | 仅本地模式 |
| Python | 3.10 或更高 | 仅扫描件识别功能 |
| 显卡 | 独显 8 GB 显存以上 | 仅本地模式；显存不足可走云端 |
| API Key | 见 `.env.example` | 仅云端模式 |

不需要 MySQL、Node、Maven——发布包已包含构建好的 jar 与前端资源。

### 三步启动

```bash
# 1) 拉取模型（一次性，约 8.5 GB）
ollama pull qwen3.5:9b
ollama pull qwen3-embedding:4b

# 2) 生成项目用的向量模型（复用上一步的权重，不额外下载）
ollama create qa-embed:4b -f deploy/ollama/Modelfile.qa-embed

# 3) 启动
java -jar qa-assistant-1.0.0-SNAPSHOT.jar
```

浏览器打开 <http://localhost:8080>。

第 2 步生成的是压缩上下文后的向量模型。直接用原始模型会占用约 10 GB 显存，与对话模型争抢，导致每次提问都重新加载模型。

### 一键脚本

| 平台 | 命令 | 脚本会检查 |
|---|---|---|
| Windows | 双击 `start.cmd`，或 `start.cmd 8081` 换端口 | JDK 版本、程序包是否含前端、Ollama 是否运行、`.env` 是否存在 |
| Linux / macOS | `./start.sh`，或 `./start.sh 8081` | 同上 |

脚本不会自动下载模型，只把需要执行的命令打印出来。

### 数据目录

默认使用内置 H2 文件库，数据存放在：

| 平台 | 路径 |
|---|---|
| Windows | `%USERPROFILE%\.qa-assistant\` |
| Linux / macOS | `~/.qa-assistant/` |

要换到其他磁盘，设置环境变量 `QA_DATA_DIR`，修改后需重启。

---

## 配置说明

配置分三处：`.env`（密钥与运行模式）、`application.yml`（业务参数）、`config/application.yml`（本机覆盖，可选）。

### .env

复制 `.env.example` 为 `.env` 后填写。该文件已被 `.gitignore` 排除。

```bash
set QA_PROFILES=h2,local
set QA_MODE=cloud
set DEEPSEEK_API_KEY=sk-xxx
set DASHSCOPE_API_KEY=sk-xxx
```

| 变量 | 作用 | 默认 |
|---|---|---|
| `QA_PROFILES` | 数据源组合：`h2,local`、`mysql,local`、`h2,cloud` | `h2,local` |
| `QA_MODE` | 模型来源：`local` 走 Ollama，`cloud` 走 API | `local` |
| `QA_DATA_DIR` | 数据目录 | `~/.qa-assistant` |
| `SERVER_PORT` | 监听端口 | `8080` |
| `DEEPSEEK_API_KEY` | 云端对话 Key | — |
| `DASHSCOPE_API_KEY` | 云端向量 Key | — |

`QA_PROFILES` 只决定数据源，模型来源由 `QA_MODE` 决定，界面上切换优先级更高（重启后回到 `QA_MODE` 的值）。

`/api/v1/system/status` 返回的 Key 只显示末四位，用于核对当前生效的是哪一把。

### 常用业务参数

完整项见 `src/main/resources/application.yml`，每项都有注释。

| 配置项 | 默认 | 说明 |
|---|---|---|
| `qa.retrieval.top-k` | 10 | 每次检索返回的片段数 |
| `qa.retrieval.min-score` | 0.65 | 片段的最低相似度 |
| `qa.retrieval.literal-search` | 开 | 章节号、引号原文的字面匹配通道 |
| `qa.local.num-ctx` | auto | 本地模型上下文长度，按时显存自动选 4096 / 8192 / 16384 |
| `qa.ocr.enabled` | true | 是否启用扫描件识别 |
| `qa.ocr.use-gpu` | auto | `auto` 能用则用，`on` 强制要求，`off` 关闭 |
| `qa.ocr.max-side-len` | 1280 | 送入检测网络的位图长边上限 |
| `qa.ocr.max-pages` | 100 | 单份文件的页数上限 |
| `qa.ocr.min-confidence` | 0.5 | 识别结果的最低置信度 |

### 本机覆盖配置

同一台机器装有多套 Python 时，可以用覆盖配置指定 OCR 使用哪一个解释器。在启动 jar 的目录下建 `config/application.yml`：

```yaml
qa:
  ocr:
    python: /home/user/qa-ocr/bin/python3
```

Windows 示例与字段说明见 `config/application.yml.example`。该文件含本机绝对路径，已被 `.gitignore` 排除。

---

## 文档入库

上传后系统按以下顺序处理：解析文本 → 按语义分块 → 向量化 → 写入本地索引。列表里能看到每份文档的分块数和状态。

带文字层的文档直接解析入库。扫描件和图片解析不出文字，状态显示"失败"，原因一列会说明可用「识别文字」处理——这与是否安装 OCR 依赖无关，装或不装都是这个结果。

文档格式支持情况：

| 类型 | 扩展名 | 说明 |
|---|---|---|
| 纯文本 | txt、md、html | 直接解析 |
| Word | docx | 推荐格式 |
| Word 97-2003 | doc | 支持，复杂排版建议先转 docx |
| PDF | pdf | 有文字层直接解析，扫描件走 OCR |
| 表格 | xlsx | 按工作表解析 |
| 图片 | jpg、jpeg、png、webp、bmp | 走 OCR |

---

## 扫描件与图片识别

这部分依赖本机 Python 环境，是可选功能。不安装时问答、上传、检索都不受影响。

### 安装

有 NVIDIA 独立显卡时用 `enable-gpu.cmd`（Linux/WSL 用 `enable-gpu.sh`），没有显卡用 `enable-ocr.cmd`。两个脚本都会把依赖一次装齐，不需要先执行 `requirements.txt`。

手工安装：

```cmd
cd /d <程序目录>
python -m pip install -r deploy\ocr\requirements.txt
```

装完自检：

```cmd
python -c "import rapidocr, onnxruntime, pymupdf; print('OCR 就绪')"
```

三个包缺一不可。`onnxruntime` 需要单独安装——它不在 `rapidocr` 的依赖清单里。

### 使用

1. 上传扫描件或图片，状态显示"失败"，原因列提示可用「识别文字」
2. 点该行的「识别文字」，识别完成后状态变为「待确认」
3. 弹窗显示页数、有字页数、插图统计、平均置信度和文本预览
4. 核对后点「确认入库」；点「放弃」则文档不受影响

识别可以在上传后自动触发（`qa.ocr.auto-recognize`），也可以手动点按钮。

识别分两路：整页渲染识别页面上的全部文字，另外单独抽取内嵌图片放大识别。第二路用于补充整页渲染漏掉的小字，重复内容会自动去重。

识别结果入库时会在来源里标注"正文来自本机 OCR 识别"，与原件文字层区分开。

### GPU 加速

不启用也能用，只是速度慢一些。同一份 38 页扫描件的实测对比：

| 方式 | 总耗时 | 每页 |
|---|---|---|
| CPU | 65.3 s | 1.72 s |
| GPU（RTX 5070） | 20.2 s | 0.53 s |

启用方式：

| 平台 | 命令 |
|---|---|
| Windows | `enable-gpu.cmd`，PowerShell 中用 `.\enable-gpu.cmd` |
| Linux / WSL | `bash enable-gpu.sh` |

脚本依次执行：定位 Python → 读取显卡与驱动 → 检查门槛 → 安装 OCR 基线与 CUDA 13 运行库（约 1.5 GB）→ 实际跑一次推理验证。

硬件门槛：

| 检查项 | 要求 |
|---|---|
| 显卡 | NVIDIA 独显，算力 7.5 及以上（Turing 及之后：GTX 16 系、RTX 20 系起） |
| 驱动 | 580 及以上（CUDA 13 要求） |

从 Turing 之前的显卡不能使用，这类卡已停止驱动侧的 CUDA 支持。

参数：

| 参数 | 作用 |
|---|---|
| `--check` | 只打印检测结果，不安装 |
| `--verify` | 只验证当前是否可用 |
| `--cpu` | 跳过 GPU，只装 CPU 版基线 |
| `--help` | 用法说明 |

需要代理时设置环境变量 `HTTPS_PROXY`。

验证是否生效：

```cmd
enable-gpu.cmd --verify
```

也可以重启应用后上传一份扫描件，在日志 `%USERPROFILE%\.qa-assistant\logs\qa-assistant.log` 中查看是否出现「启用 GPU」。

---

## 模型与厂商

本地模式使用 Ollama，云端模式内置 10 家预设。切换厂商只需修改 `qa.cloud.chat.provider` 或 `qa.cloud.embedding.provider`，再把对应 Key 填入 `.env`。

| 厂商 | provider | 对话 | 向量 | 说明 |
|---|---|---|---|---|
| DeepSeek | `deepseek` | 支持 | 不支持 | 无向量接口；当前模型为 `deepseek-flash` |
| 阿里云百炼 | `dashscope` | 支持 | 支持 1024 维 | 默认向量源；Key 与地域绑定，跨地域返回 401 |
| 硅基流动 | `siliconflow` | 支持 | 支持 2560 维 | 需显式配置 dimension |
| 智谱 GLM | `zhipu` | 支持 | 支持 2048 维 | base-url 为 `/api/paas/v4` |
| 月之暗面 Kimi | `kimi` | 支持 | 不支持 | 无向量接口 |
| 火山方舟 | `ark` | 支持 | 不支持 | 向量接口不走 OpenAI 协议 |
| 腾讯混元 | `hunyuan` | 支持 | 支持 1024 维 | 不传 dimensions 参数 |
| MiniMax | `minimax` | 支持 | 不支持 | 部分参数被忽略 |
| 阶跃星辰 | `stepfun` | 支持 | 不支持 | 无向量接口 |
| 百度千帆 v2 | `qianfan` | 支持 | 支持，维度固定 | 鉴权用 Bearer |

其中五家不能作为向量源（DeepSeek、Kimi、方舟、MiniMax、阶跃），它们的向量接口不存在或不兼容 OpenAI 协议，界面上会标注。

各家可用模型名与注意事项在「系统状态 → 内置厂商」中查看。

---

## 从源码构建

```bash
git clone <repo-url>
cd qa-assistant

# 前端（仓库已含构建产物，仅在修改前端后需要执行）
cd web && pnpm install && pnpm build && cd ..

# 后端（含 108 项单元测试）
mvn clean package

# 运行
java -jar target/qa-assistant-1.0.0-SNAPSHOT.jar
```

前端构建产物输出到 `src/main/resources/static/`，因此需要先构建前端再打包。顺序颠倒会打出旧界面。`start.cmd` 与 `start.sh` 会在启动前检查 jar 内是否包含前端资源。

前端开发模式：

```bash
# 终端 1
mvn spring-boot:run

# 终端 2，访问 http://localhost:5173，/api 代理到 8080
cd web && pnpm dev
```

生成发布包：

```bash
package-release.cmd     # Windows
./package-release.sh    # Linux / macOS
```

产物为 `release/qa-assistant-1.0.0.zip` 与同名 `.sha256`。

---

## 常见问题

| 现象 | 处理 |
|---|---|
| 启动报 `UnsupportedClassVersionError` | JDK 版本低于 17，`java -version` 确认 |
| 页面空白、控制台 404 | jar 里缺前端资源，重新 `cd web && pnpm build` 后 `mvn clean package` |
| 每个问题先"思考"十几秒 | 对话模型误用了推理模型，改用 `qwen3.5:9b` 这类对话模型 |
| 每次提问都要等 7~10 秒 | 两个模型在显存里互相挤，确认已执行快速开始第 2 步，并用 `ollama ps` 检查 |
| 闲置后第一个问题变慢 | Ollama 默认闲置 5 分钟卸载模型，可设置 `OLLAMA_KEEP_ALIVE=30m` |
| 提问长时间无响应或显存溢出 | 显存不足，把 `qa.local.num-ctx` 降到 8192，或改用云端模式 |
| 资料里有内容却回答"未找到" | 检查思考模式是否关闭，以及 `qa.retrieval.min-score` 是否过高 |
| 提示"API Key 被拒绝（401）" | Key 错误或与地域不匹配，改 `.env` 后重启应用 |
| 提示"余额不足（402）" | 充值或切回本地模型 |
| 能聊天，但上传文件就"解析失败" | 向量侧的 Key 未配置或不可达，查看失败原因列 |
| 失败原因含 `SSLHandshakeException` | TLS 握手被中间网络中断，换向量厂商或改用本地模型 |
| 扫描件检索不到 | 状态为「待确认」时点「查看识别结果」核对后确认入库；状态为「失败」时看原因列 |
| 删掉记忆后回答风格没变 | 删除影响跨会话记忆，当前会话的对话历史仍在；开新会话即可 |
| 小文档检索不到 | 相似度分数与知识库规模相关，短文档库可把 `qa.retrieval.min-score` 降到 0.60 |
| 重启后知识库为空 | 检查数据目录下 `index/` 是否存在且可写 |
| 改了 `.env` 不生效 | 配置仅在启动时读取，需重启应用 |
| `start.cmd` 双击报"不是内部或外部命令" | 该文件被存成了 UTF-8，需保持 GBK 编码 |

---

## 已知限制

这些是当前实现的边界，使用前值得了解：

- **手写体、印章、竖排、多栏排版**的识别效果很弱。真实拍照件的准确率低于印刷体，数字与编号建议人工核对
- **长截图识别效果差**。位图按长边压缩到 1280 像素，长截图（如高宽比大于 3）压缩后宽度过小，文字会糊掉。建议切成多段分别上传
- **中等相关度的提问可能混入模型自身知识**。检索片段"话题相关但不含具体事实"时，模型可能用训练知识补全。资料中有答案的问题和资料完全没涉及的话题表现正常，边界情况建议点开「引用来源」核对
- **小文档检索分数偏低**。只有一两块内容的知识库，相似度分数整体低于大规模库，必要时调整 `qa.retrieval.min-score`
- **向量模型切换需要重建索引**。对话模型可即时切换，向量模型不同维度之间不兼容

---

## 目录结构

```
qa-assistant/
├── src/main/java/com/itjiang/qa/     后端源码
│   ├── rag/                          检索与生成
│   ├── ocr/                          OCR 引擎调用
│   ├── memory/                       三层记忆
│   ├── provider/                     模型厂商适配
│   └── service/                      业务逻辑
├── src/main/resources/               配置与静态资源
├── web/                              前端源码
├── deploy/ocr/                       OCR 脚本与依赖清单
├── deploy/ollama/                    向量模型 Modelfile
├── docs/                             设计说明与验收清单
├── enable-gpu.cmd / .sh              GPU 环境安装
├── enable-ocr.cmd / .sh              CPU OCR 环境安装
├── start.cmd / start.sh              启动脚本
└── package-release.cmd / .sh         发布包构建
```

---

## 贡献与约定

- `start.cmd`、`package-release.cmd`、`enable-ocr.cmd` 等 `.cmd` 文件必须保持 **GBK 编码**，`enable-gpu.cmd` 保持 **纯 ASCII**。cmd.exe 按系统代码页解析，编码错误会导致乱码与报错
- `.sh` 文件使用 **LF 换行**（`.gitattributes` 已声明）
- 提交前确认没有把 `.env` 或真实 Key 带入仓库
- 注释用中文，标识符用英文；改动行为时同步更新 `docs/DESIGN.md`

## 文档与协议

- [`docs/DESIGN.md`](docs/DESIGN.md)：架构、数据模型、接口、记忆体系
- [`docs/ACCEPTANCE.md`](docs/ACCEPTANCE.md)：验收清单
- 开源协议：[Apache-2.0](LICENSE)
- 模型权重不在本仓库内，需自行 `ollama pull`
