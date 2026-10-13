#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""qa-assistant 本地离线 OCR 脚本。

由 Java 侧（`RapidOcrEngine`）以子进程方式调用，**每次调用都是独立一次**：
启动 → 拆页 → 识别 → 输出 JSON → 退出。刻意不做常驻服务：

  * OCR 是偶发重活，常驻会一直占 200~500 MB 内存；
  * 子进程崩了不影响主进程，还能被超时强杀；
  * 冷启动只要 0.4 s，代价可以忽略。

## 输入：PDF **或**单张图片

PyMuPDF 既能开 PDF，也能直接把 `.jpg/.png/.webp/.bmp` 当"只有一页的文档"打开
（`page_count == 1`，页面矩形就是图片尺寸，且**文字层恒为空**）。
所以图片不需要任何特殊分支：它就是"一页、且肯定没有文字层"的扫描件，
走完全同一条渲染 → 检测的路径。

实测（1200×760 的中文截图，`--dpi 200`）：165 字、置信度 0.9769、1.85 s；
同一张图塞进 PDF 再识别是 224 字 / 0.9749 —— 多出来的字是"内嵌插图"通道
把那行重复识别了一遍，**并没有多读到内容**。也就是说图片这条路更干净。

## 为什么全程离线是硬要求

本项目的卖点是"数据不出内网"。扫描件往往是最敏感的材料（合同、档案、证件），
如果 OCR 这一步把文件传到云端，等于在门口漏了 —— 所以引擎用 RapidOCR
（ONNX Runtime + PP-OCR 模型，**模型随 pip 包下发**，不联网下载），
拆页用 PyMuPDF（纯本地）。

## 输出协议（Java 侧按此解析）

stdout 里必须出现且只出现一对哨兵，中间是**单行 JSON**：

    ###QAOCR_BEGIN###{"ok":true,...}###QAOCR_END###

用哨兵包裹而不是"假设 stdout 全是 JSON"：第三方库会往 stdout 打日志，
直接把整段 stdout 当 JSON 解析是迟早会踩的坑。

## 两条识别路径（都产出文本，合并后一起交人工确认）

1. **页面文字**（主流程）：把每页渲染成位图，跑文字检测。
   扫描件、拍照件走的就是这条。
2. **内嵌图片里的文字**（补充）：抽取页面中**作为图片插入**的图表、截图、表格照片，
   各自单独识别。补的是主流程的盲区 —— 文字检测器只会"找字"，
   它认不出"这块图里有内容"，于是整块跳过。

   对**单张图片输入**这条路径基本是空转：整张图就是页面本身，
   抽出来的插图与页面内容重复，会被下面的去重逻辑整块丢弃。
   不做特殊处理 —— 多跑一次的代价远小于为它维护一个分支。

用 `--detect-images off` 可以关掉第 2 条，回到旧行为。

## 退出码（Java 侧据此给不同提示，比解析文本可靠）

    0  成功
    2  依赖缺失（rapidocr / pymupdf 没装）
    3  参数错误（文件不存在、页范围非法）
    4  页数超限
    5  文件打不开（PDF 损坏/加密，或图片格式不受支持）
    6  识别结果为空
    7  未预期的内部错误

用法：
    python qa_ocr.py --check                       # 依赖自检（Java 侧用它判断可用性）
    python qa_ocr.py <文件路径> [--max-pages 50] [--dpi 200] [--min-confidence 0.5]
                               [--detect-images on|off] [--max-images 60]
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import sys
import time

SENTINEL_BEGIN = "###QAOCR_BEGIN###"
SENTINEL_END = "###QAOCR_END###"

# ★ stdout/stderr 一律强制 UTF-8。
#
#   这不是洁癖，是**必须**：Windows 上 Python 默认用系统 ANSI 代码页（本机 936/GBK）
#   写 stdout，而 Java 侧按 UTF-8 读 —— 一旦出现中文，轻则乱码、重则
#   `UnicodeDecodeError: invalid start byte` 直接把解析打断。
#   在脚本里 reconfigure 而不是靠调用方传 -X utf8：调用方是 Java，
#   多一个"别忘了加参数"的隐性约定就多一个线上故障点。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

EXIT_OK = 0
EXIT_NO_DEPENDENCY = 2
EXIT_BAD_ARGS = 3
EXIT_TOO_MANY_PAGES = 4
# 名字保留 PDF 前缀：Java 侧（RapidOcrEngine）按这个数字映射用户提示，
# 改常量名会牵动两端，而"5 号 = 文件打不开"的语义并没有变，只是它现在
# 也覆盖"图片格式不受支持"。改名的收益不足一次跨语言同步。
EXIT_PDF_UNREADABLE = 5
EXIT_EMPTY_RESULT = 6
EXIT_INTERNAL = 7

# 低于这个长度的行且不含中文/数字，判为噪点（OCR 对扫描污点会产出 `丶` `|` `—` 之类垃圾）
NOISE_MAX_LEN = 2
# 连续重复次数达到此值时只保留一行（扫描件的页眉页脚会逐页重复）
DEDUP_RUN = 3


def _package_version(name: str) -> str:
    """读已安装包的真实版本号。

    不用 `rapidocr.__version__` —— 实测它是 `None`（该包做了懒加载，
    版本号只在构建时生成、运行时不暴露）。用 importlib.metadata 从包元数据里取，
    这才是"这批文本是谁识别的"唯一可靠的追溯依据。
    """
    try:
        import importlib.metadata as metadata

        return metadata.version(name)
    except Exception:
        return "unknown"


def emit(payload: dict) -> None:
    """按协议输出单行 JSON（哨兵包裹）。"""
    sys.stdout.write(SENTINEL_BEGIN + json.dumps(payload, ensure_ascii=False) + SENTINEL_END + "\n")
    sys.stdout.flush()


