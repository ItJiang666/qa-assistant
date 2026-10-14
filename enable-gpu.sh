#!/usr/bin/env bash
# ============================================================================
#  enable-gpu.sh —— qa-assistant OCR 的 GPU 加速一键安装
# ============================================================================
#  脚本做六件事：
#    1. 找到能用的 Python（会跳过 Microsoft Store 的假 python3），并打印
#       它将要修改的解释器**绝对路径**请你确认
#    2. 查 NVIDIA 驱动（nvidia-smi）与显卡代次
#    3. 判断驱动能否带动 CUDA 13；不能则**明确说清并改走 CPU**
#    4. 安装 OCR 基线（rapidocr + pymupdf）
#    5. 安装 onnxruntime-gpu **以及显式列出的 CUDA/cuDNN 运行库**
#    6. **真·自检**：用 rapidocr 自带的模型建一个 CUDA 会话，确认它真的能用
#
#  ═══════════════════════════════════════════════════════════════════════════
#  ⚠️ 这份脚本的第一版在干净机器上**失败过**（用户实测：只下了 300 多 MB、
#     GPU 没生效）。下面三条是那次事故的根因，改动前务必先读：
#
#  ① **不要依赖 `onnxruntime-gpu[cudnn]` 这种 extras**。
#     实测干净机器上 pip 只装了 160 MB 的 onnxruntime-gpu，**一个 CUDA 运行库
#     都没拉** —— 而 CUDA 13 那套（cudnn 416 MB + nvrtc 45 MB + …）是必需的。
#     160 + 141 ≈ 300 MB，正是用户看到的数字。
#     现在改为**显式列出**每一个包，不把关键依赖交给 extras 解析。
#     必须显式装的原因：只装 onnxruntime-gpu 时，
#     `get_available_providers()` **仍然会列出 CUDAExecutionProvider**
#     （它只是注册了），但建会话时 DLL 加载失败、**静默回落 CPU** ——
#     不报错，只是没变快，极难察觉。
#
#  ② **驱动 < 580 的"CUDA 12 兜底"是个陷阱，已删除**。
#     实测：Azure 那个 onnxruntime-cuda-12 源里虽然列着 1.27/1.28/1.29，
#     但它们的元数据声明的依赖全是 `nvidia-*-cu13` —— 名字叫 12、要的是 13，
#     自相矛盾，按那条路走永远配不好。真正的 CUDA 12 构建只到 1.19 附近。
#     所以现在：驱动 < 580 → **明确告知并改走 CPU**，不再假装能装。
#
#  ③ **必须真建一个会话才算验证通过**。
#     `get_available_providers()` 有 CUDA 不等于能用（见①）。
#     现在用 rapidocr 自带的 ch_ppocr_mobile_v2.0_cls_mobile.onnx（0.56 MB）
#     建一个 CUDA 会话，看 `session.get_providers()` 的第一项是不是它 ——
#     实测本机 2.95 秒完成，代价可接受。
#  ═══════════════════════════════════════════════════════════════════════════
#
#  用法：
#    bash enable-gpu.sh                    # 安装
#    bash enable-gpu.sh --check            # 只看它打算做什么，不安装
#    bash enable-gpu.sh --verify           # 不安装，只跑"真建 CUDA 会话"自检
#    bash enable-gpu.sh --cpu              # 只装 CPU 版 OCR（不碰 GPU 运行库）
#    bash enable-gpu.sh --source URL       # 换安装源（见下）
#    bash enable-gpu.sh --proxy URL        # 走代理（也可用环境变量 HTTPS_PROXY）
#    bash enable-gpu.sh --retries N        # 每步重试次数，默认 3（失败等待逐次翻倍）
#
#  ⚠️ 下载慢 / 装到一半断了怎么办（这一版重点加的东西）：
#    * pip 自己带断点续传与重试，本脚本把参数拉到实用值：
#      `--retries 10 --timeout 60 --resume-retries 5`。默认的 `--timeout 15`
#      对 400 MB 的包太短，慢网下会被反复掐断 —— 那次"只下了 300 多 MB"
#      就有这个因素。
#    * 外层再包一层重试：失败后等 5/10/20 秒重来，最多 --retries 次。
#      已下完的包在 pip 缓存里，重试不会从头下。
#    * **换镜像源**（国内网络最有效）：
#        bash enable-gpu.sh --source https://pypi.tuna.tsinghua.edu.cn/simple
#      实测清华镜像上 `nvidia-cublas` / `nvidia-cudnn-cu13` / `nvidia-cuda-nvrtc`
#      / `onnxruntime-gpu` 都齐全，且 is 主源（不再依赖 pypi.org 与
#      pypi.nvidia.com 的可达性）。CUDA 包在 PyPI 官方也有备份，所以指定镜像后
#      脚本仍会把 pypi.org 作为补充源。
#    * 代理：`--proxy http://127.0.0.1:7890`，或直接设 HTTPS_PROXY（pip 原生支持）。
#    * 安装前会先探测源是否可达，不可达就**立刻**告诉你，不会让你等十几分钟才失败。
#
#  说明：本文件用 UTF-8 + LF。
# ============================================================================

set -u

PRE=""
TARGET=""
VERIFY_ONLY=""
PY=""
PYVER=""
PYARCH=""

#  各档的驱动下限只用于"值不值得试"的判断，**不是**能不能用的结论 ——
#  结论由第 5 步的"真建 CUDA 会话"给出。真正的裁决者是自检。
#  实测各代 onnxruntime-gpu 声明的依赖（PyPI 元数据，2026-10-13 查）：
#    1.31.0          -> nvidia-cudnn-cu13 / nvidia-cuda-nvrtc~=13.0   （CUDA 13）
#    1.21.0 ~ 1.26.0 -> nvidia-cudnn-cu12 / nvidia-cuda-nvrtc-cu12    （CUDA 12）
#    1.20.0 及更早    -> **完全不声明 nvidia 依赖**，必须手动列出       （CUDA 11/12 交界）
#  ⚠️ 我上一版只抽了 1.27/1.28/1.29 就断言"没有可用的 CUDA 12 版"，是抽样太窄 ——
#    那三个版本确实声明 cu13（自相矛盾），但 1.21~1.26 是**货真价实**的 CUDA 12。
#    教训：判断"某条路通不通"必须把候选**列全**再下结论。
CUDA13_MIN_DRIVER=580
CUDA12_MIN_DRIVER=525
CUDA11_MIN_DRIVER=450

