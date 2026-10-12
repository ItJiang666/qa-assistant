#!/usr/bin/env bash
# ============================================================================
#  enable-gpu.sh —— qa-assistant OCR 的 GPU 加速一键安装
# ============================================================================
#  为什么是脚本而不是预打包：
#    GPU 运行库解压后约 1.46 GB，而且**与 Python ABI 严格绑定**
#    （cp311 / cp312 / cp313 …）。预打包只能服务"Python 版本恰好相同"的人，
#    而用户机器上是什么版本我们无从得知。脚本的做法是识别环境后让 pip 去取
#    对应构建，因此用户是什么环境都能装上。
#
#  脚本做五件事：
#    1. 找到 Python 解释器
#    2. 查 NVIDIA 驱动（nvidia-smi）与显卡代次
#    3. 选 CUDA 13 源（默认）或 CUDA 12 源（旧卡）
#    4. 安装 onnxruntime-gpu + CUDA/cuDNN 运行库
#    5. **验证** CUDAExecutionProvider 是否真的出现，并给出人话结论
#
#  用法：
#    bash enable-gpu.sh            # 安装
#    bash enable-gpu.sh --check    # 只看它打算做什么，不安装
#    bash enable-gpu.sh --cpu      # 明确跳过（只做检测）
#
#  说明：本文件用 UTF-8 + LF。与 .cmd 不同，bash 对 UTF-8 中文没问题。
# ============================================================================

set -u

ORT_SPEC="onnxruntime-gpu[cudnn]"
CUDA_INDEX="https://pypi.nvidia.com"
PRE=""
TARGET=""

for arg in "$@"; do
  case "$arg" in
    --check) PRE=1 ;;
    --cpu)   TARGET=cpu ;;
    -h|--help)
      sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
  esac
done

echo "============================================================"
echo "  qa-assistant  ——  启用 OCR 的 GPU 加速"
echo "============================================================"
echo

# ---- 1. 找 Python -----------------------------------------------------------
#  ⚠️ 必须**实际执行**验证，不能只 `command -v`。
#     实测踩到：Windows 上装了 Store 版 Python 时，PATH 里会有一个
#     `WindowsApps/python3` 跳转存根 —— `command -v` 找得到它，但一执行就失败。
#     只按"找得到"就选用的话，脚本会对着一台明明装了 Python 的机器报
#     "这个 Python 跑不起来"，而用户完全不知道该删哪个。
PY=""
PYVER=""
for cand in python3 python; do
  command -v "$cand" >/dev/null 2>&1 || continue
  probe=$("$cand" -c 'import sys;print("%d.%d"%sys.version_info[:2])' 2>/dev/null | tr -d '\r')
  case "$probe" in
    [0-9]*.[0-9]*)
      PY="$cand"; PYVER="$probe"; break
      ;;
    *)
      echo "[i] 跳过不可用的 $cand（$cand 存在但执行失败，可能是 Store 跳转存根）"
      ;;
  esac
done
if [ -z "$PY" ]; then
  echo "[X] 没找到可用的 Python。OCR 功能本身就需要 Python，请先安装 3.10+。"
  echo "    （GPU 是可选的，不装也照样能用，只是慢一些。）"
  exit 1
fi

PYARCH=$("$PY" -c 'import platform;print(platform.machine())' 2>/dev/null | tr -d '\r')
PYPATH=$("$PY" -c 'import sys;print(sys.executable)' 2>/dev/null | tr -d '\r')
echo "[1/6] Python：$PYVER（$PYARCH）"
echo "      解释器：$PYPATH"

# ---------------------------------------------------------------------------
#  install_baseline —— 无论有没有 GPU 都要装的部分
#
#  onnxruntime 只是**推理后端**：真正认字的是 `rapidocr`，把 PDF 拆成位图的
#  是 `pymupdf`。只装 onnxruntime-gpu 的机器依然做不了 OCR。
#  所以基线在这里一并装上 —— 这样"只跑这一个脚本"的机器也能用。
#
#  与 GPU 包不冲突：rapidocr 依赖的是 `onnxruntime`（CPU 发行版），而
#  onnxruntime-gpu 与它**同名同目录**，pip 视为已满足，不会再拉一个 CPU 版进来。
# ---------------------------------------------------------------------------
install_baseline() {
  if [ -n "$PRE" ]; then
    echo "      --check：跳过基线安装。"
    return 0
  fi
  "$PY" -m pip install --upgrade --disable-pip-version-check rapidocr pymupdf || {
    echo
    echo "[X] 装 rapidocr / pymupdf 失败。请检查网络后重试。"
    return 1
  }
  return 0
}

#  把"装到哪个环境"摆到明面上。
#  实测踩过：在一个 powershell 会话里把 venv 的 Scripts 前置到 PATH，本想
#  隔离测试，结果 cmd 解析 python 时主环境胜出 —— 依赖装进了主环境。
#  所以这里显式打印绝对路径，并在交互环境下给一次反悔机会。
if [ "${QA_GPU_YES:-}" != "1" ] && [ -t 0 ]; then
  printf "      安装将进入上面这个环境。继续？[Y/n] "
  read -r ans
  case "$ans" in
    n|N|no|NO) echo "[i] 已取消。"; exit 0 ;;
  esac
fi

