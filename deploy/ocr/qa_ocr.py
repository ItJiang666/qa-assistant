#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""qa-assistant 本地离线 OCR 脚本。

由 Java 侧以子进程方式调用：启动 -> 拆页 -> 识别 -> 输出 JSON -> 退出。
不常驻：OCR 是偶发重活，冷启动仅约 0.4 s。

输入可以是 PDF，也可以是单张图片（PyMuPDF 把图片当作只有一页的文档打开）。
引擎用 RapidOCR、拆页用 PyMuPDF，模型随 pip 包下发，全程离线。

输出协议：stdout 中出现一对哨兵，中间是单行 JSON
    ###QAOCR_BEGIN###{"ok":true,...}###QAOCR_END###

退出码：0 成功 / 2 依赖缺失 / 3 参数错误 / 4 页数超限
        5 文件打不开 / 6 结果为空 / 7 未预期的内部错误

用法：
    python qa_ocr.py --check
    python qa_ocr.py <文件路径> [--max-pages 50] [--dpi 200] [--min-confidence 0.5]
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

# CUDA/CUDNN 失败的特征串。抓到任意一个，就说明"执行层"坏了 —— 而这类失败
# **不会抛 Python 异常**，只往 stderr 打日志然后正常返回，所以必须主动抓 stderr。
CUDA_ERROR_MARKERS = (
    "CUDNN failure",
    "CUDNN_STATUS",
    "cuda_call.cc",
    "CUDA failure",
    "CUDA_ERROR",
    "no kernel image",
    "onnxruntime_providers_cuda",
)


class CaptureStderr:
    """临时把进程 stderr 重定向到文件，退出后可读回内容。

    onnxruntime 的 CUDNN 报错写在 fd 2 上，Python 异常捕获不到；
    不抓这一段，执行失败会伪装成「结果为空」。

    整个重定向是**尽力而为**的：句柄操作失败时静默跳过，绝不能让抓日志
    这件事把识别本身弄失败（Windows 上 dup2/close 的句柄语义与 POSIX 有差异）。
    """

    def __init__(self) -> None:
        self._saved: int | None = None
        self._path = ""
        self._chunks: list[bytes] = []
        self._handle = None
        self._active = False

    def __enter__(self):
        try:
            import tempfile

            fd, self._path = tempfile.mkstemp(prefix="qaocr-stderr-", suffix=".log")
            os.close(fd)
            self._handle = open(self._path, "wb")
            self._saved = os.dup(2)
            os.dup2(self._handle.fileno(), 2)
            self._active = True
        except Exception:
            self._active = False          # 抓不到日志不影响识别
        return self

    def __exit__(self, *exc):
        if self._active:
            # 先恢复 fd 2 再关句柄；每一步都单独兜住，任何一步失败都不能往上抛
            try:
                os.dup2(self._saved, 2)
            except Exception:
                pass
            try:
                os.close(self._saved)
            except Exception:
                pass
            self._saved = None
            self._active = False
        try:
            if self._handle is not None:
                self._handle.close()
        except Exception:
            pass
        try:
            with open(self._path, "rb") as fh:
                self._chunks.append(fh.read())
        except Exception:
            pass
        finally:
            try:
                os.unlink(self._path)
            except Exception:
                pass
        return False

    def cuda_errors(self) -> list[str]:
        """返回捕获到的 CUDA/CUDNN 报错行（去重、限长）。"""
        text = b"".join(self._chunks).decode("utf-8", "replace")
        hits: list[str] = []
        for line in text.splitlines():
            if any(marker in line for marker in CUDA_ERROR_MARKERS):
                clean = line.strip()
                if len(clean) > 300:
                    clean = clean[:300] + "..."
                if clean and clean not in hits:
                    hits.append(clean)
        return hits[:5]


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
    """输出一行进度 JSON（仅在 --progress on 时）。"""
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
    """返回 pip 安装的 NVIDIA 库目录：Windows 取 bin，Linux 取 lib。

    布局：Windows 为 nvidia/<组件>/bin[/x86_64]；Linux CUDA 13 为 nvidia/cu13/lib
    （组件名是 cu13），CUDA 12 为 nvidia/<组件>/lib。这些目录不在系统搜索路径上。
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
    """建一个 CUDA 会话并真跑一次前向，返回 (是否可用, 说明)。

    只查执行器名单或只建会话都不够：实测存在「执行器注册了、库加载了、会话也建起来了，
    但一跑推理就失败」的机器，浅层自检在那类机器上会假通过。
    输入含负数，Relu 输出才能与 CPU 结果逐元素比对。
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

    # ------------------------------------------------------------------
    #  ★ 光"建起会话"还不够，必须**真跑一次前向并比对结果** ★
    #
    #  <h2>为什么（2026-10-14 在干净机器上实测出来的，代价很大）</h2>
    #  一台完全干净的 Windows 机器（RTX 4060 Laptop + 驱动 596.36 + Python 3.13.16）：
    #
    #      执行器名单    : 含 CUDAExecutionProvider
    #      会话实际执行器: ['CUDAExecutionProvider', 'CPUExecutionProvider']   ← 看起来完美
    #      然后真实 OCR  : 38 页全部 0 字，日志里是
    #          [E:onnxruntime, cuda_call.cc] CUDNN failure 5003:
    #          CUDNN_STATUS_EXECUTION_FAILED_CUDART ; GPU=0
    #
    #  结论：**"执行器注册了 + DLL 加载了 + 会话建起来了"三件事全部成立，
    #  GPU 依然算不出东西。** 旧版自检只验到"建会话"，所以在干净环境里
    #  100% 假通过，把用户直接带进坑里。唯一无法伪造的判据是：跑一次、看结果对不对。
    #
    #  <h2>两个容易做错的细节</h2>
    #   ① 输入形状**必须与模型声明的 (1,3,32,32) 一致**。用 (1,3,2,2) 之类的小数组，
    #      onnxruntime 会抛 `INVALID_ARGUMENT: Got invalid dimensions`，
    #      于是所有环境都被误判成前向失败（这个错我犯过）。
    #   ② 输入**必须含负数**，Relu 的输出才是可判定的 max(0,x)；
    #      全正数输入下"算对了"和"算错了"可能给出同一结果。
    # ------------------------------------------------------------------
    try:
        import numpy as np
    except Exception as exc:
        return False, "numpy 不可用，无法做前向验证：%s" % exc

    rng = np.random.RandomState(20261014)
    x = (rng.rand(1, 3, 32, 32).astype(np.float32) - 0.5) * 4.0
    expected = np.maximum(x, 0.0)
    try:
        got = session.run(None, {"x": x})[0]
    except Exception as exc:
        return False, "前向执行抛异常：%s: %s" % (
            type(exc).__name__, str(exc).splitlines()[0][:200])

    if got.shape != expected.shape:
        return False, "前向输出形状异常：%s（期望 %s）" % (got.shape, expected.shape)
    if not np.allclose(got, expected, rtol=1e-4, atol=1e-5):
        return False, "前向输出与 CPU 期望不一致（GPU 算错了）：max|diff|=%g" % float(
            np.max(np.abs(got - expected)))

    return True, "会话执行器 %s，且真实前向结果与 CPU 一致" % active[0]


