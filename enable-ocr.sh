#!/usr/bin/env bash
# ============================================================================
#  enable-ocr.sh —— 装「扫描版 PDF 识别文字」的 CPU 版依赖
# ============================================================================
#  bash enable-ocr.sh            安装
#  bash enable-ocr.sh --check    只打印将要做的事，不安装
#
#  装三个包（约 50 MB）：rapidocr 认字、onnxruntime 推理后端（CPU 版）、
#  pymupdf 把 PDF 逐页渲染成位图。模型随 pip 包下发，装完即离线可用。
#  想要 GPU 加速用 enable-gpu.sh。
#
#  本脚本复用 enable-gpu.sh 的 find_python / install_baseline，避免两份实现漂移。
# ============================================================================

set -u

HERE=$(cd "$(dirname "$0")" && pwd)

if [ ! -f "$HERE/enable-gpu.sh" ]; then
  echo "[X] 找不到 enable-gpu.sh（本脚本复用它的检测与安装逻辑）。"
  echo "    请确认两个脚本在同一个目录下。"
  exit 1
fi

# shellcheck source=enable-gpu.sh
. "$HERE/enable-gpu.sh"

# 只支持 --check / --help
for arg in "$@"; do
  case "$arg" in
    --check) PRE=1 ;;
    -h|--help)
      sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "[i] 忽略无法识别的参数：$arg（本脚本只支持 --check / --help）"
      ;;
  esac
done

echo "============================================================"
echo "  qa-assistant  ——  启用扫描件识别（CPU 版）"
echo "============================================================"
echo

# ---- 1. 找 Python -----------------------------------------------------------
if ! find_python; then
  echo "[X] 没找到可用的 Python。OCR 功能本身就需要 Python 3.10+，请先安装。"
  echo "    可选功能：不装它，问答、上传、检索全都不受影响。"
  exit 1
fi
if python_too_old; then
  echo "[X] Python $PYVER 太旧，OCR 依赖需要 3.10 或更新。"
  exit 1
fi
echo "[1/3] Python：$PYVER（$PYARCH），目标解释器：$PY"

# ---- 2. 安装三个包 ----------------------------------------------------------
echo "[2/3] 安装 OCR 依赖..."

# 已装 onnxruntime-gpu 时不提 onnxruntime 这个名字：两者文件同目录、pip 记成
# 两条独立分发，装 CPU 版会覆盖 GPU 版并静默关掉 GPU 加速。
HAS_GPU=""
if "$PY" -m pip list 2>/dev/null | grep -qi '^onnxruntime-gpu'; then
  HAS_GPU=1
  echo "      [i] 本环境已装 onnxruntime-gpu。"
  echo "          本脚本**不会**安装 CPU 版 —— 那会覆盖 GPU 版（同一批文件、"
  echo "          两条 pip 记录）并静默关掉 GPU 加速。"
  echo "          只补装缺失的 rapidocr / pymupdf。"
fi

if [ -n "$PRE" ]; then
  echo "      --check：跳过基线安装。"
else
  if [ -n "$HAS_GPU" ]; then
    echo "      约 50 MB；模型随 pip 包下发，装完即离线可用。"
    echo "      命令：$PY -m pip install rapidocr pymupdf"
    "$PY" -m pip install --upgrade --disable-pip-version-check rapidocr pymupdf || {
      echo
      echo "[X] 安装失败。请检查网络后重试（什么都没被弄坏）。"
      exit 1
    }
  else
    echo "      约 50 MB；模型随 pip 包下发，装完即离线可用。"
    echo "      命令：$PY -m pip install rapidocr onnxruntime pymupdf"
    "$PY" -m pip install --upgrade --disable-pip-version-check rapidocr onnxruntime pymupdf || {
      echo
      echo "[X] 安装失败。请检查网络后重试（什么都没被弄坏）。"
      exit 1
    }
  fi
fi
echo

# ---- 3. 自检：三个包都要能 import -------------------------------------------
echo "[3/3] 自检..."
if [ -n "$PRE" ]; then
  echo "      --check：跳过自检。"
else
  "$PY" - <<'PYEOF'
import sys
missing = []
for name in ("rapidocr", "onnxruntime", "pymupdf"):
    try:
        __import__(name)
    except Exception:
        missing.append(name)
if missing:
    print("MISSING " + "|".join(missing))
    sys.exit(1)
import onnxruntime as o
print("PROVIDERS " + "|".join(o.get_available_providers()))
PYEOF
  RC=$?
  echo
  echo "============================================================"
  if [ $RC -eq 0 ]; then
    echo "  [OK] 扫描件识别已可用（CPU 模式）。"
    echo
    echo "  重启应用后上传一份扫描版 PDF，状态会变成「待确认」；"
    echo "  点「查看识别结果」核对后确认入库即可。"
    echo "  实测速度（38 页扫描件）：约 47 秒。"
  else
    echo "  [X] 自检未通过：仍有包无法 import（见上面的 MISSING 一行）。"
    echo "      重新执行本脚本，或手动安装："
    echo "        $PY -m pip install rapidocr onnxruntime pymupdf"
  fi
  echo "============================================================"
fi
exit 0
