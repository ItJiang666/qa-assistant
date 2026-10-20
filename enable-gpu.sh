#!/usr/bin/env bash
# ============================================================================
#  enable-gpu.sh —— 给 OCR 安装 GPU 运行库（Linux / WSL）
# ============================================================================
#  bash enable-gpu.sh             安装并验证
#  bash enable-gpu.sh --check     只打印检测结果，不安装
#  bash enable-gpu.sh --verify    只验证当前是否可用
#  bash enable-gpu.sh --cpu       跳过 GPU，只装 CPU 版基线
#
#  流程：找 Python -> 打印显卡与驱动 -> 检查门槛 -> 安装 -> 真实前向验证
#  门槛不满足直接报错退出；不降档、不测速。
#
#  本文件的 find_python / confirm_target / install_baseline 会被 enable-ocr.sh
#  source 复用，改动时保持接口不变。
# ============================================================================

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PY=""
PYVER=""
PYARCH=""
PRE=""
VERIFY_ONLY=""
TARGET="gpu"
STEP_RETRIES=3

PYPI_INDEX="https://pypi.org/simple"
NVIDIA_INDEX="https://pypi.nvidia.com"

# CUDA 13 的最低要求
MIN_DRIVER=580
MIN_CAP="7.5"

GPU_SPEC="onnxruntime-gpu==1.31.0"
GPU_DEPS="nvidia-cudnn-cu13~=9.0 nvidia-cublas nvidia-cuda-nvrtc~=13.0 nvidia-cufft~=12.0 nvidia-curand~=10.0 nvidia-cuda-runtime~=13.0 nvidia-nvjitlink"
BASELINE_PKGS="rapidocr pymupdf"

# ---------------------------------------------------------------------------
#  pip 辅助
# ---------------------------------------------------------------------------

# --resume-retries 只有 pip 26+ 才有，先探测再用
pip_supports_resume() {
  "$PY" -m pip install --help 2>/dev/null | grep -q -- '--resume-retries'
}

pip_net_args() {
  echo "--retries 10 --timeout 60"
  pip_supports_resume && echo "--resume-retries 5"
  return 0
}

# pip_retry <说明> <pip 参数...>：失败后按 5/10/20 秒退避重试
pip_retry() {
  local label="$1"; shift
  local attempt=1 wait=5
  while :; do
    # shellcheck disable=SC2086
    if "$@" ; then
      return 0
    fi
    if [ "$attempt" -ge "$STEP_RETRIES" ]; then
      echo "[X] $label 失败（已重试 $STEP_RETRIES 次）"
      return 1
    fi
    echo "[!] $label 第 $attempt 次失败，${wait} 秒后重试（已下载部分会复用）"
    sleep "$wait"
    attempt=$((attempt + 1))
    wait=$((wait * 2))
  done
}

# ---------------------------------------------------------------------------
#  Python 检测
# ---------------------------------------------------------------------------

# 逐个候选实际执行验证，跳过 Microsoft Store 的假 python3
find_python() {
  local cand probe
  for cand in python3 python; do
    command -v "$cand" >/dev/null 2>&1 || continue
    probe=$("$cand" -c 'import sys;print("%d.%d.%d" % sys.version_info[:3])' 2>/dev/null) || continue
    case "$probe" in [0-9]*.[0-9]*.[0-9]*) ;; *) continue ;; esac
    PY="$cand"
    PYVER="$probe"
    PYARCH=$("$cand" -c 'import platform;print(platform.machine())' 2>/dev/null || echo "?")
    return 0
  done
  return 1
}

python_too_old() {
  local major minor
  major=$(echo "$PYVER" | cut -d. -f1)
  minor=$(echo "$PYVER" | cut -d. -f2)
  [ "$major" -lt 3 ] && return 0
  [ "$major" -eq 3 ] && [ "$minor" -lt 10 ] && return 0
  return 1
}

# 打印将要修改的解释器并要求确认
confirm_target() {
  local answer
  echo "      解释器：$PY"
  printf "      确认装进这个环境？[Y/n] "
  read -r answer < /dev/tty 2>/dev/null || answer="y"
  case "$answer" in
    ""|y|Y|yes|YES) return 0 ;;
    *) echo "      已取消。"; return 1 ;;
  esac
}

# ---------------------------------------------------------------------------
#  安装
# ---------------------------------------------------------------------------

install_baseline() {
  if [ -n "$PRE" ]; then
    echo "      --check：跳过基线安装。"
    return 0
  fi
  # shellcheck disable=SC2086
  pip_retry "安装 OCR 基线" "$PY" -m pip install --upgrade \
    --disable-pip-version-check --index-url "$PYPI_INDEX" $(pip_net_args) $BASELINE_PKGS || {
    echo "[X] 装 rapidocr / pymupdf 失败，检查网络后重试。"
    return 1
  }
  return 0
}

# --force-reinstall 必须加：onnxruntime-gpu 各版本共用同一目录，
# 不加时 pip 认为已满足而跳过，版本号不会变。
install_gpu() {
  # shellcheck disable=SC2086
  pip_retry "安装 GPU 运行库" "$PY" -m pip install --upgrade --force-reinstall \
    --disable-pip-version-check \
    --index-url "$PYPI_INDEX" --extra-index-url "$NVIDIA_INDEX" \
    $(pip_net_args) "$GPU_SPEC" $GPU_DEPS || return 1
  return 0
}

# ---------------------------------------------------------------------------
#  显卡检测
# ---------------------------------------------------------------------------