def report(payload: dict, args) -> None:
    """打一行**进度**（不套哨兵）。

    <h2>为什么不套哨兵</h2>
    结果只有一个、进度有很多条，两者混在一个协议里迟早出错。现在分工明确：
      * **哨兵之间** = 最终结果，Java 侧只认这一段（`extractPayload` 用正则截取）；
      * **哨兵之外** = 进度/诊断，Java 侧按哨兵取结果时天然忽略，一个字都不用改。

    这正是当初选"哨兵包裹"而不是"假设 stdout 全是 JSON"的意外收益（见模块头注释）。

    进度行还有个实际用途：38 页扫描件要跑几十秒，用户在这期间看不到任何反馈，
    只会以为程序卡死。Java 侧把子进程输出整段读下来，需要时可以从里面挑出进度行。
    行内带 `"qaProgress": true` 便于识别。
    """
    if getattr(args, "progress", "on") != "on":
        return
    try:
        sys.stdout.write(json.dumps({"qaProgress": True, **payload}, ensure_ascii=False) + "\n")
        sys.stdout.flush()
    except Exception:
        # 进度打不出来绝不能影响识别本身
        pass


def fail(code: int, message: str, **extra) -> int:
    emit({"ok": False, "errorCode": code, "message": message, **extra})
    return code


def check_dependencies() -> int:
    """依赖自检：Java 侧靠它判断"OCR 能不能用"，所以必须快、且不加载模型。"""
    missing = []
    versions = {}
    try:
        import rapidocr  # noqa: F401

        versions["rapidocr"] = _package_version("rapidocr")
    except Exception:
        missing.append("rapidocr")
    try:
        import pymupdf  # noqa: F401

        versions["pymupdf"] = _package_version("pymupdf")
    except Exception:
        missing.append("pymupdf")
    try:
        import onnxruntime  # noqa: F401

        versions["onnxruntime"] = onnxruntime.__version__
    except Exception:
        missing.append("onnxruntime")

    if missing:
        return fail(
            EXIT_NO_DEPENDENCY,
            "缺少 Python 依赖：" + "、".join(missing),
            missing=missing,
        )
    emit({"ok": True, "engine": "rapidocr", "versions": versions})
    return EXIT_OK


def _nvidia_dll_dirs() -> list[str]:
    """找出 pip 安装的 NVIDIA 运行时库目录（`site-packages/nvidia/**` 下的 bin / lib）。

    <h2>为什么需要这个函数（实测踩出来的坑，别删）</h2>
    `onnxruntime-gpu` 的 CUDA 版会依赖 `cublasLt64_13.dll`、`cudnn64_9.dll`（Windows）
    或 `libcublasLt.so.13`、`libcudnn.so.9`（Linux）这些库，它们由
    `nvidia-cublas` / `nvidia-cudnn-cu13` 等包提供，落在 site-packages 里面 ——
    **既不在系统 PATH（Windows）也不在默认库搜索路径（Linux）上**。

    于是会出现这个极具迷惑性的现象：包明明装好了、`get_available_providers()`
    也确实列出了 `CUDAExecutionProvider`，但真正建会话时报

        Error loading "onnxruntime_providers_cuda.dll" which depends on
        "cublasLt64_13.dll" which is missing. (Error 126)
        Failed to load library libonnxruntime_providers_cuda.so with error:
        libcublasLt.so.13: cannot open shared object file

    然后**静默回落到 CPU**（表面看只是"没变快"，不报错）。

    两种常见修法实测**都无效**：
      * `os.add_dll_directory()` —— onnxruntime 加载 EP 时不走这个搜索路径；
      * 装完之后再改 PATH —— onnxruntime 在 **import 那一刻**就注册执行器，晚了。

    唯一有效的做法：在 import onnxruntime **之前**把这些目录塞进搜索路径。
    本模块的 `configure_gpu()` 就是这个时机，且由 `main()` 最早调用。

    <h2>目录布局实测（2026-10 在 RTX 5070 / Ubuntu 22.04 / Python 3.12 上量的）</h2>
      * Windows：`nvidia/<组件>/bin`、`nvidia/<组件>/bin/x86_64`；
      * Linux CUDA 13 包：`nvidia/cu13/lib` —— **注意组件名是 `cu13` 而不是包名**，
        onnxruntime 自带的搜索逻辑只认 CUDA 12 时代的 `nvidia/cublas/lib`，
        所以这个目录必须由我们显式加进去，否则必然掉回 CPU；
      * Linux CUDA 12 包：`nvidia/<组件>/lib`。
    因此 Windows 认 `bin`、Linux 认 `lib`，各取所需；两种布局都扫，兼容新旧包。
    """
    patterns = ("*/bin", "*/bin/x86_64", "*/*/bin", "*/*/bin/x86_64") if os.name == "nt" else \
               ("*/lib", "*/*/lib")
    dirs: list[str] = []
    for entry in sys.path:
        if not entry or not os.path.isdir(entry):
            continue
        root = os.path.join(entry, "nvidia")
        if not os.path.isdir(root):
            continue
        for pattern in patterns:
            dirs.extend(glob.glob(os.path.join(root, pattern)))
    # 去重且保持顺序
    return [d for d in dict.fromkeys(dirs) if os.path.isdir(d)]


