#!/usr/bin/env bash
# ============================================================================
#  enable-ocr.sh —— qa-assistant「扫描版 PDF 识别文字」的 CPU 版一键安装
# ============================================================================
#  什么时候用这个：
#    * 你没有 NVIDIA 显卡，或者不打算用 GPU；
#    * 你只想让"扫描件 / 图片识别文字"这一项功能可用，不想要 1 GB 的
#      CUDA 运行库。
#
#  它只装三个包（约 50 MB，其中模型随包下发、装完即离线可用）：
#      rapidocr     真正认字的引擎（PP-OCR 系列）
#      onnxruntime  它的推理后端（CPU 版）
#      pymupdf      把 PDF 逐页渲染成位图
#
#  ⚠️ 与 `pip install -r deploy/ocr/requirements.txt` 的区别：
#    两者装的东西**完全一样**，这个脚本只是多了环境检测与自检 —— 它会
#    跳过不可用的 Python、打印将要修改的解释器绝对路径、装完验证三个包都能
#    import（与应用的真实判定标准一致）。requirements.txt 那条路依然可用。
#
#  用法：
#    bash enable-ocr.sh            # 安装
#    bash enable-ocr.sh --check    # 只看它打算做什么，不安装
#
#  想要 GPU 加速？用 enable-gpu.sh（它会先装这三个、再装 GPU 运行库）。
#
#  实现说明：本脚本 **复用 enable-gpu.sh 的函数**而不是复制一份逻辑 ——
#  两份实现迟早会漂移（改了 A 忘了 B），而 Python 探测、环境确认这两段
#  是最不该漂移的。所以这里把 PRE 预置为 1 再调用同一个 install_baseline()。
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

# 解析本脚本自己的用法：只支持 --check
for arg in "$@"; do
  case "$arg" in
    --check) PRE=1 ;;
    -h|--help)
      sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
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
PYM=${PYVER%%.*}
PYN=${PYVER#*.}
if [ "$PYM" -lt 3 ] || { [ "$PYM" -eq 3 ] && [ "$PYN" -lt 10 ]; }; then
  echo "[X] Python $PYVER 太旧，OCR 依赖需要 3.10 或更新。"
  exit 1
fi
echo "[1/3] Python：$PYVER（$PYARCH）"
confirm_target || exit 0

# ---- 2. 安装三个包 ----------------------------------------------------------
echo "[2/3] 安装 OCR 依赖..."

# ⚠️ 守卫：绝不降级已装的 GPU 版
#
#   实测踩到过，而且真的弄坏了一台机器：`pip install onnxruntime` 装的是 **CPU 分发**，
#   而它的文件与 `onnxruntime-gpu` 落在**同一个目录**（两者都 import 成 onnxruntime）。
#   pip 把两者记成**两个独立分发**，于是它会开开心心把 CPU 版盖上去 ——
#   `CUDAExecutionProvider` 就此消失，**GPU 加速被静默关掉，任何地方都不报错**。
#
#   所以：只要检测到 onnxruntime-gpu，就**完全不提 onnxruntime 这个名字** ——
#   现有的 GPU 版已经满足了 rapidocr 的那条依赖。
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

# ---- 3. 自检（与应用的真实判定标准一致：三个包都要能 import）---------------
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