nvidia_info() {
  GPU_NAME=$(nvidia-smi --query-gpu=name --format=csv,noheader 2>/dev/null | head -n1 | tr -d '\r')
  DRV=$(nvidia-smi --query-gpu=driver_version --format=csv,noheader 2>/dev/null | head -n1 | tr -d '\r')
  CAP=$(nvidia-smi --query-gpu=compute_cap --format=csv,noheader 2>/dev/null | head -n1 | tr -d '\r')
  return 0
}

# 算力取不到时放行，交给第 5 步的真实前向验证裁决
cap_at_least() {
  local got="$1" need="$2"
  case "$got" in ''|*[!0-9.]*) return 0 ;; esac
  awk -v a="$got" -v b="$need" 'BEGIN{exit !(a+0 >= b+0)}'
}

check_thresholds() {
  if [ -z "${GPU_NAME:-}" ]; then
    echo "[X] 未检测到 NVIDIA 显卡，无法启用 GPU 加速。"
    echo "    没有 nvidia-smi，或驱动未安装。装好驱动后重跑。"
    return 1
  fi
  if [ -n "${CAP:-}" ] && ! cap_at_least "$CAP" "$MIN_CAP"; then
    echo "[X] 显卡型号过低：$GPU_NAME（算力 $CAP，需要 $MIN_CAP 及以上）。"
    echo "    CUDA 13 要求 Turing 及以后（GTX 16 系 / RTX 20 系起）。"
    return 1
  fi
  local major
  major=$(echo "${DRV:-0}" | cut -d. -f1)
  case "$major" in ''|*[!0-9]*) major=0 ;; esac
  if [ "$major" -lt "$MIN_DRIVER" ]; then
    echo "[X] 驱动版本 $DRV 不满足要求（需要 >= $MIN_DRIVER）。请升级驱动后重跑。"
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------------------
#  验证
# ---------------------------------------------------------------------------

verify_gpu() {
  local probe="$SCRIPT_DIR/deploy/ocr/qa_gpu_probe.py"
  [ -f "$probe" ] || probe="$SCRIPT_DIR/qa_gpu_probe.py"
  if [ ! -f "$probe" ]; then
    echo "[X] 找不到 qa_gpu_probe.py，无法验证。"
    return 1
  fi
  "$PY" "$probe" --require numpy onnxruntime
}

# ---------------------------------------------------------------------------
#  主流程
# ---------------------------------------------------------------------------

main() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --check)  PRE=1 ;;
      --verify) VERIFY_ONLY=1 ;;
      --cpu)    TARGET=cpu ;;
      -h|--help) sed -n '3,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
      *) echo "[X] 不认识的参数：$1（--help 查看用法）"; exit 1 ;;
    esac
    shift
  done

  echo "============================================================"
  echo "  qa-assistant —— 启用 OCR 的 GPU 加速"
  echo "============================================================"
  echo

  # 1) Python
  if ! find_python; then
    echo "[X] 没找到可用的 Python。OCR 需要 Python 3.10+。"
    exit 1
  fi
  echo "[1/5] Python：$PYVER（$PYARCH）"
  if python_too_old; then
    echo "[X] Python $PYVER 太旧，需要 3.10 或更新。"
    exit 1
  fi

  if [ -n "$VERIFY_ONLY" ]; then
    echo
    echo "[verify] 只验证，不安装..."
    if verify_gpu; then
      echo "[OK] GPU 加速当前可用。"
      exit 0
    fi
    echo "[!] GPU 当前不生效，应用会退回 CPU。重跑本脚本（不加 --verify）可安装。"
    exit 1
  fi

  # 2) 显卡与驱动
  nvidia_info
  if [ -z "${GPU_NAME:-}" ]; then
    echo "[2/5] 显卡：未检测到 NVIDIA 显卡"
  else
    echo "[2/5] 显卡：$GPU_NAME"
    echo "      驱动：$DRV${CAP:+（算力 $CAP）}"
  fi

  # 3) 门槛
  if [ "$TARGET" = "cpu" ]; then
    echo "[3/5] 指定了 --cpu，跳过 GPU 运行库。"
    install_baseline || exit 1
    echo "[OK] CPU 版 OCR 已装好。"
    exit 0
  fi
  if ! check_thresholds; then
    exit 1
  fi
  echo "[3/5] 门槛检查通过，目标解释器：$PY"

  if [ -n "$PRE" ]; then
    echo
    echo "[i] --check：只做检测，不安装。去掉 --check 即执行安装。"
    exit 0
  fi

  # 4) 安装
  echo
  echo "[4/5] 安装 OCR 基线与 GPU 运行库（官方源，约 1.5 GB）..."
  install_baseline || exit 1
  if ! install_gpu; then
    echo "[X] GPU 运行库安装失败，检查网络后重试。"
    exit 1
  fi

  # 5) 验证
  echo
  echo "[5/5] 验证：用 CUDA 执行一次推理并与 CPU 比对..."
  if ! verify_gpu; then
    echo
    echo "[X] GPU 装上但不可用，应用会自动退回 CPU。"
    echo "    清理后可重试：$PY -m pip uninstall -y onnxruntime-gpu"
    exit 1
  fi
  echo
  echo "============================================================"
  echo "  [OK] GPU 加速已启用并验证通过。"
  echo "  重启应用后识别一份扫描件，日志里会出现「启用 GPU」。"
  echo "  38 页扫描件预期 47 秒 → 17 秒。"
  echo "============================================================"
  exit 0
}

# 只有直接执行时才跑 main；被 source 时（enable-ocr.sh）只取函数
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