def _cuda_session_selftest(onnxruntime) -> tuple[bool, str]:
    """真的建一个 CUDA 会话跑一次，确认 GPU **确实可用**；返回 (是否可用, 说明)。

    <h2>为什么非要做这一层（血泪教训，别删）</h2>
    `get_available_providers()` **不代表 CUDA 真的能用**：它列的是"这个轮子编译时
    注册了哪些执行器"，而不是"这些执行器的动态库现在能不能被加载"。
    实测（2026-10，Ubuntu 22.04 / Python 3.12 / onnxruntime-gpu 1.31.0）：

        available providers: ['TensorrtExecutionProvider','CUDAExecutionProvider','CPUExecutionProvider']
        InferenceSession(..., providers=['CUDAExecutionProvider'])
        → session.get_providers() == ['CPUExecutionProvider']      # 静默掉回 CPU！
        stderr: Failed to load libonnxruntime_providers_cuda.so:
                libcublasLt.so.13: cannot open shared object file

    也就是说：**不报错、不抛异常、只是慢 3 倍**。用户只会觉得"开了 GPU 怎么没变快"。
    所以这里必须真建会话、真跑一次 forward，用 `session.get_providers()` 的结果说话。

    <h2>模型怎么来的</h2>
    不依赖 `onnx` 包（那只是构建工具，不该进运行依赖），直接用 protobuf 的
    动态描述符按 onnx.proto 的字段号手搓一个 97 字节的 `Relu` 单节点模型。
    字段号：ModelProto{ir_version=1,graph=7,opset_import=8}、GraphProto{node=1,name=2,input=11,output=12}、
    NodeProto{input=1,output=2,name=3,op_type=4}、ValueInfoProto{name=1,type=2}、
    TypeProto{tensor_type=1}、TypeProto.Tensor{elem_type=1,shape=2}、TensorShapeProto{dim=1}、Dim{dim_value=1}。
    实测这套字段号能被 onnxruntime 1.31 正常加载（ir_version=7 / opset=17）。
    protobuf 本身是 onnxruntime 的硬依赖，一定在，所以不会引入新的缺依赖风险。
    """
    try:
        import uuid

        from google.protobuf import descriptor_pb2, descriptor_pool, message_factory
    except Exception as exc:
        return False, "protobuf 动态描述符不可用：%s" % exc

    try:
        F = descriptor_pb2.FieldDescriptorProto
        pkg = "qaocrprobe" + uuid.uuid4().hex[:8]
        fdp = descriptor_pb2.FileDescriptorProto()
        fdp.name, fdp.package, fdp.syntax = pkg + ".proto", pkg, "proto3"

        def add(msg, name, num, typ, label=1, type_name=None):
            f = msg.field.add()
            f.name, f.number, f.type, f.label = name, num, typ, label
            if type_name:
                f.type_name = type_name

        def new_msg(name):
            m = fdp.message_type.add()
            m.name = name
            return m

        m = new_msg("Dim"); add(m, "dim_value", 1, F.TYPE_INT64)
        m = new_msg("TensorShape"); add(m, "dim", 1, F.TYPE_MESSAGE, 3, "." + pkg + ".Dim")
        m = new_msg("TensorType")
        add(m, "elem_type", 1, F.TYPE_INT32)
        add(m, "shape", 2, F.TYPE_MESSAGE, 1, "." + pkg + ".TensorShape")
        m = new_msg("Type"); add(m, "tensor_type", 1, F.TYPE_MESSAGE, 1, "." + pkg + ".TensorType")
        m = new_msg("ValueInfo")
        add(m, "name", 1, F.TYPE_STRING)
        add(m, "type", 2, F.TYPE_MESSAGE, 1, "." + pkg + ".Type")
        m = new_msg("Node")
        add(m, "input", 1, F.TYPE_STRING, 3)
        add(m, "output", 2, F.TYPE_STRING, 3)
        add(m, "name", 3, F.TYPE_STRING)
        add(m, "op_type", 4, F.TYPE_STRING)
        m = new_msg("Graph")
        add(m, "node", 1, F.TYPE_MESSAGE, 3, "." + pkg + ".Node")
        add(m, "name", 2, F.TYPE_STRING)
        add(m, "input", 11, F.TYPE_MESSAGE, 3, "." + pkg + ".ValueInfo")
        add(m, "output", 12, F.TYPE_MESSAGE, 3, "." + pkg + ".ValueInfo")
        m = new_msg("OpsetId"); add(m, "version", 2, F.TYPE_INT64)
        m = new_msg("Model")
        add(m, "ir_version", 1, F.TYPE_INT64)
        add(m, "graph", 7, F.TYPE_MESSAGE, 1, "." + pkg + ".Graph")
        add(m, "opset_import", 8, F.TYPE_MESSAGE, 3, "." + pkg + ".OpsetId")

        pool = descriptor_pool.DescriptorPool()
        pool.Add(fdp)
        Model = message_factory.GetMessageClass(pool.FindMessageTypeByName(pkg + ".Model"))
        model = Model()
        model.ir_version = 7
        model.opset_import.add().version = 17
        model.graph.name = "qa_ocr_probe"
        for name, container in (("x", model.graph.input), ("y", model.graph.output)):
            vi = container.add()
            vi.name = name
            vi.type.tensor_type.elem_type = 1          # FLOAT
            for dim in (1, 3, 32, 32):
                vi.type.tensor_type.shape.dim.add().dim_value = dim
        node = model.graph.node.add()
        node.input.append("x")
        node.output.append("y")
        node.op_type = "Relu"
        model_bytes = model.SerializeToString()
    except Exception as exc:
        return False, "构造自检模型失败：%s" % exc

    try:
        options = onnxruntime.SessionOptions()
        options.log_severity_level = 3                # 只保留 error，避免正常情况刷屏
        session = onnxruntime.InferenceSession(
            model_bytes, sess_options=options,
            providers=["CUDAExecutionProvider", "CPUExecutionProvider"])
        active = list(session.get_providers())
    except Exception as exc:
        return False, "%s: %s" % (type(exc).__name__, str(exc).splitlines()[0][:200])

    if not active or active[0] != "CUDAExecutionProvider":
        return False, "会话实际执行器为 %s（CUDA 运行库未加载成功）" % (active or ["?"])
    return True, "会话执行器 %s" % active[0]