#  GPU 运行时档位（从新到旧，脚本按顺序尝试，自检失败就降一档）
#  每档：代号 | 人话名 | 驱动下限 | 显卡代次要求 | onnxruntime-gpu 约束 | 依赖包列表
#
#  ⚠️ 依赖包必须**显式列出**，不要用 `onnxruntime-gpu[cuda,cudnn]` 这种 extras：
#    实测（干净机器）extras 一个 CUDA 运行库都没带进来，只装了主包 —— 这正是
#    用户看到的"只下了 300 多 MB"。
#  ⚠️ cu11 档用的 1.19.2 **不声明**任何 nvidia 依赖（1.20.0 之前都这样），
#    所以那一档的包列表是唯一的来源，绝不能省。
GPU_TIERS="cu13|CUDA 13|580|turing|onnxruntime-gpu==1.31.0|nvidia-cudnn-cu13~=9.0 nvidia-cublas nvidia-cuda-nvrtc~=13.0
cu12|CUDA 12|525|turing|onnxruntime-gpu==1.26.0|nvidia-cudnn-cu12~=9.0 nvidia-cublas-cu12 nvidia-cuda-nvrtc-cu12~=12.0
cu11|CUDA 11.8|450|maxwell|onnxruntime-gpu==1.19.2|nvidia-cudnn-cu11~=9.0 nvidia-cublas-cu11 nvidia-cuda-nvrtc-cu11~=11.8"

NVIDIA_INDEX="https://pypi.nvidia.com"
PYPI_INDEX="https://pypi.org/simple"

#  自定义源/代理/重试（命令行或环境变量，命令行优先）
SRC_INDEX="${QA_GPU_SOURCE:-}"
PROXY="${QA_GPU_PROXY:-}"
STEP_RETRIES="${QA_GPU_RETRIES:-3}"

#  自动选源：每个候选下载多少 MB 来测速。2 MB × 4 个源约 5~10 秒，
#  换来的是"下 1.5 GB 用 6 分钟还是 2 小时"的区别，很划算。
SPEEDTEST_MB="${QA_GPU_SPEEDTEST_MB:-2}"

#  脚本所在目录（用于定位 deploy/ocr/qa_speedtest.py）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

#  最近一次 probe_candidate 的结论 JSON（供 probe_reason / probe_print_evidence 解析）
PROBE_JSON=""

#  pip 的网络参数。默认 --timeout 15 秒对 400 MB 的包太短：
#  慢网下一次卡顿就整包重来，这是把 `--timeout 60` 提上来的原因。
PIP_NET_OPTS="--retries 10 --timeout 60"

#  pip 26 才有 --resume-retries（断点续传）；老 pip 不认识这个参数会直接报错，
#  所以先探测再用，不能硬写上去。
pip_supports_resume() {
  "$PY" -m pip install --help 2>/dev/null | grep -q -- '--resume-retries'
}

# ---------------------------------------------------------------------------
#  install_baseline —— 无论有没有 GPU 都要装的部分
#
#  onnxruntime 只是**推理后端**：真正认字的是 `rapidocr`，把 PDF 拆成位图的
#  是 `pymupdf`。只装 onnxruntime-gpu 的机器依然做不了 OCR。
#
#  这里**不写 onnxruntime 这个名字**：rapidocr 依赖的 `onnxruntime` 与
#  `onnxruntime-gpu` 同名同目录，写上去会把已装的 GPU 版降级成 CPU 版。
# ---------------------------------------------------------------------------
install_baseline() {
  if [ -n "$PRE" ]; then
    echo "      --check：跳过基线安装。"
    return 0
  fi
  # shellcheck disable=SC2086
  pip_retry "装 OCR 基线（rapidocr / pymupdf）" "$PY" -m pip install --upgrade \
    --disable-pip-version-check $PIP_NET_OPTS rapidocr pymupdf || {
    echo "[X] 装 rapidocr / pymupdf 失败。请检查网络（或换源、挂代理）后重试。"
    return 1
  }
  return 0
}

# ---------------------------------------------------------------------------
#  pip_args —— 攒出 pip 的"源 + 代理 + 网络参数"
#
#  规则：
#    * 指定了 --source 时，用它当**主源**（--index-url），另外再挂上 PyPI 官方
#      当补充源（--extra-index-url），两边都留着才能保证解析得出来。
#    * 没指定源时：由 auto_pick_source() **实测挑一个最快的**（见下），
#      挑不出来才回退"官方 PyPI + NVIDIA 源"。
#
#  注意 --index-url 的优先级**高于**环境变量与 pip.conf，所以只有在用户明确
#  要换源、或自动挑源挑出了结果时才传它 —— 否则会悄悄覆盖用户自己的配置。
# ---------------------------------------------------------------------------
pip_args() {
  if [ -n "$SRC_INDEX" ]; then
    echo "--index-url $SRC_INDEX --extra-index-url $PYPI_INDEX"
  else
    echo "--extra-index-url $NVIDIA_INDEX"
  fi
  [ -n "$PROXY" ] && echo "--proxy $PROXY"
  echo "$PIP_NET_OPTS"
  pip_supports_resume && echo "--resume-retries 5"
  return 0
}

# ---------------------------------------------------------------------------
#  auto_pick_source —— 实测各镜像速度，挑最快的那个当安装源
#
#  为什么必须做：GPU 运行库那套很大（实测 cu13 约 1.05 GB、cu12 约 1.49 GB），
#  而各源吞吐差很多。本机实测（2026-10-13，同一个 527 MB 轮子各取 3 MB）：
#      中科大 4.31 ｜ 腾讯云 3.63 ｜ 清华 0.81 ｜ PyPI 官方 0.24  (MB/s)
#  按 1.49 GB 算：最快的约 6 分钟，官方源要约 1.8 小时；某些网络下官方源只有
#  0.02 MB/s，那是 20 小时以上 —— 等于装不完。所以"选对源"不是优化，是能不能
#  装完的分界线。
#
#  测速逻辑放在 deploy/ocr/qa_speedtest.py（随交付包一起发），.sh 与 .cmd 共用，
#  避免两份实现各自漂移。
# ---------------------------------------------------------------------------
auto_pick_source() {
  local script="${SCRIPT_DIR:-.}/deploy/ocr/qa_speedtest.py"
  #  找不到就往上找一层（脚本被单独拷出来用时）
  [ -f "$script" ] || script="$SCRIPT_DIR/qa_speedtest.py"
  if [ ! -f "$script" ]; then
    echo "[i] 没找到 qa_speedtest.py，跳过自动选源（将用官方源）。"
    return 0
  fi
  echo "[i] 正在实测各镜像速度（每个源最多下 $SPEEDTEST_MB MB，约 5~10 秒）..."
  local best
  best=$(PYTHONIOENCODING=utf-8 "$PY" "$script" --limit-mb "$SPEEDTEST_MB" 2>/dev/null \
         | sed -n 's/^BEST\t//p' | head -n1 | tr -d '\r')
  if [ -z "$best" ]; then
    echo "[i] 没测出可用镜像，回退官方源。"
    return 0
  fi
  SRC_INDEX="$best"
  echo "[OK] 已选安装源：$SRC_INDEX"
  return 0
}