# ---- 2. 驱动与显卡 ----------------------------------------------------------
DRV=""
GPUNAME=""
if command -v nvidia-smi >/dev/null 2>&1; then
  DRV=$(nvidia-smi --query-gpu=driver_version --format=csv,noheader 2>/dev/null | head -n1 | tr -d '\r')
  GPUNAME=$(nvidia-smi --query-gpu=name --format=csv,noheader 2>/dev/null | head -n1 | tr -d '\r')
fi

if [ -z "$DRV" ]; then
  echo "[2/6] NVIDIA 驱动：未检测到"
  echo
  echo "[i] 这台机器上没有可用的 NVIDIA 显卡，跳过 GPU 运行库"
  echo "    （否则是白下约 1 GB）。改为安装 CPU 版的 OCR 基线，功能照常可用。"
  install_baseline || exit 1
  echo
  echo "[i] 完成。实测参考：38 页扫描件在 CPU 上约 47 秒（v1.28 优化后，比之前快 6.6 倍）。"
  exit 0
fi
echo "[2/6] 显卡：$GPUNAME"
echo "      驱动：$DRV"

DRVMAJOR=$(echo "$DRV" | cut -d. -f1)
case "$DRVMAJOR" in
  ''|*[!0-9]*) DRVMAJOR=0 ;;
esac

if [ "$DRVMAJOR" -ge 580 ]; then
  echo "[3/6] CUDA：13（驱动 $DRV ≥ 580）"
else
  echo "[3/6] CUDA：12（驱动 $DRV < 580，改用 CUDA 12 构建）"
  CUDA_INDEX="https://aiinfra.pkgs.visualstudio.com/PublicPackages/_packaging/onnxruntime-cuda-12/pypi/simple/"
  ORT_SPEC="onnxruntime-gpu==1.29.0"
fi

# 很老的卡：CUDA 12 的运行库也已不支持 Maxwell/Pascal，
# 装了也用不起来 —— 与其让用户白下 1 GB，不如直接说清。
case "$GPUNAME" in
  *"GTX 10"*|*"GTX 9"*|*"GTX 7"*|*"GT 10"*|*"GT 9"*|*"GT 7"*|*"Quadro K"*|*"Quadro P"*|*"Quadro M"*)
    echo
    echo "[i] 这个代次的显卡（GTX 10 系及更早）已不被 onnxruntime-gpu 依赖的"
    echo "    CUDA 运行库支持：装得进去，但 CUDAExecutionProvider 起不来。"
    echo "    所以跳过它，改为安装 CPU 版 OCR 基线。"
    install_baseline || exit 1
    exit 0
    ;;
esac

if [ "$TARGET" = "cpu" ]; then
  echo "[i] 指定了 --cpu：跳过 GPU 运行库，只装 CPU 版 OCR 基线。"
  install_baseline || exit 1
  exit 0
fi

# ---- 4. OCR 基线（无论有没有 GPU 都必须装）----------------------------------
echo "[4/6] 安装 OCR 基线（rapidocr、pymupdf）..."
install_baseline || exit 1
echo

# ---- 5. GPU 运行库 ----------------------------------------------------------
echo "[5/6] 准备安装：$ORT_SPEC"
echo "      索引源：  $CUDA_INDEX"
echo
if [ -n "$PRE" ]; then
  echo "[i] --check：只做检测，不安装。去掉 --check 即真正执行。"
  exit 0
fi

echo "      需要下载约 1.07 GB，请保持网络畅通（有加速器会快很多）。"
echo
"$PY" -m pip install --upgrade --disable-pip-version-check \
  --extra-index-url "$CUDA_INDEX" "$ORT_SPEC"
if [ $? -ne 0 ]; then
  echo
  echo "[X] 安装失败。常见原因：网络受限、需要代理、或 NVIDIA 源不可达。"
  echo "    什么都没被弄坏：应用会自动回落到 CPU，照常可用。"
  exit 1
fi
echo

# ---- 5. 验证 ----------------------------------------------------------------
echo "[6/6] 验证中……"
"$PY" - <<'PYEOF'
import sys
try:
    import onnxruntime as o
except Exception as e:
    print("IMPORTFAIL", e)
    sys.exit(2)
provs = o.get_available_providers()
print("VERSION", o.__version__)
print("HASCUDA", "YES" if "CUDAExecutionProvider" in provs else "NO")
print("PROVIDERS", "|".join(provs))
PYEOF
RC=$?

echo
echo "============================================================"
if [ $RC -eq 0 ] && "$PY" -c "import onnxruntime as o,sys;sys.exit(0 if 'CUDAExecutionProvider' in o.get_available_providers() else 1)" 2>/dev/null; then
  echo "  [OK] GPU 加速已启用。"
  echo
  echo "  重启应用后，随便识别一份扫描件，到日志里搜这一行确认："
  echo "      OCR 使用 GPU 加速"
  echo "  预期效果（38 页扫描件实测）：约 47 秒 → 约 17 秒。"
else
  echo "  [!] CUDAExecutionProvider 没有出现。"
  echo
  echo "  应用会自动回落到 CPU —— 什么都没坏，只是没有加速。常见原因："
  echo "    - 驱动版本太旧，带不动装上的 CUDA 运行库"
  echo "    - 显卡代次不受支持"
  echo "  建议先升级 NVIDIA 驱动，再跑一次本脚本。"
fi
echo "============================================================"
exit 0