def _preload_cuda_libs(dirs: list[str]) -> tuple[int, list[str]]:
    """Linux 专用：把 nvidia 目录下的 .so 用 `ctypes.CDLL(..., RTLD_GLOBAL)` 预加载。

    <h2>为什么不能靠 LD_LIBRARY_PATH（实测结论，别改成只设环境变量）</h2>
    在 Python 进程里执行 `os.environ["LD_LIBRARY_PATH"] = ...` **完全无效**：
    LD_LIBRARY_PATH 是**动态链接器在进程启动时**读一次的，程序跑起来之后再改
    环境变量，链接器不会重新读。实测（Ubuntu 22.04 / Python 3.12 / onnxruntime-gpu 1.31.0）：

        在 shell 里 export LD_LIBRARY_PATH=site-packages/nvidia/*/lib  → GPU_ACTIVE
        在 Python 里 os.environ["LD_LIBRARY_PATH"]=...                 → 仍报
            libonnxruntime_providers_cuda.so: libcublasLt.so.13: cannot open shared object file

    Windows 那边没有这个问题，因为 onnxruntime 的 EP 加载**确实**走 PATH
    （`os.add_dll_directory()` 反倒无效），两边机制不同，别互相套用。

    <h2>为什么 ctypes 预加载有效</h2>
    用 `RTLD_GLOBAL` 把库 dlopen 进来后，其 SONAME 就登记进了进程的全局符号表，
    之后 onnxruntime 再 `dlopen("libonnxruntime_providers_cuda.so")` 时，
    `libcublasLt.so.13` 作为**已加载的 SONAME**被直接复用，不再去磁盘搜索 —— 于是成功。
    实测：不设任何环境变量，仅预加载 20 个 .so，`session.get_providers()`
    就从 `['CPUExecutionProvider']` 变成 `['CUDAExecutionProvider','CPUExecutionProvider']`。

    <h2>返回</h2>
    (成功加载的库数, 失败清单)；失败不致命 —— 脚本正常路径会先 export LD_LIBRARY_PATH，
    那种情况下这里全部跳过；只有用户"自己 pip 装完直接跑脚本"时才依赖这条兜底。
    """
    import ctypes

    loaded: set[str] = set()
    failures: list[str] = []
    for directory in dirs:
        for path in sorted(glob.glob(os.path.join(directory, "*.so*"))):
            base = os.path.basename(path)
            if base in loaded:
                continue
            try:
                ctypes.CDLL(path, mode=ctypes.RTLD_GLOBAL)
                loaded.add(base)
            except OSError as exc:
                failures.append("%s: %s" % (base, str(exc).splitlines()[0][:120]))
    return len(loaded), failures


def configure_gpu(use_cuda: bool) -> dict:
    """在**导入 onnxruntime 之前**决定能否用 GPU，并准备好搜索路径。

    @param use_cuda 是否希望用 GPU（False 时直接返回，不做任何探测）
    @return 形如 {"enabled": bool, "providers": [...], "reason": str} 的说明
    """
    if not use_cuda:
        return {"enabled": False, "providers": [], "reason": "未开启（--use-gpu off）"}

    cuda_preload: tuple[int, list[str]] = (0, [])
    dirs = _nvidia_dll_dirs()
    if dirs:
        if os.name == "nt":
            # Windows：onnxruntime 加载 EP 走 PATH（os.add_dll_directory 实测无效）
            os.environ["PATH"] = os.pathsep.join(dirs + [os.environ.get("PATH", "")])
        else:
            # Linux：**设 LD_LIBRARY_PATH 没用**（实测），见 _preload_cuda_libs 的注释。
            # 这里同时设一份，只为让本进程之后 fork 出去的子进程也带上，真正起作用的是预加载。
            os.environ["LD_LIBRARY_PATH"] = os.pathsep.join(
                dirs + [os.environ.get("LD_LIBRARY_PATH", "")])
            cuda_preload = _preload_cuda_libs(dirs)

    try:
        import onnxruntime
    except Exception as exc:                       # 没装 onnxruntime，交给调用方报缺依赖
        return {"enabled": False, "providers": [], "reason": "onnxruntime 不可用：%s" % exc}

    try:
        providers = list(onnxruntime.get_available_providers())
        device = onnxruntime.get_device()
    except Exception as exc:
        return {"enabled": False, "providers": [], "reason": "查询执行器失败：%s" % exc}

    if "CUDAExecutionProvider" not in providers:
        # 装的是 CPU 版 onnxruntime（PyPI 上的 onnxruntime，其 CUDA 版叫 onnxruntime-gpu）
        return {
            "enabled": False,
            "providers": providers,
            "reason": "当前 onnxruntime 无 CUDA 执行器（版本 %s）；"
                      "要提速请装 onnxruntime-gpu" % getattr(onnxruntime, "__version__", "?"),
        }
    if device != "GPU":
        return {"enabled": False, "providers": providers, "reason": "onnxruntime 报告设备为 %s" % device}

    # 走到这里还不能说"能用 GPU"：见 _cuda_session_selftest 的注释，
    # get_available_providers() 在 CUDA 运行库缺失时**照样**列出 CUDAExecutionProvider。
    ok, detail = _cuda_session_selftest(onnxruntime)
    if not ok:
        return {
            "enabled": False,
            "providers": providers,
            "reason": "CUDA 执行器加载失败，已按 CPU 运行：%s" % detail,
        }

    if os.name == "nt":
        note = "nvidia 运行库目录 %d 个" % len(dirs)
    else:
        note = "nvidia 库目录 %d 个、预加载 %d 个 .so" % (len(dirs), cuda_preload[0])
    return {
        "enabled": True,
        "providers": providers,
        "reason": "CUDA 可用并已建会话验证（%s）" % note,
    }