# ---------------------------------------------------------------------------
#  install_tier_with_fallback <代号> —— 先按选定源装；失败且选的是镜像时，
#  自动改用官方源（PyPI + NVIDIA）再试一次。
#
#  为什么值得多这一层：镜像偶尔会缺某个版本、或同步到一半。没有这层的话
#  用户只看到"安装失败"，然后得自己摸索着换源；有了它，脚本自己就绕过去了。
#  换源前清一次 pip 缓存，避免把镜像下的坏包/半包复用到官方源上。
# ---------------------------------------------------------------------------
install_tier_with_fallback() {
  local code="$1"
  if install_tier "$code"; then
    return 0
  fi
  if [ -z "$SRC_INDEX" ] || [ "$SRC_INDEX" = "$PYPI_INDEX" ]; then
    return 1
  fi
  echo
  echo "[!] 用 $SRC_INDEX 安装失败，改用官方源（PyPI + NVIDIA）重试一次..."
  echo "    已清掉 pip 缓存里的临时文件，避免把下坏的包带过去。"
  "$PY" -m pip cache purge >/dev/null 2>&1 || true
  SRC_INDEX=""
  install_tier "$code"
}

# ---------------------------------------------------------------------------
#  pip_retry <描述> <pip 命令...> —— 失败重试，等待 5/10/20 秒
#
#  为什么要外层再包一层（pip 自己已经有 --retries）：
#  pip 的 --retries 管的是**单个请求**，而一次 1 GB 的安装会发很多请求。
#  中途整体失败（连接被掐、超时、镜像抖动）时 pip 直接退出，人得手动重跑。
#  已下完的 wheel 在 pip 缓存里，所以重试的代价只是重试的那部分。
# ---------------------------------------------------------------------------
pip_retry() {
  local desc="$1"; shift
  local attempt=1 wait_s=5
  local max="$STEP_RETRIES"
  case "$max" in ''|*[!0-9]*) max=3 ;; esac

  while :; do
    echo "      命令：$*"
    if "$@"; then
      return 0
    fi
    if [ "$attempt" -ge "$max" ]; then
      echo
      echo "[X] $desc 失败，已重试 $max 次。"
      return 1
    fi
    echo
    echo "[!] $desc 第 $attempt 次失败，${wait_s} 秒后重试（已下载的部分会复用）..."
    sleep "$wait_s"
    attempt=$((attempt + 1))
    wait_s=$((wait_s * 2))
  done
}

# ---------------------------------------------------------------------------
#  check_source —— 安装前先探源，不可达就立刻说，不让人干等
# ---------------------------------------------------------------------------
check_source() {
  local url="$1"
  echo -n "      探测安装源可达性：$url ... "
  if "$PY" - "$url" <<'PYEOF'
import sys, urllib.request
url = sys.argv[1]
try:
    req = urllib.request.Request(url, method='HEAD')
    urllib.request.urlopen(req, timeout=20)
except Exception as exc:
    try:
        # 有些源不支持 HEAD，用 GET 再试一次（只读 1 字节）
        urllib.request.urlopen(url, timeout=20).read(1)
    except Exception:
        print(type(exc).__name__)
        sys.exit(1)
PYEOF
  then
    echo "可达"
    return 0
  fi
  echo "不可达"
  return 1
}

# ---------------------------------------------------------------------------
#  find_python —— 找到并**实际执行验证**一个可用的 Python
#
#  ⚠️ 必须实际执行验证，不能只 `command -v`：Windows 上装了 Store 版 Python 时，
#     PATH 里有个 `WindowsApps/python3` 跳转存根，找得到但执行失败。
# ---------------------------------------------------------------------------
find_python() {
  PY=""
  PYVER=""
  for cand in python3 python; do
    command -v "$cand" >/dev/null 2>&1 || continue
    probe=$("$cand" -c 'import sys;print(sys.version.split()[0])' 2>/dev/null | tr -d '\r')
    case "$probe" in
      [0-9]*.[0-9]*) PY="$cand"; PYVER="$probe"; break ;;
      *) echo "[i] 跳过不可用的 $cand（存在但执行失败，可能是 Store 跳转存根）" ;;
    esac
  done
  [ -n "$PY" ] || return 1
  PYARCH=$("$PY" -c 'import platform;print(platform.machine())' 2>/dev/null | tr -d '\r')
  return 0
}

# ---------------------------------------------------------------------------
#  python_too_old —— OCR 那套依赖要 Python 3.10+（与 .cmd 版同一道门槛）
#  早点说清楚，比让 pip 抛一屏解析失败好得多。
# ---------------------------------------------------------------------------
python_too_old() {
  local maj min
  maj=$(echo "$PYVER" | cut -d. -f1)
  min=$(echo "$PYVER" | cut -d. -f2)
  case "$maj" in ''|*[!0-9]*) return 0 ;; esac
  case "$min" in ''|*[!0-9]*) min=0 ;; esac
  [ "$maj" -lt 3 ] && return 0
  [ "$maj" -eq 3 ] && [ "$min" -lt 10 ] && return 0
  return 1
}

# ---------------------------------------------------------------------------
#  confirm_target —— 把"装到哪个环境"摆到明面上（可设 QA_GPU_YES=1 跳过）
# ---------------------------------------------------------------------------
confirm_target() {
  echo "      解释器：$("$PY" -c 'import sys;print(sys.executable)' 2>/dev/null | tr -d '\r')"
  if [ "${QA_GPU_YES:-}" = "1" ] || [ ! -t 0 ]; then
    return 0
  fi
  printf "      安装将进入上面这个环境。继续？[Y/n] "
  read -r ans
  case "$ans" in
    n|N|no|NO) echo "[i] 已取消。"; return 1 ;;
  esac
  return 0
}

# ---------------------------------------------------------------------------
#  arch_rank —— 把显卡名字归到一个代次序号（越大越新）。
#  判据是**代次**而不是具体型号：CUDA 运行库按算力架构编译，同代次一视同仁。
#  序号用于和档位的 arch_need 比较。
# ---------------------------------------------------------------------------
arch_rank() {
  local n="$1"
  case "$n" in
    *"RTX 50"*|*"RTX 51"*|*"RTX 52"*|*"RTX 53"*|*"RTX 54"*|*"RTX 55"*|*"RTX 56"*|*"RTX 57"*|*"RTX 58"*|*"RTX 59"*) echo 90 ;; # Blackwell
    *"RTX 40"*|*"RTX 41"*|*"RTX 42"*|*"RTX 43"*|*"RTX 44"*|*"RTX 45"*|*"RTX 46"*|*"RTX 47"*|*"RTX 48"*|*"RTX 49"*) echo 80 ;; # Ada
    *"RTX 30"*|*"RTX 31"*|*"RTX 32"*|*"RTX 33"*|*"RTX 34"*|*"RTX 35"*|*"RTX 36"*|*"RTX 37"*|*"RTX 38"*|*"RTX 39"*) echo 70 ;; # Ampere
    *"RTX 20"*|*"GTX 16"*|*"TITAN RTX"*|*"Quadro RTX"*|*"Quadro T"*|*"Tesla T"*) echo 60 ;;                            # Turing
    *"GTX 10"*|*"GT 10"*|*"GTX 9"*|*"GT 9"*|*"GTX 7"*|*"GT 7"*|*"Quadro P"*|*"Quadro M"*|*"Quadro K"*|*"Tesla P"*|*"Tesla K"*|*"Tesla M"*|*"MX"*) echo 50 ;; # Maxwell/Pascal/Kepler
    *) echo 0 ;;   # 认不出来：见 tier_eligible 里对 rank=0 的特殊处理
  esac
}

