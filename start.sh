#!/usr/bin/env bash
# ============================================================================
#  qa-assistant 一键启动（Linux / macOS）
#    ./start.sh            # 默认 8080
#    ./start.sh 8081       # 指定端口
#  只做体检 + 启动；不自动拉模型（ollama pull 是 8.5 GB 下载）。
# ============================================================================
set -uo pipefail

cd "$(dirname "$0")" || exit 1

PORT=8080
case "${1:-}" in
  --port) PORT="${2:-8080}" ;;
  "")     ;;
  *)      PORT="$1" ;;
esac

echo
echo "============================================================"
echo "  qa-assistant 智能问答助手 - 启动前检查"
echo "============================================================"
echo

# ---------- 1) JDK ----------
if ! command -v java >/dev/null 2>&1; then
  echo "[X] 没有找到 java 命令。"
  echo "    本程序需要 JDK 17 或更高版本：https://adoptium.net/temurin/releases/?version=17"
  exit 1
fi

# java -version 把版本打到 stderr，形如 openjdk version "17.0.9" 或 "25"
JAVA_VER="$(java -version 2>&1 | head -n 1 | sed -n 's/.*version "\([^"]*\)".*/\1/p')"
JAVA_MAJOR="$(printf '%s' "$JAVA_VER" | cut -d. -f1)"

if [ -z "$JAVA_MAJOR" ]; then
  echo "[!] 无法解析 Java 版本（原始输出：$JAVA_VER），跳过版本检查。"
elif [ "$JAVA_MAJOR" -lt 17 ] 2>/dev/null; then
  echo "[X] 检测到 Java $JAVA_MAJOR，但本程序需要 JDK 17 或更高。"
  echo "    请安装 JDK 17+：https://adoptium.net/temurin/releases/?version=17"
  exit 1
else
  echo "[OK] Java $JAVA_MAJOR（满足 JDK 17+）"
fi

# ---------- 2) 找到可执行的 jar ----------
# 兼容两种布局：开发仓库（jar 在 target/）与发布包（jar 就在本目录）
JAR="$(ls -1 target/qa-assistant-*.jar 2>/dev/null | head -n 1)"
if [ -z "$JAR" ]; then
  JAR="$(ls -1 qa-assistant-*.jar 2>/dev/null | head -n 1)"
fi
if [ -z "$JAR" ]; then
  echo "[X] 没有找到 qa-assistant-*.jar。"
  echo "    下载的发布包：说明包不完整，请重新下载并核对 SHA256。"
  echo "    从源码运行：请先构建 mvn clean package"
  echo "    （改了前端的话要先 cd web && pnpm build，再 mvn clean package）"
  exit 1
fi
echo "[OK] 找到程序包: $JAR"

# 校验 jar 里是否带前端产物 —— 缺了会打开一个空白页，这比启动失败更难排查
if command -v unzip >/dev/null 2>&1; then
  if ! unzip -l "$JAR" 2>/dev/null | grep -q 'BOOT-INF/classes/static/index.html'; then
    echo "[!] 警告：程序包里没有前端页面（static/index.html）。"
    echo "    启动后打开网页会是一片空白。请先在 web 目录构建前端再重新打包。"
  fi
else
  # 没有 unzip 就退化为"用 jar 自身的 list 能力"，jar 命令随 JDK 一起提供
  if ! jar tf "$JAR" 2>/dev/null | grep -q 'BOOT-INF/classes/static/index.html'; then
    echo "[!] 警告：程序包里可能没有前端页面（static/index.html）。"
  fi
fi

# ---------- 3) Ollama 体检（本地模式用；没有也不阻塞，可能是走云端）----------
OLLAMA_OK=0
if command -v curl >/dev/null 2>&1; then
  if curl -s -m 3 http://localhost:11434/api/tags >/dev/null 2>&1; then
    OLLAMA_OK=1
    echo "[OK] Ollama 可用（http://localhost:11434）"
  else
    echo "[!] 连不上 Ollama（http://localhost:11434）。"
    echo "    要用本地模型请先启动 Ollama；只用云端模式可以忽略这条。"
  fi
else
  echo "[i] 没有 curl，跳过 Ollama 体检。"
fi

# ---------- 4) .env 体检 ----------
if [ -f .env ]; then
  echo "[OK] 找到 .env"
else
  echo "[i] 没有 .env（可选）。需要配置云端 Key 时："
  echo "    cp .env.example .env 然后填入 Key，再重新运行本脚本。"
fi

# ---------- 4.5) 云端模式预检：缺 Key 时给出可操作的提示 ----------
MODE_VALUE="${QA_MODE:-}"
if [ -z "$MODE_VALUE" ] && [ -f .env ]; then
  MODE_VALUE="$(grep -E '^[[:space:]]*QA_MODE=' .env | tail -n 1 | cut -d= -f2- | tr -d '[:space:]')"
fi
if [ "$MODE_VALUE" = "cloud" ]; then
  CHAT_KEY="${DEEPSEEK_API_KEY:-}"
  EMB_KEY="${SILICONFLOW_API_KEY:-}"
  [ -z "$CHAT_KEY" ] && [ -f .env ] && CHAT_KEY="$(grep -E '^[[:space:]]*DEEPSEEK_API_KEY=' .env | tail -n 1 | cut -d= -f2- | tr -d '[:space:]')"
  [ -z "$EMB_KEY" ] && [ -f .env ] && EMB_KEY="$(grep -E '^[[:space:]]*SILICONFLOW_API_KEY=' .env | tail -n 1 | cut -d= -f2- | tr -d '[:space:]')"
  if [ -z "$CHAT_KEY" ] || [ -z "$EMB_KEY" ]; then
    echo
    echo "[X] QA_MODE=cloud，但缺少云端 Key，程序无法启动。"
    echo "    云端模式需要两个 Key：对话 DEEPSEEK_API_KEY + 向量 SILICONFLOW_API_KEY。"
    echo "    二选一："
    echo "      a) cp .env.example .env，把两个 Key 填上，再运行本脚本；"
    echo "      b) 改用本地模式：把 .env 里的 QA_MODE 设成 local（或删掉这一行，默认就是 local）。"
    exit 1
  fi
  echo "[OK] 云端模式 Key 已配置"
fi

# ---------- 5) 首次运行提示 ----------
if [ "$OLLAMA_OK" = "1" ]; then
  echo
  echo "[i] 首次使用本地模式，请确认这两个模型已拉取："
  echo "      ollama pull qwen3.5:9b"
  echo "      ollama pull qwen3-embedding:4b"
  echo "    并生成项目专用的向量模型（把上下文压到 2048，否则它会独占约 10GB 显存）："
  echo "      ollama create qa-embed:4b -f deploy/ollama/Modelfile.qa-embed"
  echo "    漏掉最后一步的表现是「每一问都要等 7~10 秒」。"
  echo
  echo "[i] 要处理扫描版 PDF（可选），还需要 Python 3.10+ 和下面两步："
  echo "      cd \"\$(dirname \"\$0\")\""
  echo "      python3 -m pip install -r deploy/ocr/requirements.txt"
  echo "    注意先在程序目录里执行（这条命令用的是相对路径），这一步需要联网。"
  echo "    装完刷新页面按钮才会亮；不装不影响问答、上传、检索。"
fi

# ---------- 4.6) 端口自检：被占用时直接说明是哪个实例 ----------
PORT_PID=""
if command -v ss >/dev/null 2>&1; then
  PORT_PID="$(ss -lntp 2>/dev/null | awk -v p=":$PORT" '$4 ~ p"$" {print $NF}' | sed -n 's/.*pid=\([0-9]*\).*/\1/p' | head -n 1)"
elif command -v lsof >/dev/null 2>&1; then
  PORT_PID="$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null | head -n 1)"
elif command -v netstat >/dev/null 2>&1; then
  # Linux netstat 的进程列形如 "1234/java"，macOS 是 "java" 或带 pid 前缀
  PORT_PID="$(netstat -lntp 2>/dev/null | awk -v p=":$PORT" '$4 ~ p"$" {print $7}' | sed -n 's#^\([0-9]*\)/.*#\1#p' | head -n 1)"
fi
if [ -n "${PORT_PID:-}" ]; then
  PORT_PROC="$(ps -p "$PORT_PID" -o comm= 2>/dev/null | head -n 1)"
  echo
  echo "[X] 端口 $PORT 已被占用（PID $PORT_PID，进程 ${PORT_PROC:-未知}），无法启动。"
  echo "    最可能的情况：已经有一个本程序在运行。"
  echo "    换一个端口启动：  ./start.sh 8081"
  exit 1
fi
echo "[OK] 端口 $PORT 可用"

echo
echo "============================================================"
echo "  正在启动，服务地址 http://localhost:$PORT"
echo "  按 Ctrl+C 停止服务"
echo "============================================================"
echo

# 稍等几秒再开浏览器：给 Spring Boot 一点启动时间，避免打开白页
if command -v open >/dev/null 2>&1; then
  ( sleep 8 && open "http://localhost:$PORT" ) >/dev/null 2>&1 &
elif command -v xdg-open >/dev/null 2>&1; then
  ( sleep 8 && xdg-open "http://localhost:$PORT" ) >/dev/null 2>&1 &
fi

java -jar "$JAR" --server.port="$PORT"
EXIT_CODE=$?

echo
if [ "$EXIT_CODE" != "0" ]; then
  echo "[X] 程序退出，退出码 $EXIT_CODE。常见原因："
  echo "    - 端口 $PORT 被占用：换一个端口运行，例如  ./start.sh 8081"
  echo "    - 缺云端 Key：见上面的 .env 提示"
  echo "    - 数据目录不可写：默认在 ~/.qa-assistant"
fi
exit "$EXIT_CODE"