def build_engine_params(gpu_enabled: bool, max_side_len: int, use_cls: bool) -> dict:
    """组装 RapidOCR 的引擎参数。

    <h2>三个参数各自的依据（都是实测值，别凭感觉改）</h2>
      * `use_cuda`：实测同一份 38 页扫描件，CPU 65.3 s → GPU 20.2 s（**3.2 倍**），
        识别结果逐页相似度 99.96%（33/38 页完全一致）。
      * `max_side_len` 2000 → 1280：实测识别耗时 2.21 → 1.55 s/页（**-30%**）而
        **字数不减反略增**（756→759）。降到 960 会掉字（725），所以 1280 是拐点。
        它管的是"位图送进检测网络前压到多长边"，200 DPI 的 A4 是 1654×2481，
        压到 1280 后检测网络负担明显下降。
      * `use_cls=False`：关闭文本行方向分类（判断是否 180° 倒置）。本项目处理的
        都是正立扫描件，这步是白跑，实测省约 7%。**代价**：倒置/横向的扫描件可能识别不出，
        文档里已写明"请先把扫描件转正"。
    """
    params = {
        "Global.max_side_len": int(max_side_len),
        "Global.use_cls": bool(use_cls),
    }
    if gpu_enabled:
        params["EngineConfig.onnxruntime.use_cuda"] = True
    return params


def is_noise(text: str) -> bool:
    """判噪点行：极短且不含任何中文或数字。"""
    stripped = text.strip()
    if len(stripped) > NOISE_MAX_LEN:
        return False
    return not any(ch.isdigit() or "\u4e00" <= ch <= "\u9fff" for ch in stripped)


def normalize(text: str) -> str:
    """轻量规整：去行内多余空白。

    刻意**不做**语义改写、不做同义词替换 —— 这是"资料"，必须忠实于原件；
    改写会引入幻觉，而幻觉正是本项目最想避免的东西。
    """
    return " ".join(text.split())


# 内嵌图片的过滤阈值。
#
# 为什么必须过滤：PDF 里的"图片"不全是内容 —— 图标、公司 logo、分隔线、
# 页眉的装饰图形都会以图片对象存在，识别出来只会是一两个噪点字符。
#
# ⚠️ 但门槛**不能设高**：小图恰恰是本功能唯一有价值的场景。
#    实测两轮才定下这两个值：
#      初版 80px / 40000px² —— 把 150×40（含一行铭牌号）、300×50 这类
#      "小尺寸但有字"的图全部挡掉了，而整页 200 DPI 渲染对它们又确实读不出来
#      （放大余量只有 2.78 倍），结果功能形同虚设。
#      改成 24px / 2000px² 后，真实图标（16×16、32×32 的 logo 与符号）
#      仍被挡在门外，而"小而有字"的图能进入识别。
DEFAULT_MIN_IMAGE_SIDE = 24       # 任一边小于此像素 → 判为图标/装饰，跳过
DEFAULT_MIN_IMAGE_PIXELS = 2000   # 总面积小于此 → 跳过（约 45×45）
TARGET_RENDER_SIDE = 1500         # 长边小于此的图放大到该尺寸附近，识别更稳
DEFAULT_MAX_IMAGES = 60           # 单次最多处理多少张内嵌图片（防超大文档跑不完）

# 内嵌图识别出的文字，若与整页识别结果重复到这个比例，就整块丢弃。
#
# 这是实测两轮才定下来的判据：
#   第一轮按"整页字数是否够多"来跳（阈值 50 字）——失败。实测一份文字层页面
#   整页只读出 48 字，刚好低于阈值，于是同一张截图被识别两遍；而另一轮
#   200 DPI 的整页渲染其实已把插图文字读得很准（`dpi`/`200`），
#   逐图放大到 1500px 反而更差（`! d p`/全角 `２00`）。
#   第二轮改成**按内容比对**：图里识别出的行若大多已出现在整页结果里，
#   说明这份内容已经有了，再放一遍只会重复并引入错字。
#
# 用内容判据而不是字数阈值，是因为"整页读没读到"跟字数没有稳定关系 ——
# 一页可能只有 40 个字但每个都对，也可能有 300 个字却全是噪点。
IMAGE_DUP_RATIO = 0.7


def render_image(doc, xref, min_side, min_pixels):
    """把 PDF 内嵌图片渲染成识别用的 PNG 字节。

    <h2>为什么小图要放大</h2>
    内嵌图片的分辨率差异极大：一张 30000×2000 的长截图直接丢给识别引擎既慢又容易被
    内部缩放糊掉；一张 150×120 的小图则字太细、检测不到。所以按"长边"归一：
    长边不足 {@link TARGET_RENDER_SIDE} 的图放大到该尺寸附近，够大的图原样使用。

    <h2>为什么用"临时页重绘"而不是 Pixmap 自己的缩放</h2>
    PyMuPDF 1.28 的 {@code Pixmap} 没有通用的重采样接口 —— 实测：
      * {@code pix * Matrix}  → TypeError（Pixmap 不支持与 Matrix 相乘）
      * {@code pix.warp(Matrix, w, h)} → AttributeError（要求 Quad，不是 Matrix）
      * {@code pix.warp(Quad(...), w, h)} → ValueError: quad must be convex
      * {@code pix.shrink(n)}  → 只能整数倍**缩小**，且放大时返回 None
    最可靠的通用做法是：把图插进一个临时页，再用 {@code get_pixmap(matrix=...)}
    按任意倍率渲染 —— 这条路径是 MuPDF 自己的渲染器，缩放质量也最好。
    代价是每张图多一次内存渲染，相对识别本身的耗时可以忽略。

    @param doc        PyMuPDF 文档
    @param xref       图片对象号
    @param min_side   任一边小于此像素 → 判为图标/装饰，返回 None
    @param min_pixels 总面积小于此 → 同样跳过
    @return PNG 字节，或 None（尺寸不达标 / 读取失败）
    """
    import pymupdf

    try:
        pix = pymupdf.Pixmap(doc, xref)
        # CMYK / 带 alpha 的图先转成 RGB：识别引擎只吃常规三通道位图
        if pix.n - pix.alpha >= 4:
            pix = pymupdf.Pixmap(pymupdf.csRGB, pix)
        w, h = pix.width, pix.height

        if min(w, h) < min_side or w * h < min_pixels:
            return None

        if max(w, h) >= TARGET_RENDER_SIDE:
            return pix.tobytes("png")

        factor = min(TARGET_RENDER_SIDE / float(max(w, h)), 4.0)
        tmp = pymupdf.open()
        try:
            page = tmp.new_page(width=w, height=h)
            page.insert_image(pymupdf.Rect(0, 0, w, h), pixmap=pix)
            out = page.get_pixmap(matrix=pymupdf.Matrix(factor, factor), alpha=False)
            return out.tobytes("png")
        finally:
            tmp.close()
    except Exception:
        return None