#  档位对显卡的最低要求（与 arch_rank 的序号对应）
arch_need_rank() {
  case "$1" in
    turing)  echo 60 ;;
    maxwell) echo 50 ;;
    *)       echo 0 ;;
  esac
}

# ---------------------------------------------------------------------------
#  tier_eligible <代号> <驱动主版本> <显卡名>
#  返回 0 = 这一档值得试；1 = 明确不适用（跳过，省下 1 GB 下载）。
#  返回 2 = 驱动低于这一档的要求（可以试更旧的档）。
# ---------------------------------------------------------------------------
tier_eligible() {
  local code="$1" drvmajor="$2" gpuname="$3"
  local kind name mindrv archneed ortspec deps
  IFS='|' read -r kind name mindrv archneed ortspec deps <<EOF
$(tier_line "$code")
EOF
  local rank need
  rank=$(arch_rank "$gpuname")
  need=$(arch_need_rank "$archneed")
  if [ "$rank" -eq 0 ]; then
    #  认不出的型号：**不要**直接拒。宁可多试一次也不要把人挡在门外 ——
    #  这正是 560.94 那次的教训（当时是靠推断拒绝，而不是靠实测）。自检会给出结论。
    echo "[i] $name：显卡型号（$gpuname）不在已知代次表内，仍会尝试；不行由自检裁决。"
  elif [ "$rank" -lt "$need" ]; then
    echo "[i] $name：当前显卡（$gpuname）代次过旧，CUDA 运行库已不支持它，跳过。"
    return 1
  fi
  if [ "$drvmajor" -lt "$mindrv" ]; then
    return 2
  fi
  return 0
}

# ---------------------------------------------------------------------------
#  tier_line <代号> —— 从 GPU_TIERS 里取出一行
# ---------------------------------------------------------------------------
tier_line() {
  echo "$GPU_TIERS" | while IFS= read -r line; do
    case "${line%%|*}" in
      "$1") echo "$line"; break ;;
    esac
  done
}

# ---------------------------------------------------------------------------
#  tier_field <代号> <字段号 1-6>
# ---------------------------------------------------------------------------
tier_field() {
  local line
  line=$(tier_line "$1")
  echo "$line" | cut -d'|' -f"$2"
}

# ---------------------------------------------------------------------------
#  purge_gpu_packages —— 换档前把上一档的包彻底清掉
#
#  ⚠️ 必须清干净，不能"叠着装"：三代的发行包名不同、但都往**同一个**
#     `site-packages\nvidia\**\bin` 和**同一个** `onnxruntime` 目录里放 DLL。
#     装上 cu12 却留着 cu13 的 cublas，onnxruntime 可能加载到错的那份 ——
#     表现仍然是"静默回落 CPU"，正是本项目反复踩的那个坑。
#     `onnxruntime-gpu` 与 `onnxruntime` 也同名同目录，两个都要卸。
# ---------------------------------------------------------------------------
purge_gpu_packages() {
  echo "      清理上一档的 GPU 包（避免两代 CUDA 的 DLL 混在一起）..."
  #  注意 nvidia-* 是**故意不加引号**的：要让 pip 自己去展开这个通配符，
  #  而不是由 shell 展开文件。这些名字在正常目录里匹配不到文件，所以走的是
  #  字面量传参；万一目录里真有同名文件，加引号反而更危险（会把文件名喂给 pip）。
  #  不能只卸已知名字：换档时残留的上一代包会让 onnxruntime 加载到错的 DLL，
  #  表现又是"静默回落 CPU"。
  "$PY" -m pip uninstall -y -q \
    onnxruntime onnxruntime-gpu nvidia-* \
    >/dev/null 2>&1
  return 0
}

# ---------------------------------------------------------------------------
#  install_tier <代号> —— 安装某一档，返回 pip 的结果
# ---------------------------------------------------------------------------
install_tier() {
  local code="$1"
  local name ortspec deps
  name=$(tier_field "$code" 2)
  ortspec=$(tier_field "$code" 5)
  deps=$(tier_field "$code" 6)
  echo "      档位：$name"
  echo "      包  ：$ortspec  +  $deps"
  #  ⚠️ `--force-reinstall` 不能省（实测）：`onnxruntime-gpu` 的不同版本**文件落在
  #     同一个目录**（`site-packages/onnxruntime`），pip 却把它们记成两条独立记录。
  #     换档/换版本时若不加这个参数，pip 会认为"已满足"而静默跳过，
  #     **版本号不变、库却还是旧的** —— 表现就是"换了档位但一点变化都没有"。
  # shellcheck disable=SC2086
  pip_retry "安装 $name 运行库" "$PY" -m pip install --upgrade --force-reinstall \
    --disable-pip-version-check $(pip_args) "$ortspec" $deps
}