def _preload_cuda_libs(dirs: list[str]) -> tuple[int, list[str]]:
    """把 nvidia 目录下的 .so 以 RTLD_GLOBAL 预加载，返回 (成功数, 失败清单)。

    LD_LIBRARY_PATH 在进程启动时已固定，Python 内再设置无效，只能预加载：
    库的 SONAME 进入进程全局符号表后，onnxruntime 才能复用它们。
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
        # 措辞必须点到"真实前向"：只写"已建会话"会让人以为验过了，
        # 而 2026-10-14 的故障恰恰是"会话建得起来、前向算不出来"。
        "reason": "CUDA 可用且真实前向验证通过（%s；%s）" % (detail, note),
    }


def build_engine_params(gpu_enabled: bool, max_side_len: int, use_cls: bool) -> dict:
    """组装 RapidOCR 的引擎参数。

    use_cuda：GPU 实测比 CPU 快 3.2 倍，识别结果逐页相似度 99.96%。
    max_side_len=1280：比 2000 快 30% 且不丢字，降到 960 会开始掉字。
    use_cls=False：处理的都是正立扫描件，方向分类是白跑。
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
    """把 PDF 内嵌图片渲染成待识别用的 PNG 字节。"""
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
    """判断内嵌图是否就是整页扫描图。整页图会跳过，避免与页面识别重复。"""
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
    """识别内嵌图片里的文字，丢弃与页面结果重复的部分。

    返回 (文本块列表, 参与识别数, 有文字数, 置信度列表, 重复数)。
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
    # 全程捕获 stderr：GPU 执行失败**不抛异常**，只往这里打 CUDNN 报错。
    # 见 CaptureStderr 的注释 —— 不抓这一段，失败就会伪装成"这个文件没有文字"。
    stderr_capture = CaptureStderr()
    try:
        zoom = args.dpi / 72.0
        matrix = pymupdf.Matrix(zoom, zoom)
        with stderr_capture:
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
        # 空结果有两种完全不同的原因，必须分清 —— 这是 2026-10-14 那台干净机器
        # 用一整晚的排查换来的结论：明明是 GPU 执行失败，却报成"这个文件没有文字"，
        # 于是一直在查 PDF 和参数，而真正的问题在 CUDA 运行库。
        cuda_errors = stderr_capture.cuda_errors()
        if cuda_errors and gpu.get("enabled"):
            return fail(
                EXIT_EMPTY_RESULT,
                "GPU 推理失败，因此没能识别出文字：%s"
                "（驱动与 CUDA 运行库可能不匹配；可先用 --use-gpu off 走 CPU 验证文件本身没问题）"
                % cuda_errors[0],
                totalPages=total_pages,
                imageCount=images_considered,
                gpuError=cuda_errors,
            )
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