def recognize_bitmap(engine, png_bytes, min_confidence):
    """对一张位图跑识别，返回 (保留的行列表, 各行的置信度列表)。

    与整页识别共用同一套过滤（置信度阈值 + 噪点判定），
    保证"页内文字"和"插图文字"的质量标准一致。
    """
    try:
        result = engine(png_bytes)
    except Exception:
        return [], []
    txts = getattr(result, "txts", None)
    if txts is None:
        return [], []
    texts = [str(t) for t in txts]
    raw_scores = getattr(result, "scores", None)
    scores = [float(s) for s in raw_scores] if raw_scores is not None else []

    kept, kept_scores = [], []
    for i, text in enumerate(texts):
        score = scores[i] if i < len(scores) else 1.0
        if score < min_confidence:
            continue
        normalized = normalize(text)
        if normalized and not is_noise(normalized):
            kept.append(normalized)
            kept_scores.append(score)
    return kept, kept_scores


def image_covers_page(page, xref, ratio_threshold: float) -> bool:
    """判断这张内嵌图是不是"整页扫描图"（铺满整个页面）。

    <h2>为什么必须判这个（实测数据，省下一半时间）</h2>
    扫描件 PDF 的典型结构是：**每页一张大图，就是这一页的扫描件本身**，
    页面上没有别的元素。实测用户那份 38 页 PDF：每页 1 张 1190×1683 的图，
    在页面上占 **100%** 面积。

    这种情况下"逐页渲染 + 识别"（主流程）已经把整页内容完整读过了，
    再把这些图抽出来放大识别一遍是**纯重复劳动** —— 实测那一遍花了 **62 秒
    （占 46% 时间）**，而 38 张里 **37 张被判重丢弃、只有 1 张真有字**，
    最终字数只差 3%（24,954 vs 25,745）。GPU 下这一遍同样要花约 17 秒。

    <h2>为什么不能干脆关掉内嵌图识别</h2>
    这条通道是为**另一种情况**存在的：正文是文字层，中间插了一张小截图/表格照片，
    整页渲染时那部分字太细检测不到（详见 extract_page_images 的注释）。
    那种图**只占页面一小块**，不会达到这里 80% 的阈值。

    所以判据是"占页面多大"而不是"要不要识别" —— **能力一点没减，只是不再对自己重复**。

    @param page            PyMuPDF 页面
    @param xref            图片对象号
    @param ratio_threshold 面积占比阈值，超过即判为整页图
    @return True 表示这是整页图，应跳过单独识别
    """
    try:
        page_area = page.rect.width * page.rect.height
        if page_area <= 0:
            return False
        rects = page.get_image_rects(xref)
        if not rects:
            # 拿不到显示区域（图被裁剪或变换）——保守起见当作"不是整页图"，
            # 宁可多识别一次，也不要漏掉内容。
            return False
        for rect in rects:
            if rect.width * rect.height >= page_area * ratio_threshold:
                return True
        return False
    except Exception:
        # 任何异常都退回"不跳过"：保守方向与上面一致
        return False


def extract_page_images(doc, engine, args, page_texts_by_index=None):
    """抽取内嵌图片并识别其中的文字，**丢掉与整页结果重复的部分**。

    <h2>为什么需要这一步</h2>
    主流程是"整页渲染 → 文字检测"。实测在 200 DPI 下它通常已经能读出页面上
    插图里的字；但有两种情况会漏：
      * 图很小或被缩放过，整页渲染后那部分字太细，检测不到；
      * 页面主体是模糊扫描件（低对比、倾斜），而插在其中的截图反而清晰。
    这时按图单独抽出来识别，才能把那部分文字捞回来。

    <h2>三道过滤，各有各的依据</h2>
    1. **尺寸过滤**（min_image_side / min_image_pixels）：图标、logo、分隔线不是内容。
    2. **整页图过滤**（`--page-image-ratio`，新增）：图占页面 ≥80% 面积时，
       它就是这一页的扫描件，主流程刚读过 —— 见 image_covers_page 的实测数据。
       这一道把"扫描件 PDF"的时间砍掉近一半。
    3. **内容判重**（IMAGE_DUP_RATIO）：即便通过了前两道，图里识别出的行若大多
       已出现在该页整页结果里，整块丢弃。**这一道是兜底，不是主力** ——
       实测它在扫描件上要"识别完才发现是重复"，钱已经花掉了。

    @param page_texts_by_index 页码 → 该页整页识别文本（用于去重）
    @return (图片段落列表, 纳入识别的图片数, 其中有文字的图片数, 置信度列表, 去重丢弃数)
    """
    page_texts_by_index = page_texts_by_index or {}
    blocks: list[str] = []
    confidences: list[float] = []
    considered = 0
    with_text = 0
    duplicates = 0
    skipped_full_page = 0

    for index in range(doc.page_count):
        if considered >= args.max_images:
            break
        try:
            page = doc.load_page(index)
            images = page.get_images(full=True)
        except Exception:
            continue

        page_text = page_texts_by_index.get(index, "")
        for seq, info in enumerate(images, start=1):
            if considered >= args.max_images:
                break

            # 第 2 道：整页扫描图跳过（主流程已读过，别重复花钱）
            if image_covers_page(page, info[0], args.page_image_ratio):
                skipped_full_page += 1
                continue

            png_bytes = render_image(doc, info[0], args.min_image_side, args.min_image_pixels)
            if png_bytes is None:
                continue                       # 尺寸太小，判为图标/装饰

            considered += 1
            kept, kept_scores = recognize_bitmap(engine, png_bytes, args.min_confidence)
            if not kept:
                continue

            # 第 3 道：按内容判重：这一行在整页结果里出现过就算重复。
            # 用"包含"而不是"相等"——整页结果里同一行可能被切分或带前后缀。
            fresh = [line for line in kept if line not in page_text]
            if len(fresh) < max(1, int(len(kept) * (1 - IMAGE_DUP_RATIO))):
                duplicates += 1
                continue                       # 增量太小，整块丢弃

            with_text += 1
            confidences.extend(kept_scores)
            blocks.append("[第 %d 页·插图 %d]\n%s" % (index + 1, seq, "\n".join(fresh)))

    if skipped_full_page:
        report({"stage": "images", "skippedFullPage": skipped_full_page,
                "considered": considered, "duplicates": duplicates}, args)

    return blocks, considered, with_text, confidences, duplicates