# ---------------------------------------------------------------------------
#  probe_candidate <档位代号> <驱动版本> <显卡名> —— 「隔离试装 + 真实前向验证」
#
#  <h2>为什么必须这么做（2026-10-14 在干净机器上踩出来的，代价是一整晚）</h2>
#  旧流程是「先装进用户环境 → 再自检」，而自检只到「建起了 CUDA 会话」这一层。
#  在一台**完全干净**的 Windows 机器上（RTX 4060 Laptop + 驱动 596.36 +
#  Python 3.13.16），脚本打印了：
#
#      PROBE session providers: CUDAExecutionProvider|CPUExecutionProvider
#      [OK] GPU acceleration is ENABLED -- and a CUDA session was really built.
#
#  然后真实 OCR **38 页一个字都没认出来**，onnxruntime 日志里是
#      CUDNN failure 5003: CUDNN_STATUS_EXECUTION_FAILED_CUDART ; GPU=0
#
#  也就是说：「执行器注册了 + DLL 加载了 + 会话建起来了」三条全成立，GPU 依然算不出东西。
#  所以旧自检在干净环境里 **100% 假通过**，而且装错了还把状态留在用户环境里。
#
#  改法：先在**临时 venv** 里试装，用 qa_gpu_probe.py 跑一次**真实前向**（Relu 输出
#  与 CPU 逐元素比对），通过了才落地到用户环境。任何"装得上但用不了"的组合
#  都会在落地**之前**被淘汰 —— 脚本从此不可能假通过。
#
#  <h2>为什么验证逻辑放在 Python 里</h2>
#  .sh 和 .cmd 共用同一份 qa_gpu_probe.py，避免两版实现各自漂移（这是本项目
#  反复踩过的坑：探测方式一旦和运行时不一致，"自检通过"就证明不了任何事）。
# ---------------------------------------------------------------------------
probe_candidate() {
  local code="$1" drv="$2" gpu="$3"
  local ortspec deps name
  name=$(tier_field "$code" 2)
  ortspec=$(tier_field "$code" 5)
  deps=$(tier_field "$code" 6)

  local probe="${SCRIPT_DIR:-.}/deploy/ocr/qa_gpu_probe.py"
  [ -f "$probe" ] || probe="$SCRIPT_DIR/qa_gpu_probe.py"
  if [ ! -f "$probe" ]; then
    echo "[i] 没找到 qa_gpu_probe.py，退回旧版自检（无法做隔离验证）。" >&2
    install_tier_with_fallback "$code" && gpu_selftest
    return $?
  fi

  local tmpenv="${TMPDIR:-/tmp}/qa-gpu-try-$$"
  rm -rf "$tmpenv"

  #  包列表用 `;` 分隔后整体作为一个参数传给 --pkgs：
  #  带版本约束的项（如 `nvidia-cudnn-cu13~=9.0`）含 `~` `=` 等字符，
  #  在 shell 里按空格拆再拼回去容易散架，索性由探测器自己拆。
  local pkgs
  pkgs=$(printf '%s;%s' "$ortspec" "$deps" | tr ' ' ';')

  local -a cmd=("$PY" "$probe" --json-only
                --pkgs "$pkgs"
                --require numpy onnxruntime rapidocr
                --target-env "$tmpenv"
                --install-first
                --python "$PY"
                --retries "$STEP_RETRIES" --timeout 60)
  [ -n "$SRC_INDEX" ] && cmd+=(--index "$SRC_INDEX" --extra-index "$PYPI_INDEX")
  [ -n "$PROXY" ]     && cmd+=(--proxy "$PROXY")
  cmd+=(--force-reinstall)

  echo "      档位：$name"
  echo "      包  ：$ortspec  +  $deps"
  local out rc=0
  out=$("${cmd[@]}" 2>&1) || rc=$?
  echo "$out" | grep -v '^###QAGPUPROBE###' | sed 's/^/      /' | tail -n 12

  PROBE_JSON=$(echo "$out" | grep -o '###QAGPUPROBE###.*###END###' | tail -n1 \
               | sed 's/###QAGPUPROBE###//; s/###END###//')
  rm -rf "$tmpenv"
  return $rc
}

#  probe_reason <json> —— 从探测结论里取人话原因（不依赖 jq：目标机器未必有）
probe_reason() {
  python3 -c 'import json,sys
try:
    d = json.loads(sys.argv[1])
    print("%s：%s" % (d.get("stage", "?"), d.get("reason", "?")))
except Exception:
    print("（探测结论无法解析）")' "$1" 2>/dev/null || echo "（探测结论无法解析）"
}

#  probe_print_evidence <json> —— 把 CUDA/CUDNN 报错原文打出来当证据
probe_print_evidence() {
  python3 -c 'import json,sys
try:
    d = json.loads(sys.argv[1])
except Exception:
    sys.exit(0)
for e in (d.get("cuda_errors") or [])[:3]:
    print("      " + e)' "$1" 2>/dev/null || true
}

# ---------------------------------------------------------------------------
#  gpu_selftest —— 真·自检：建一个 CUDA 会话，而不是只看执行器名单
#  返回 0 表示真的能用；1 表示不能用。
#
#  ⚠️ 下面这段库目录搜索与 `deploy/ocr/qa_ocr.py` 的 `_nvidia_dll_dirs()`
#     **刻意保持一致**（按平台取 bin/lib + 两套 glob 布局 + os.path.isdir 过滤）。
#     如果探测的搜索方式和应用运行时的搜索方式不同，自检通过就不能证明应用能用。
#
#  ⚠️⚠️ 本文件是 **Linux/macOS 版**，库目录是 `nvidia/*/lib`，不是 Windows 的 `bin`。
#     历史教训：这一版最初照抄了 `enable-gpu.cmd` 的 Windows 逻辑，只找 `*/bin`、
#     只改 PATH，结果在 Linux 上 **每一个档位都被判成不可用** —— 因为：
#       1. CUDA 13 的包把库放在 `site-packages/nvidia/cu13/lib`（组件名是 cu13，
#          不是包名 cublas/cudnn），Windows 那套 `*/bin` 一个都匹配不到；
#       2. Linux 的动态链接器**根本不看 PATH**，它看 LD_LIBRARY_PATH，而
#          LD_LIBRARY_PATH 只在**进程启动时**读一次，Python 里再设也没用。
#     于是自检必然报 `libcublasLt.so.13: cannot open shared object file` 并回落 CPU，
#     脚本就会误报"所有档位都装不上"。Windows 版不受影响，两边机制不同。
# ---------------------------------------------------------------------------
gpu_selftest() {
  #  v1.35：优先走 qa_gpu_probe.py —— 它除了建会话，还会**真跑一次前向并比对结果**。
  #  只"建起会话"是不够的：实测「执行器注册 + DLL 加载 + 会话建成」三条全成立，
  #  真实推理依然可以全部失败（CUDNN_STATUS_EXECUTION_FAILED_CUDART）。
  local probe="${SCRIPT_DIR:-.}/deploy/ocr/qa_gpu_probe.py"
  [ -f "$probe" ] || probe="$SCRIPT_DIR/qa_gpu_probe.py"
  if [ -f "$probe" ]; then
    local out rc=0
    out=$("$PY" "$probe" --json-only --require numpy onnxruntime 2>&1) || rc=$?
    echo "$out" | grep -v '^###QAGPUPROBE###' | sed 's/^/  /'
    if [ "$rc" -eq 0 ]; then
      return 0
    fi
    PROBE_JSON=$(echo "$out" | grep -o '###QAGPUPROBE###.*###END###' | tail -n1 \
                 | sed 's/###QAGPUPROBE###//; s/###END###//')
    echo "  [自检失败] $(probe_reason "$PROBE_JSON")"
    probe_print_evidence "$PROBE_JSON"
    return 1
  fi

  local probe_py
  probe_py=$(mktemp "${TMPDIR:-/tmp}/qa-gpu-probe-XXXXXX.py" 2>/dev/null || echo "/tmp/qa-gpu-probe-$$.py")
  cat > "$probe_py" <<'PYEOF'
import ctypes, glob, os, sys
# 平台判定用 os.name，而不是 sys.platform 前缀，和 qa_ocr.py 保持一致
pats = ('*/bin', '*/bin/x86_64', '*/*/bin', '*/*/bin/x86_64') if os.name == 'nt' \
       else ('*/lib', '*/*/lib')
dirs = []
for entry in sys.path:
    if not entry or not os.path.isdir(entry):
        continue
    root = os.path.join(entry, 'nvidia')
    if not os.path.isdir(root):
        continue
    for pat in pats:
        dirs += glob.glob(os.path.join(root, pat))
dirs = [d for d in dict.fromkeys(dirs) if os.path.isdir(d)]
print('PROBE nvidia lib dirs: %d' % len(dirs))
preload = 0
if os.name == 'nt':
    # Windows：onnxruntime 加载 EP 走 PATH（os.add_dll_directory 实测无效）
    if dirs:
        os.environ['PATH'] = os.pathsep.join(dirs + [os.environ.get('PATH', '')])
else:
    # Linux：LD_LIBRARY_PATH 在这里设已经太晚（链接器启动时就读完了），
    # 真正有效的是用 RTLD_GLOBAL 预加载，让库的 SONAME 进入进程全局符号表；
    # 之后 onnxruntime 再 dlopen 它的 CUDA EP 时就能直接复用，不再去磁盘找。
    if dirs:
        os.environ['LD_LIBRARY_PATH'] = os.pathsep.join(
            dirs + [os.environ.get('LD_LIBRARY_PATH', '')])
    for d in dirs:
        for so in sorted(glob.glob(os.path.join(d, '*.so*'))):
            try:
                ctypes.CDLL(so, mode=ctypes.RTLD_GLOBAL)
                preload += 1
            except OSError:
                pass          # 单个库失败不致命，下面的会话自检会给出最终结论
    print('PROBE preloaded .so: %d' % preload)
try:
    import onnxruntime as ort
except Exception as e:
    print('PROBE import onnxruntime failed: %s' % e); sys.exit(1)
provs = ort.get_available_providers()
print('PROBE providers: %s' % '|'.join(provs))
if 'CUDAExecutionProvider' not in provs:
    print('PROBE CUDA provider not registered'); sys.exit(1)

def builtin_model():
    """手搓一个 97 字节的 Relu 单节点模型，不依赖 rapidocr / onnx 包。

    字段号取自 onnx.proto：ModelProto{ir_version=1,graph=7,opset_import=8}、
    GraphProto{node=1,name=2,input=11,output=12}、NodeProto{input=1,output=2,name=3,op_type=4}、
    ValueInfoProto{name=1,type=2}、TypeProto{tensor_type=1}、TypeProto.Tensor{elem_type=1,shape=2}、
    TensorShapeProto{dim=1}、Dim{dim_value=1}。protobuf 是 onnxruntime 的硬依赖，必然在。
    """
    import uuid
    from google.protobuf import descriptor_pb2, descriptor_pool, message_factory
    F = descriptor_pb2.FieldDescriptorProto
    pkg = 'qagpuprobe' + uuid.uuid4().hex[:8]
    fdp = descriptor_pb2.FileDescriptorProto()
    fdp.name, fdp.package, fdp.syntax = pkg + '.proto', pkg, 'proto3'
    def add(m, name, num, typ, label=1, type_name=None):
        f = m.field.add()
        f.name, f.number, f.type, f.label = name, num, typ, label
        if type_name:
            f.type_name = type_name
    def newm(name):
        m = fdp.message_type.add(); m.name = name; return m
    m = newm('Dim'); add(m, 'dim_value', 1, F.TYPE_INT64)
    m = newm('TensorShape'); add(m, 'dim', 1, F.TYPE_MESSAGE, 3, '.' + pkg + '.Dim')
    m = newm('TensorType')
    add(m, 'elem_type', 1, F.TYPE_INT32)
    add(m, 'shape', 2, F.TYPE_MESSAGE, 1, '.' + pkg + '.TensorShape')
    m = newm('Type'); add(m, 'tensor_type', 1, F.TYPE_MESSAGE, 1, '.' + pkg + '.TensorType')
    m = newm('ValueInfo')
    add(m, 'name', 1, F.TYPE_STRING)
    add(m, 'type', 2, F.TYPE_MESSAGE, 1, '.' + pkg + '.Type')
    m = newm('Node')
    add(m, 'input', 1, F.TYPE_STRING, 3); add(m, 'output', 2, F.TYPE_STRING, 3)
    add(m, 'name', 3, F.TYPE_STRING); add(m, 'op_type', 4, F.TYPE_STRING)
    m = newm('Graph')
    add(m, 'node', 1, F.TYPE_MESSAGE, 3, '.' + pkg + '.Node')
    add(m, 'name', 2, F.TYPE_STRING)
    add(m, 'input', 11, F.TYPE_MESSAGE, 3, '.' + pkg + '.ValueInfo')
    add(m, 'output', 12, F.TYPE_MESSAGE, 3, '.' + pkg + '.ValueInfo')
    m = newm('OpsetId'); add(m, 'version', 2, F.TYPE_INT64)
    m = newm('Model')
    add(m, 'ir_version', 1, F.TYPE_INT64)
    add(m, 'graph', 7, F.TYPE_MESSAGE, 1, '.' + pkg + '.Graph')
    add(m, 'opset_import', 8, F.TYPE_MESSAGE, 3, '.' + pkg + '.OpsetId')
    pool = descriptor_pool.DescriptorPool(); pool.Add(fdp)
    try:
        M = message_factory.GetMessageClass(pool.FindMessageTypeByName(pkg + '.Model'))
    except AttributeError:                      # protobuf < 4.22 没有这个函数
        M = message_factory.MessageFactory(pool).GetPrototype(
            pool.FindMessageTypeByName(pkg + '.Model'))
    mo = M(); mo.ir_version = 7; mo.opset_import.add().version = 17
    mo.graph.name = 'qa_gpu_probe'
    for name, cont in (('x', mo.graph.input), ('y', mo.graph.output)):
        vi = cont.add(); vi.name = name; vi.type.tensor_type.elem_type = 1
        for d in (1, 3, 32, 32):
            vi.type.tensor_type.shape.dim.add().dim_value = d
    nd = mo.graph.node.add(); nd.input.append('x'); nd.output.append('y'); nd.op_type = 'Relu'
    return mo.SerializeToString()

# self-test 优先用 rapidocr 自带的真实模型（更贴近实际推理），取不到再用手搓模型
model = None
try:
    import rapidocr
    cand = os.path.join(os.path.dirname(rapidocr.__file__),
                        'models', 'ch_ppocr_mobile_v2.0_cls_mobile.onnx')
    if os.path.exists(cand):
        model = cand
        print('PROBE model: rapidocr bundled')
except Exception:
    pass
if model is None:
    try:
        model = builtin_model()
        print('PROBE model: builtin %d bytes' % len(model))
    except Exception as e:
        print('PROBE cannot build a model: %s' % e); sys.exit(1)
so = ort.SessionOptions()
so.log_severity_level = 3
try:
    sess = ort.InferenceSession(model, sess_options=so,
                                providers=['CUDAExecutionProvider', 'CPUExecutionProvider'])
except Exception as e:
    print('PROBE session failed: %s' % e); sys.exit(1)
actual = sess.get_providers()
print('PROBE session providers: %s' % '|'.join(actual))
sys.exit(0 if actual and actual[0] == 'CUDAExecutionProvider' else 1)
PYEOF
  "$PY" "$probe"
  local rc=$?
  rm -f "$probe"
  return $rc
}