def dedup_repeated_lines(lines: list[str]) -> list[str]:
    """连续重复行只保留一行。

    页眉/页脚/水印会在每一页被识别出来；40 页的文档就是 40 遍噪声，
    既稀释检索质量又白占 token。只处理**连续**重复，避免误删正文里的正常重复。
    """
    out: list[str] = []
    run_text = None
    run_count = 0
    for line in lines:
        if line == run_text:
            run_count += 1
            if run_count < DEDUP_RUN:
                out.append(line)
        else:
            run_text = line
            run_count = 1
            out.append(line)
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description="qa-assistant 本地离线 OCR")
    # 位置参数叫 input_path 而不是 pdf：图片（.jpg/.png/…）同样走这条路，
    # 名字、帮助文本、报错话术都必须对得上，否则用户看到"未提供 PDF 路径"
    # 会以为自己传错了格式。
    parser.add_argument("input_path", nargs="?", help="待识别的文件路径（PDF 或图片）")
    parser.add_argument("--check", action="store_true", help="只做依赖自检")
    parser.add_argument("--max-pages", type=int, default=100, help="页数上限，超出直接拒绝")
    parser.add_argument("--dpi", type=int, default=200, help="拆页渲染 DPI，越高越准也越慢")
    parser.add_argument("--min-confidence", type=float, default=0.5, help="低于此置信度的行丢弃")
    parser.add_argument("--preview-chars", type=int, default=500, help="回给 Java 的预览字数")
    parser.add_argument(
        "--use-gpu",
        choices=["on", "off", "auto"],
        default="auto",
        help="是否用 GPU 跑识别：auto=有 CUDA 就用（默认），off=强制 CPU，on=要求 GPU",
    )
    parser.add_argument(
        "--max-side-len",
        type=int,
        default=1280,
        help="送进检测网络的位图长边上限。默认 1280（实测比 2000 快 30%% 且不掉字）",
    )
    parser.add_argument(
        "--use-cls",
        choices=["on", "off"],
        default="off",
        help="是否判断文本行方向（180° 倒置）。默认 off —— 本项目处理正立扫描件，省约 7%%",
    )
    parser.add_argument(
        "--page-image-ratio",
        type=float,
        default=0.8,
        help="内嵌图占页面面积超过此比例时判为'整页扫描图'，整页那遍已读过，不再单独识别（默认 0.8）",
    )
    parser.add_argument(
        "--progress",
        choices=["on", "off"],
        default="on",
        help="是否在识别过程中往 stdout 打进度行（哨兵之外，Java 侧按哨兵取结果，不受影响）",
    )
    parser.add_argument(
        "--detect-images",
        choices=["on", "off"],
        default="on",
        help="是否额外识别页面内嵌图片里的文字（默认 on；用 off 可回到旧行为）",
    )
    parser.add_argument(
        "--min-image-side",
        type=int,
        default=DEFAULT_MIN_IMAGE_SIDE,
        help="内嵌图片任一边小于此像素则跳过（判为图标/装饰）",
    )
    parser.add_argument(
        "--min-image-pixels",
        type=int,
        default=DEFAULT_MIN_IMAGE_PIXELS,
        help="内嵌图片总面积小于此则跳过",
    )
    parser.add_argument(
        "--max-images",
        type=int,
        default=DEFAULT_MAX_IMAGES,
        help="单次最多处理多少张内嵌图片",
    )
    args = parser.parse_args()

    if args.check:
        return check_dependencies()

    started = time.time()

    if not args.input_path:
        return fail(EXIT_BAD_ARGS, "未提供文件路径（PDF 或图片）")
    if not os.path.isfile(args.input_path):
        return fail(EXIT_BAD_ARGS, "文件不存在：%s" % args.input_path)

    # 依赖缺失要单独报，与"识别失败"区分开：前者用户装一下就好，后者要查文件
    try:
        import pymupdf
    except Exception:
        return fail(EXIT_NO_DEPENDENCY, "缺少依赖 pymupdf，请执行：pip install pymupdf")
    try:
        from rapidocr import RapidOCR
    except Exception:
        return fail(EXIT_NO_DEPENDENCY, "缺少依赖 rapidocr，请执行：pip install rapidocr")

    # ---- 打开文件 ----
    # PyMuPDF 对 PDF 和图片是同一个入口：图片会变成"一页、文字层为空"的文档。
    # 所以下面这一整段逻辑不需要区分两者 —— 这也正是把图片纳入进来的成本
    # 几乎为零的原因（区别只在"用户传了什么"，不在"怎么处理"）。
    try:
        doc = pymupdf.open(args.input_path)
    except Exception as exc:
        return fail(EXIT_PDF_UNREADABLE, "文件打不开（可能已损坏，或图片格式不受支持）：%s" % exc)
    if doc.needs_pass:
        doc.close()
        return fail(EXIT_PDF_UNREADABLE, "该 PDF 有密码保护，请先解除密码后重传")

    total_pages = doc.page_count
    if total_pages == 0:
        doc.close()
        return fail(EXIT_PDF_UNREADABLE, "该文件没有任何页面（或图片内容为空）")

    # 页数上限在**应用层**挡：Python 侧若因页数过多 OOM，会被系统直接杀掉，
    # 那时 Java 只能看到一个退出码，给不出"请减少页数"这种可照做的提示。
    if total_pages > args.max_pages:
        doc.close()
        return fail(
            EXIT_TOO_MANY_PAGES,
            "该文件共 %d 页，超过上限 %d 页" % (total_pages, args.max_pages),
            totalPages=total_pages,
            maxPages=args.max_pages,
        )

    # ---- 初始化引擎（模型随包下发，此步不联网）----
    #  ⚠️ GPU 探测必须在 RapidOCR() **之前**做：onnxruntime 在导入时就注册执行器，
    #     而 CUDA 的依赖 DLL 需要提前出现在 PATH 上（原因见 _nvidia_dll_dirs 的注释）。
    gpu = configure_gpu(args.use_gpu in ("on", "auto"))
    if args.use_gpu == "on" and not gpu["enabled"]:
        doc.close()
        return fail(EXIT_INTERNAL, "要求用 GPU 但 CUDA 不可用：%s" % gpu["reason"])

    engine_params = build_engine_params(gpu["enabled"], args.max_side_len, args.use_cls == "on")
    report(
        {"stage": "engine", "gpu": gpu["enabled"], "gpuReason": gpu["reason"],
         "engine": "rapidocr", "maxSideLen": args.max_side_len, "useCls": args.use_cls == "on"},
        args,
    )
    try:
        engine = RapidOCR(params=engine_params)
    except Exception as exc:
        doc.close()
        return fail(EXIT_INTERNAL, "OCR 引擎初始化失败：%s" % exc)

    # ---- 逐页渲染 + 识别（主流程：页面上的文字）----
    page_texts: list[str] = []
    page_texts_by_index: dict[int, str] = {}
    confidences: list[float] = []
    pages_with_text = 0
    try:
        zoom = args.dpi / 72.0
        matrix = pymupdf.Matrix(zoom, zoom)
        for index in range(total_pages):
            page = doc.load_page(index)
            pix = page.get_pixmap(matrix=matrix, alpha=False)
            png_bytes = pix.tobytes("png")

            kept, kept_scores = recognize_bitmap(engine, png_bytes, args.min_confidence)
            if kept:
                pages_with_text += 1
                confidences.extend(kept_scores)
                joined = "\n".join(kept)
                page_texts.append("[第 %d 页]\n%s" % (index + 1, joined))
                page_texts_by_index[index] = joined
            # 逐页报进度：38 页要跑几十秒，没有反馈时用户会以为卡死
            report({"stage": "page", "page": index + 1, "total": total_pages,
                    "pagesWithText": pages_with_text}, args)
    except Exception as exc:
        doc.close()
        return fail(EXIT_INTERNAL, "识别过程中出错：%s" % exc)

    # ---- 内嵌图片识别（补充：整页渲染没读到的图内文字）----
    image_blocks: list[str] = []
    images_considered = 0
    images_with_text = 0
    image_duplicates = 0
    if args.detect_images == "on":
        try:
            (image_blocks, images_considered, images_with_text,
             image_scores, image_duplicates) = extract_page_images(
                doc, engine, args, page_texts_by_index
            )
            confidences.extend(image_scores)
        except Exception:
            # 图片识别失败**不能**让整次识别失败：页面文字已经拿到了，
            # 那才是主要产出。这里静默降级，imageCount=0 即体现。
            image_blocks, images_considered, images_with_text, image_duplicates = [], 0, 0, 0

    try:
        doc.close()
    except Exception:
        pass

    # 合并顺序：先页面文字、后插图文字。用户核对时是按页读的，
    # 插图内容跟在其所属文档之后，比穿插进页面中间更好核对。
    merged = "\n".join(page_texts + image_blocks)
    merged = "\n".join(dedup_repeated_lines(merged.split("\n"))).strip()

    if not merged:
        return fail(
            EXIT_EMPTY_RESULT,
            "未能从该文件中识别出文字"
            "（可能是纯图片、空白页，或置信度全部低于阈值；也可能图内文字尺寸过小）",
            totalPages=total_pages,
            imageCount=images_considered,
        )

    avg_confidence = round(sum(confidences) / len(confidences), 4) if confidences else None
    emit(
        {
            "ok": True,
            "engine": "rapidocr",
            "engineVersion": _package_version("rapidocr"),
            # 实际跑在 GPU 还是 CPU：用户反馈"慢"时，第一个要确认的就是这个。
            # 注意它是**实测结论**而不是配置请求 —— 装了 GPU 包但 CUDA 运行库没就位时，
            # onnxruntime 会静默回落到 CPU，只有看这个字段才能发现。
            "gpu": gpu["enabled"],
            "gpuReason": gpu["reason"],
            "totalPages": total_pages,
            "pagesWithText": pages_with_text,
            # 内嵌图片的统计：Java 侧用它向用户说明"这张图里有没有字"，
            # 而不是让用户对着空结果猜是文件问题还是功能没生效。
            "imageCount": images_considered,
            "imagesWithText": images_with_text,
            # 有多少张图识别出的内容与整页结果重复而被丢弃（可用来判断识别行为是否正常）
            "imageDuplicates": image_duplicates,
            "chars": len(merged),
            "avgConfidence": avg_confidence,
            "elapsedMs": int((time.time() - started) * 1000),
            "text": merged,
            "preview": merged[: args.preview_chars],
        }
    )
    return EXIT_OK


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(EXIT_INTERNAL)
    except Exception as exc:  # 兜底：任何未预期异常也要给出协议格式的输出
        sys.exit(fail(EXIT_INTERNAL, "未预期的内部错误：%s" % exc))