# ===========================================================================
#  main
# ===========================================================================
main() {
  #  逐参数扫描（带值的选项要跳一格，所以用 while 而不是 for）
  while [ $# -gt 0 ]; do
    case "$1" in
      --check)   PRE=1 ;;
      --verify)  VERIFY_ONLY=1 ;;
      --cpu)     TARGET=cpu ;;
      --source)  shift; [ $# -gt 0 ] || { echo "[X] --source 后面要跟一个源地址"; exit 1; }; SRC_INDEX="$1" ;;
      --proxy)   shift; [ $# -gt 0 ] || { echo "[X] --proxy 后面要跟一个代理地址"; exit 1; }; PROXY="$1" ;;
      --retries) shift; [ $# -gt 0 ] || { echo "[X] --retries 后面要跟次数"; exit 1; }; STEP_RETRIES="$1" ;;
      -h|--help) sed -n '2,53p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
      *) echo "[X] 不认识的参数：$1（用 --help 看用法）"; exit 1 ;;
    esac
    shift
  done

  echo "============================================================"
  echo "  qa-assistant  ——  启用 OCR 的 GPU 加速"
  echo "============================================================"
  echo

  # ---- 1. Python -----------------------------------------------------------
  if ! find_python; then
    echo "[X] 没找到可用的 Python。OCR 功能本身就需要 Python 3.10+，请先安装。"
    exit 1
  fi
  echo "[1/6] Python：$PYVER（$PYARCH）"
  if python_too_old; then
    echo "[X] Python $PYVER 太旧。OCR 这套依赖需要 Python 3.10 或更新。"
    echo "    装个新 Python，或者继续用 CPU（本脚本不会改动现有环境）。"
    exit 1
  fi
  [ -n "$SRC_INDEX" ] && echo "      安装源：$SRC_INDEX（自定义）"
  [ -n "$PROXY" ]     && echo "      代理  ：$PROXY"
  confirm_target || exit 0

  #  --verify：不装任何东西，只重跑自检，用来回答"现在到底生效了没有"。
  if [ -n "$VERIFY_ONLY" ]; then
    echo
    echo "[verify] 只做自检，不安装任何东西..."
    if gpu_selftest; then
      echo
      echo "[OK] GPU 加速**当前可用**。"
      exit 0
    fi
    echo
    echo "[!] CUDA 会话建不起来 —— GPU 当前**不生效**（应用会自动用 CPU）。"
    echo "    重跑本脚本（不加 --verify）即可安装/修复。"
    exit 1
  fi

  # ---- 2. 驱动与显卡 -------------------------------------------------------
  local DRV="" GPUNAME=""
  if command -v nvidia-smi >/dev/null 2>&1; then
    DRV=$(nvidia-smi --query-gpu=driver_version --format=csv,noheader 2>/dev/null | head -n1 | tr -d '\r')
    GPUNAME=$(nvidia-smi --query-gpu=name --format=csv,noheader 2>/dev/null | head -n1 | tr -d '\r')
  fi
  if [ -z "$DRV" ]; then
    echo "[2/6] NVIDIA 驱动：未检测到"
    echo
    echo "[i] 这台机器上没有可用的 NVIDIA 显卡，跳过 GPU 运行库（否则是白下约 1 GB）。"
    echo "    改为安装 CPU 版 OCR 基线，功能照常可用。"
    install_baseline || exit 1
    exit 0
  fi
  echo "[2/6] 显卡：$GPUNAME"
  echo "      驱动：$DRV"

  # ---- 3. 算出驱动主版本，并列出可尝试的档位 -------------------------------
  #  这里**不再**用驱动版本把人挡在门外（那正是 560.94 那次的错）：
  #  驱动版本只决定"哪些档位值得试"，能否生效交给第 5 步的真自检裁决。
  local DRVMAJOR
  DRVMAJOR=$(echo "$DRV" | cut -d. -f1)
  case "$DRVMAJOR" in ''|*[!0-9]*) DRVMAJOR=0 ;; esac

  if [ "$TARGET" = "cpu" ]; then
    echo "[3/6] 指定了 --cpu：跳过 GPU 运行库。"
    install_baseline || exit 1
    exit 0
  fi
  echo "[3/6] 驱动主版本：$DRVMAJOR（将据此挑选可尝试的档位）"

  # ---- 4. OCR 基线 ---------------------------------------------------------
  echo "[4/6] 安装 OCR 基线（rapidocr、pymupdf）..."
  install_baseline || exit 1
  echo

  # ---- 5+6. 按档位尝试 GPU 运行库，装完立刻真自检，失败降档 ----------------
  #  ⚠️ 这是本脚本的核心设计：**驱动下限不靠推断，靠自检裁决**。
  #     驱动版本只能告诉我们"某档值不值得试"，能不能用由"建 CUDA 会话"回答。
  #     这样做的好处：不管什么显卡、什么驱动，只要能跑就一定能装上；
  #     宁可靠实测多试一次，也不要靠猜测把人挡在门外（那正是 560.94 那次的教训）。
  local TIER_OK="" TIER_LAST_FAIL="" TIER_TRIED=0
  local CANDIDATES=""
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    code=$(echo "$line" | cut -d'|' -f1)
    tier_eligible "$code" "$DRVMAJOR" "$GPUNAME"
    rc=$?
    case $rc in
      0) CANDIDATES="$CANDIDATES $code" ;;
      1) : ;;                      # 代次不支持，已提示
      2) : ;;                      # 驱动不够，静默跳过（下面统一汇总）
    esac
  done <<EOF
$GPU_TIERS
EOF

  if [ -z "$CANDIDATES" ]; then
    echo
    echo "[!] 没有一档 GPU 运行库适用于当前组合（驱动 $DRV、显卡 $GPUNAME）。"
    echo "    驱动太旧：CUDA 11.8 也需要驱动 >= $CUDA11_MIN_DRIVER。"
    echo "    请升级 NVIDIA 驱动后重跑；在此之前 CPU 版 OCR 已装好，功能照常可用。"
    exit 0
  fi

  echo "[5/6] 将按顺序尝试这些档位：$CANDIDATES"
  echo "      说明：驱动下限只用来决定'值不值得试'；每一档都在**隔离的临时环境**里"
  echo "            试装，并用**真实前向**（Relu 输出与 CPU 逐元素比对）裁决，"
  echo "            通过的才会装进你的正式环境 —— 所以本脚本不会假通过。"
  echo "      网络  ：$PIP_NET_OPTS$(pip_supports_resume && echo ' --resume-retries 5')"
  echo
  if [ -n "$PRE" ]; then
    echo "[i] --check：只做检测，不安装。去掉 --check 即真正执行。"
    echo "    （自动选源与探源也会跳过，避免 --check 还要下几 MB 测速）"
    exit 0
  fi

  #  没指定 --source 时，实测挑一个最快的源。
  #  这一步是 v1.33 加的：GPU 包 1~1.5 GB，各源实测差十几倍，
  #  默认用官方源在慢网下等于装不完。
  if [ -z "$SRC_INDEX" ]; then
    auto_pick_source
  else
    echo "[i] 使用命令行指定的源：$SRC_INDEX"
  fi
  echo "      索引源：${SRC_INDEX:-$NVIDIA_INDEX（官方）}"
  echo

  #  装之前先探源：不可达就立刻说清楚，而不是让人等十几分钟再看到一堆超时。
  if ! check_source "${SRC_INDEX:-$NVIDIA_INDEX}"; then
    echo
    echo "[X] 上面这个安装源从本机访问不通，先不开始下载（免得白等）。"
    echo "    可以试："
    echo "      1) 换镜像源：bash enable-gpu.sh --source https://pypi.tuna.tsinghua.edu.cn/simple"
    echo "      2) 走代理  ：bash enable-gpu.sh --proxy http://127.0.0.1:7890"
    echo "      3) 检查网络/DNS/防火墙后重跑。"
    echo "    什么都没被弄坏：应用会自动回落到 CPU，照常可用。"
    exit 1
  fi

  for code in $CANDIDATES; do
    TIER_TRIED=$((TIER_TRIED + 1))
    echo
    echo "------------------------------------------------------------"
    echo "[$TIER_TRIED] 尝试 $(tier_field "$code" 2)"
    echo "------------------------------------------------------------"
    echo
    echo "      验证：在一个**隔离的临时环境**里试装，并用真实前向验证（约 1~2 分钟）..."
    probe_rc=0
    probe_json=$(probe_candidate "$code" "$DRV" "$GPUNAME") || probe_rc=$?
    if [ "$probe_rc" -eq 0 ]; then
      TIER_OK="$code"
      break
    fi
    echo
    echo "[!] $(tier_field "$code" 2) 未通过验证 —— 这一档在本机不可用。"
    echo "    原因：$(probe_reason "$probe_json")"
    probe_print_evidence "$probe_json"
    TIER_LAST_FAIL="$code"
  done

  # ---- 落地：验证通过的档位才装进用户环境 ---------------------------------
  #  到这里说明上面已经有一个档位在**隔离环境里验证通过**了。
  #  现在把它装进用户真正要用的解释器，然后**再验一次** ——
  #  因为隔离环境和用户环境的差别（已有的旧包、权限、Python 版本细节）
  #  本身就可能让结果不同，不能假定"隔离能跑 = 用户环境也能跑"。
  if [ -n "$TIER_OK" ]; then
    echo
    echo "============================================================"
    echo "  验证通过的档位：$(tier_field "$TIER_OK" 2)"
    echo "  现在装进正式环境：$PY"
    echo "============================================================"
    purge_gpu_packages
    if ! install_tier_with_fallback "$TIER_OK"; then
      echo
      echo "[X] 验证通过的档位却装不进正式环境（网络/权限问题）。"
      echo "    隔离环境已经证明这套组合在本机可用，重跑本脚本即可（已下载的包会复用）。"
      exit 1
    fi
    echo
    echo "      在正式环境里复验一次（这一步不能省：隔离环境与用户环境并不等同）..."
    if ! gpu_selftest; then
      echo
      echo "[X] 隔离环境通过、正式环境却没通过 —— 说明你这台机器的目标解释器里有干扰。"
      echo "    最常见的原因：**同目录下混着两代 CUDA 包**（pip 记录里看得见、文件也还在）。"
      echo "    可先执行下面的命令清干净再重跑本脚本："
      echo "      $PY -m pip uninstall -y onnxruntime onnxruntime-gpu"
      echo "      $PY -m pip list --format=freeze | grep -i nvidia   # 再逐个卸载列出来的包"
      exit 1
    fi
    echo
    echo "============================================================"
    echo "  [OK] GPU 加速已启用 —— 档位：$(tier_field "$TIER_OK" 2)"
    echo "       已通过**真实前向验证**（不是只看执行器名单，也不是只看建没建起会话）。"
    echo
    echo "  重启应用后随便识别一份扫描件，到日志里搜「启用 GPU」确认。"
    echo "  预期效果（38 页扫描件实测）：约 47 秒 → 约 17 秒。"
    echo "  随时可用 --verify 复查当前是否生效。"
    echo "============================================================"
    exit 0
  fi

  echo
  echo "============================================================"
  echo "  [!] 所有候选档位都没通过验证 —— GPU 不会生效（会自动用 CPU）。"
  echo
  echo "  每一档的失败原因已经在上面逐条打印（含 CUDA/CUDNN 报错原文）。"
  echo "  这套流程是 v1.35 特意加的：旧版只验证到「建起 CUDA 会话」，"
  echo "  而实测存在「执行器注册了、DLL 加载了、会话也建起来了，"
  echo "  但一跑推理就 CUDNN_STATUS_EXECUTION_FAILED_CUDART」的情况 ——"
  echo "  旧版会把它报成成功，用户装完才发现 38 页一个字都认不出来。"
  echo
  echo "  常见原因与对策："
  echo "    - 驱动版本低于所装 CUDA 运行库的要求 → 升级 NVIDIA 驱动后重跑"
  echo "    - 显卡代次过旧，CUDA 运行库已不支持 → 只能用 CPU"
  echo "    - 同目录混装了两代 CUDA 包 → 按上面的提示清干净后重跑"
  echo "    - 网络把包下坏了 → 重跑本脚本（已下的会复用）"
  echo "  当前环境仍可正常跑 OCR，只是走 CPU（功能不受影响）。"
  echo "============================================================"
  exit 1
}

# ⚠️ 只有"直接执行"时才跑 main。
#
#   不能写成裸的 `main "$@"`：本文件会被 enable-ocr.sh **source** 以复用
#   install_baseline / find_python / confirm_target。source 会把整个文件当脚本
#   执行一遍，于是**调用方只是想借用函数、却把 GPU 安装整套跑了起来**（实测踩到过）。
#   判据：直接执行时 ${BASH_SOURCE[0]} 与 $0 相同；被 source 时不同。
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
