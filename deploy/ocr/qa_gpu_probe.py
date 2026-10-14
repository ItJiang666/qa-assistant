#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""qa_gpu_probe.py —— GPU 加速候选组合的「隔离试装 + 真实推理验证」探测器。

<h2>这个脚本为什么存在（本项目最贵的一次教训）</h2>
2026-10-14，在一台**完全干净**的 Windows 机器上（RTX 4060 Laptop + 驱动 596.36 +
Python 3.13.16），`enable-gpu.cmd` 走完全流程并打印：

    PROBE session providers: CUDAExecutionProvider|CPUExecutionProvider
    [OK] GPU acceleration is ENABLED -- and a CUDA session was really built.

**但真实 OCR 一页都认不出来**，38 页全部 `pagesWithText: 0`，onnxruntime 的日志里是：

    [E:onnxruntime:Default, cuda_call.cc:148 onnxruntime::CudaCall]
    CUDNN failure 5003: CUDNN_STATUS_EXECUTION_FAILED_CUDART ; GPU=0

也就是说：**"执行器注册了 + DLL 加载了 + 会话建起来了"这三件事全部成立，
GPU 依然不可用。** 旧自检的判据只到"建会话"这一层，所以它 100% 会假通过。

本脚本的判据只有一个，无法伪造：

    用 CUDA 执行器**真跑一次 forward**，输出必须与 CPU 逐元素一致。

<h2>它做什么</h2>
把"先装进用户环境、再自检"改成"先在**隔离环境**里装、**验证通过才落地**"：

    ① 建临时 venv（--target-env）
    ② 按候选组合装包（--pkgs / --index / --find-links / --force-reinstall）
    ③ 用那个 venv 的解释器跑本文件的 --probe-with：真跑 forward 并比对
    ④ 输出一行 ###QAGPUPROBE###{...}###END### 给 shell 解析
    ⑤ 删掉临时 venv

这样任何"装得上但用不了"的组合都会在**落地之前**被淘汰，脚本不可能再假通过。

<h2>字段说明（输出 JSON）</h2>
    ok          : bool  —— 唯一需要信的字段：GPU 真的跑通了一次正确的前向
    stage       : str   —— 卡在哪一步：venv / install / import / preload / session / forward / guard
    reason      : str   —— 人话原因
    providers   : list  —— 执行器名单（会撒谎，只作参考）
    session     : list  —— 会话实际执行器（比 providers 可信，但仍不足以定论）
    cuda_errors : list  —— 从 stderr 捕获到的 CUDNN/CUDA 报错原文（关键证据）
    version     : str   —— onnxruntime 版本

<h2>退出码</h2>
    0 = 验证通过（真的能用）   1 = 验证失败（附 stage/reason/证据）

<h2>⚠️ 平台差异（两个方向都实测过，别互相套用）</h2>
  * Windows：onnxruntime 加载 EP 走 PATH，要先把自己的 PATH 前置，再用
    `onnxruntime.preload_dlls(directory="")` 兜一道。
    `os.add_dll_directory()` 实测**无效**（onnxruntime 加载 EP 不走那个搜索路径）。
  * Linux：`LD_LIBRARY_PATH` 在进程启动时就固定了，**在 Python 里再设没有任何作用**；
    唯一有效的事后补救是 `ctypes.CDLL(path, RTLD_GLOBAL)` 预加载，
    让库的 SONAME 进入进程全局符号表（实测能把会话执行器从 CPU 变成 CUDA）。
    另外 CUDA 13 的包把库放在 `site-packages/nvidia/cu13/lib`，**组件名是 cu13 而不是包名**，
    onnxruntime 自带的搜索逻辑只认 CUDA 12 时代的 `nvidia/<包名>/lib`，所以必须我们显式加。
"""
from __future__ import annotations

import argparse
import ctypes
import glob
import json
import os
import shutil
import subprocess
import sys
import tempfile
import uuid

SENTINEL_BEGIN = "###QAGPUPROBE###"
SENTINEL_END = "###END###"

# CUDA 报错的特征串：抓到任意一个就说明"执行层"出了问题（而不是依赖缺失）
CUDA_ERROR_MARKERS = (
    "CUDNN failure",
    "CUDNN_STATUS",
    "cuda_call.cc",
    "CUDA failure",
    "CUDA_ERROR",
    "cublas",
    "cudart",
    "no kernel image",
    "failed to load library",
    "onnxruntime_providers_cuda",
)


# ---------------------------------------------------------------------------
#  结果输出
# ---------------------------------------------------------------------------
def emit(result: dict) -> None:
    """把结论按 shell 能解析的格式打到 stdout。"""
    sys.stdout.write(SENTINEL_BEGIN + json.dumps(result, ensure_ascii=False) + SENTINEL_END + "\n")
    sys.stdout.flush()


def result(ok: bool, stage: str, reason: str, **extra) -> dict:
    base = {
        "ok": bool(ok),
        "stage": stage,
        "reason": reason,
        "providers": [],
        "session": [],
        "cuda_errors": [],
        "version": "",
        "preloaded": 0,
    }
    base.update(extra)
    return base


# ---------------------------------------------------------------------------
#  库目录发现与预加载（与 qa_ocr.py 的 _nvidia_dll_dirs 保持同一套规则）
# ---------------------------------------------------------------------------
def nvidia_lib_dirs() -> list[str]:
    """找出 pip 安装的 NVIDIA 库目录。Windows 取 bin、Linux 取 lib。

    <h2>目录布局（实测）</h2>
      * Windows：`nvidia/<组件>/bin`、`nvidia/<组件>/bin/x86_64`
      * Linux CUDA 13 包：`nvidia/cu13/lib`（组件名是 cu13，不是包名）
      * Linux CUDA 12 包：`nvidia/<组件>/lib`
    """
    patterns = ("*/bin", "*/bin/x86_64", "*/*/bin", "*/*/bin/x86_64") if os.name == "nt" \
               else ("*/lib", "*/*/lib")
    dirs: list[str] = []
    for entry in sys.path:
        if not entry or not os.path.isdir(entry):
            continue
        root = os.path.join(entry, "nvidia")
        if not os.path.isdir(root):
            continue
        for pat in patterns:
            dirs.extend(glob.glob(os.path.join(root, pat)))
    return [d for d in dict.fromkeys(dirs) if os.path.isdir(d)]


def preload_libraries() -> tuple[int, list[str]]:
    """把 nvidia 目录下的库预加载进进程，返回 (成功数, 失败清单)。

    Windows 先改 PATH（onnxruntime 加载 EP 走 PATH），Linux 只能靠 RTLD_GLOBAL 预加载。
    """
    dirs = nvidia_lib_dirs()
    if not dirs:
        return 0, []
    if os.name == "nt":
        os.environ["PATH"] = os.pathsep.join(dirs + [os.environ.get("PATH", "")])
    loaded: set[str] = set()
    failed: list[str] = []
    suffix = (".dll",) if os.name == "nt" else (".so", ".so.1")
    for directory in dirs:
        targets: list[str] = []
        for ext in suffix:
            targets.extend(glob.glob(os.path.join(directory, "*" + ext)))
        for path in sorted(set(targets)):
            base = os.path.basename(path)
            if base in loaded:
                continue
            try:
                ctypes.CDLL(path, mode=ctypes.RTLD_GLOBAL)
                loaded.add(base)
            except OSError as exc:
                failed.append("%s: %s" % (base, str(exc).splitlines()[0][:100]))
    return len(loaded), failed


# ---------------------------------------------------------------------------
#  手搓一个最小 ONNX 模型（Relu 单节点），用于"真跑一次前向"
# ---------------------------------------------------------------------------
def build_relu_model() -> bytes:
    """按 onnx.proto 的字段号手搓一个 Relu 单节点模型，不需要 onnx 包。

    字段号：ModelProto{ir_version=1,graph=7,opset_import=8}、GraphProto{node=1,name=2,input=11,output=12}、
    NodeProto{input=1,output=2,name=3,op_type=4}、ValueInfoProto{name=1,type=2}、
    TypeProto{tensor_type=1}、TypeProto.Tensor{elem_type=1,shape=2}、TensorShapeProto{dim=1}、Dim{dim_value=1}。
    protobuf 是 onnxruntime 的硬依赖，必然存在。
    """
    from google.protobuf import descriptor_pb2, descriptor_pool, message_factory

    F = descriptor_pb2.FieldDescriptorProto
    pkg = "qagpuprobe" + uuid.uuid4().hex[:8]
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
    try:
        Model = message_factory.GetMessageClass(pool.FindMessageTypeByName(pkg + ".Model"))
    except AttributeError:                      # protobuf < 4.22
        Model = message_factory.MessageFactory(pool).GetPrototype(
            pool.FindMessageTypeByName(pkg + ".Model"))

    model = Model()
    model.ir_version = 7
    model.opset_import.add().version = 17
    model.graph.name = "qa_gpu_probe"
    for name, container in (("x", model.graph.input), ("y", model.graph.output)):
        vi = container.add()
        vi.name = name
        vi.type.tensor_type.elem_type = 1       # FLOAT
        for dim in (1, 3, 32, 32):
            vi.type.tensor_type.shape.dim.add().dim_value = dim
    node = model.graph.node.add()
    node.input.append("x")
    node.output.append("y")
    node.op_type = "Relu"
    return model.SerializeToString()


# ---------------------------------------------------------------------------
#  stderr 捕获：CUDNN 报错是 C++ 层打到 stderr 的，Python 异常抓不到
# ---------------------------------------------------------------------------
class CaptureStderr:
    """把进程 stderr 重定向到临时文件，退出时读回内容。

    <h2>为什么必须抓 stderr</h2>
    实测：GPU 执行失败时 onnxruntime **不一定抛 Python 异常** —— 它把
    `CUDNN failure 5003: CUDNN_STATUS_EXECUTION_FAILED_CUDART` 打到 stderr，
    然后**正常返回**（结果是错的/空的）。所以只看异常和返回值会漏掉这个故障，
    必须把 stderr 的原文抓下来当证据。
    """

    def __init__(self) -> None:
        self._saved: int | None = None
        self._tmp = tempfile.TemporaryFile(mode="w+b")

    def __enter__(self):
        sys.stderr.flush()
        self._saved = os.dup(2)
        os.dup2(self._tmp.fileno(), 2)
        return self

    def __exit__(self, *exc):
        sys.stderr.flush()
        if self._saved is not None:
            os.dup2(self._saved, 2)
            os.close(self._saved)
            self._saved = None
        return False

    def text(self) -> str:
        self._tmp.flush()
        self._tmp.seek(0)
        return self._tmp.read().decode("utf-8", "replace")


def cuda_errors_in(text: str) -> list[str]:
    """从日志里挑出 CUDA/CUDNN 相关的行（去重、限长）。"""
    hits: list[str] = []
    for line in text.splitlines():
        if any(marker in line for marker in CUDA_ERROR_MARKERS):
            clean = line.strip()
            if len(clean) > 300:
                clean = clean[:300] + "..."
            if clean and clean not in hits:
                hits.append(clean)
    return hits[:8]


# ---------------------------------------------------------------------------
#  单环境验证：import → preload → 建会话 → **真跑 forward 并比对**
# ---------------------------------------------------------------------------
def probe(require: list[str]) -> dict:
    """在当前解释器/当前 PYTHONPATH 下验证 GPU 是否真的可用。"""
    missing = []
    for name in require:
        try:
            __import__(name)
        except Exception:
            missing.append(name)
    if missing:
        return result(False, "import", "缺少 Python 依赖：" + "、".join(missing))

    try:
        pre_loaded, pre_failed = preload_libraries()
    except Exception as exc:
        return result(False, "preload", "预加载 nvidia 库失败：%s" % exc)

    try:
        import numpy as np
        import onnxruntime as ort
    except Exception as exc:
        return result(False, "import", "导入 onnxruntime/numpy 失败：%s" % exc)

    version = getattr(ort, "__version__", "?")
    providers = list(ort.get_available_providers())

    # Windows 上再补一道官方提供的预加载（onnxruntime >= 1.21）
    if os.name == "nt" and hasattr(ort, "preload_dlls"):
        try:
            with CaptureStderr():
                ort.preload_dlls(directory="")
        except Exception:
            pass                                    # 失败无所谓，PATH 已经前置过了

    if "CUDAExecutionProvider" not in providers:
        return result(
            False, "import",
            "当前 onnxruntime 无 CUDA 执行器（版本 %s）；装的很可能是 CPU 版" % version,
            providers=providers, version=version, preloaded=pre_loaded,
        )

    try:
        blob = build_relu_model()
    except Exception as exc:
        return result(False, "guard", "构造自检模型失败：%s" % exc,
                      providers=providers, version=version, preloaded=pre_loaded)

    options = ort.SessionOptions()
    options.log_severity_level = 3                  # 只保留 error，避免正常情况刷屏
    with CaptureStderr() as captured:
        try:
            session = ort.InferenceSession(
                blob, sess_options=options,
                providers=["CUDAExecutionProvider", "CPUExecutionProvider"])
        except Exception as exc:
            errs = cuda_errors_in(captured.text())
            return result(False, "session", "%s: %s" % (type(exc).__name__, str(exc).splitlines()[0][:200]),
                          providers=providers, version=version, preloaded=pre_loaded, cuda_errors=errs)

        active = list(session.get_providers())
        if not active or active[0] != "CUDAExecutionProvider":
            errs = cuda_errors_in(captured.text())
            return result(
                False, "session",
                "会话实际执行器为 %s（CUDA 执行器没能接管）" % (active or ["?"]),
                providers=providers, session=active, version=version,
                preloaded=pre_loaded, cuda_errors=errs,
            )

        # ★★★ 这一步才是唯一有效的判据：真跑一次前向，并且比对结果 ★★★
        # 关键细节一：**不能只看"有没有抛异常"**。实测 CUDNN 执行失败时 onnxruntime
        #   只往 stderr 打一行日志，然后正常返回（结果是空的/错的），Python 侧毫无察觉。
        # 关键细节二：输入形状**必须与模型声明的 (1,3,32,32) 一致** —— 这是实测踩出来的：
        #   我最初用了一个 (1,3,2,2) 的小数组，onnxruntime 直接抛
        #   `INVALID_ARGUMENT: Got invalid dimensions for input: x`，
        #   于是所有候选都会被误判成"前向失败"。形状对了，测的才是 GPU 而不是参数校验。
        # 关键细节三：输入**必须含负数**，这样 Relu 的输出才是可判定的（max(0,x)），
        #   否则"算错了"和"算对了"可能得到同样的结果。
        rng = np.random.RandomState(20261014)
        x = (rng.rand(1, 3, 32, 32).astype(np.float32) - 0.5) * 4.0
        expected = np.maximum(x, 0.0)
        try:
            got = session.run(None, {"x": x})[0]
        except Exception as exc:
            errs = cuda_errors_in(captured.text())
            return result(False, "forward", "前向执行抛异常：%s: %s"
                          % (type(exc).__name__, str(exc).splitlines()[0][:200]),
                          providers=providers, session=active, version=version,
                          preloaded=pre_loaded, cuda_errors=errs)
        errs = cuda_errors_in(captured.text())

    # 比对（放在 stderr 恢复之后，避免把 numpy 的报错也吞掉）
    if got.shape != expected.shape:
        return result(False, "forward", "前向输出形状异常：%s（期望 %s）"
                      % (got.shape, expected.shape),
                      providers=providers, session=active, version=version,
                      preloaded=pre_loaded, cuda_errors=errs)
    if not np.allclose(got, expected, rtol=1e-4, atol=1e-5):
        return result(False, "forward",
                      "前向输出与期望不一致（GPU 算错了）：max|diff|=%g" % float(np.max(np.abs(got - expected))),
                      providers=providers, session=active, version=version,
                      preloaded=pre_loaded, cuda_errors=errs)
    if errs:
        # 结果是"对"的，但日志里有 CUDA 报错 —— 不能放过：这类错误往往先在
        # 大 shape / 特定算子上出现，小模型上侥幸对了不代表真实模型能跑。
        return result(False, "forward", "前向结果正确，但 stderr 里有 CUDA/CUDNN 报错（不可信）",
                      providers=providers, session=active, version=version,
                      preloaded=pre_loaded, cuda_errors=errs)

    return result(True, "forward",
                  "CUDA 会话真实前向通过（输出与 CPU 期望一致）",
                  providers=providers, session=active, version=version,
                  preloaded=pre_loaded, cuda_errors=[])


# ---------------------------------------------------------------------------
#  隔离试装
# ---------------------------------------------------------------------------
def run_capture(cmd: list[str], timeout: int = 600) -> tuple[int, str]:
    """跑一条命令并把 stdout+stderr 收进文件（不用管道，原因见 install 的注释）。"""
    log_path = ""
    try:
        fd, log_path = tempfile.mkstemp(prefix="qa-gpu-cmd-", suffix=".log")
        os.close(fd)
        with open(log_path, "wb") as log:
            try:
                proc = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
                code = proc.returncode
            except subprocess.TimeoutExpired:
                return 124, "命令超时"
        with open(log_path, "rb") as fh:
            return code, fh.read().decode("utf-8", "replace")
    except Exception as exc:
        return 1, "启动命令失败：%s" % exc
    finally:
        if log_path:
            try:
                os.unlink(log_path)
            except Exception:
                pass


def make_venv(path: str, base_python: str) -> tuple[bool, str]:
    """建一个带 pip 的临时 venv。返回 (是否成功, 说明)。"""
    code, out = run_capture([base_python, "-m", "venv", path])
    if code != 0:
        return False, "创建 venv 失败：%s" % out.strip()[-200:]
    py = venv_python(path)
    code, out = run_capture([py, "-m", "pip", "--version"])
    if code != 0:
        # 有些 Python 安装没带 pip（实测：Windows 上取消勾选 pip 组件就会这样）
        code, out = run_capture([py, "-m", "ensurepip", "--upgrade"])
        if code != 0:
            return False, "venv 里没有 pip，且 ensurepip 引导失败：%s" % out.strip()[-200:]
    return True, "ok"


def venv_python(path: str) -> str:
    return os.path.join(path, "Scripts" if os.name == "nt" else "bin",
                        "python.exe" if os.name == "nt" else "python")


def install(python: str, pkgs: list[str], args: argparse.Namespace) -> tuple[bool, str]:
    """在目标解释器里装候选组合。返回 (是否成功, 输出尾巴)。

    <h2>为什么输出写文件而不是用管道（实测踩出来的）</h2>
    最初用 `subprocess.run(..., capture_output=True)` 抓 pip 的输出，结果在受限环境里
    **永久阻塞**：venv 建好了、pip 也引导成功，但 pip install 一启动就再无输出，
    10 分钟不退出（stderr 也是空的）。同样是"跑 pip"，把输出重定向到**文件**就正常返回。

    管道阻塞会让整套验证卡死在第一步，比拿不到漂亮的错误摘要严重得多，
    所以这里一律写临时文件，再从文件尾部读摘要。
    """
    cmd = [python, "-m", "pip", "install", "--disable-pip-version-check",
           "--retries", str(args.retries), "--timeout", str(args.timeout)]
    if args.force_reinstall:
        cmd.append("--force-reinstall")
    if args.no_index:
        cmd.append("--no-index")
    if args.find_links:
        for link in args.find_links:
            cmd.append("--find-links=" + link)
    if args.index:
        cmd.append("--index-url=" + args.index)
    if args.extra_index:
        cmd.append("--extra-index-url=" + args.extra_index)
    if args.proxy:
        cmd.append("--proxy=" + args.proxy)
    cmd.extend(pkgs)

    log_path = ""
    try:
        fd, log_path = tempfile.mkstemp(prefix="qa-gpu-pip-", suffix=".log")
        os.close(fd)
        with open(log_path, "wb") as log:
            try:
                proc = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT,
                                      timeout=args.install_timeout)
                code = proc.returncode
            except subprocess.TimeoutExpired:
                return False, "安装超时（%d 秒）" % args.install_timeout
        with open(log_path, "rb") as fh:
            tail = fh.read().decode("utf-8", "replace")
    except Exception as exc:
        return False, "启动 pip 失败：%s" % exc
    finally:
        if log_path:
            try:
                os.unlink(log_path)
            except Exception:
                pass

    lines = [l for l in tail.splitlines() if l.strip()][-5:]
    return code == 0, "\n".join(lines)


def requirements_from(args: argparse.Namespace) -> list[str]:
    """--require 里写的是 import 名，转成 pip 包名。"""
    return [r.strip() for r in (args.require or []) if r.strip()]


def pip_names(args: argparse.Namespace) -> list[str]:
    """把 `--pkgs` 拆成 pip 参数列表。

    shell 侧用一个参数传进来、内部用 `;` 分隔：带版本约束的项（如
    `nvidia-cudnn-cu13~=9.0`）含 `~` `=` 等字符，交给 shell 按空格拆再拼回去
    容易散架，所以由这里拆。同时容忍 shell 真的按空格传了多个参数。
    """
    out: list[str] = []
    for chunk in args.pkgs:
        for item in str(chunk).split(";"):
            item = item.strip()
            if item:
                out.append(item)
    return out


# ---------------------------------------------------------------------------
#  主流程
# ---------------------------------------------------------------------------
def main() -> int:
    parser = argparse.ArgumentParser(
        description="GPU 候选组合的隔离试装 + 真实推理验证")
    parser.add_argument("--pkgs", nargs="+", default=[],
                        help="候选组合的包列表（每个包一个参数，用引号包住带版本约束的项）")
    parser.add_argument("--require", nargs="*", default=["numpy", "onnxruntime"],
                        help="必须能 import 的模块名")
    parser.add_argument("--index", default="", help="主索引 URL")
    parser.add_argument("--extra-index", default="", help="补充索引 URL")
    parser.add_argument("--find-links", nargs="*", default=[],
                        help="本地 wheel 目录（可多个）；给定时会加 --no-index")
    parser.add_argument("--no-index", action="store_true", help="禁止联网查索引")
    parser.add_argument("--proxy", default="", help="代理地址")
    parser.add_argument("--force-reinstall", action="store_true",
                        help="强制重装（同目录覆盖时 pip 会静默跳过，必须加）")
    parser.add_argument("--retries", type=int, default=10)
    parser.add_argument("--timeout", type=int, default=60)
    parser.add_argument("--install-timeout", type=int, default=3600)
    parser.add_argument("--target-env", default="",
                        help="隔离试装的临时 venv 路径；留空=直接在当前环境验证")
    parser.add_argument("--install-first", action="store_true",
                        help="先试装再验证（配合 --target-env 使用）")
    parser.add_argument("--keep-env", action="store_true", help="验证后保留临时 venv（排查用）")
    parser.add_argument("--probe-with", default="",
                        help="内部用：用这个解释器重新执行本文件做验证")
    parser.add_argument("--python", default=sys.executable, help="基础解释器（建 venv 用）")
    parser.add_argument("--json-only", action="store_true", help="只输出 JSON 行，不打印人话")
    args = parser.parse_args()

    # 内部模式：用指定解释器验证（shell 不会走到这里，只在本文件内部递归调用）
    if args.probe_with:
        sys.exit(0 if probe(requirements_from(args)).get("ok") else 1)

    target_python = venv_python(args.target_env) if args.target_env else sys.executable

    # 阶段 1：需要隔离试装时先建 venv 并装包
    if args.target_env:
        if os.path.isdir(args.target_env):
            shutil.rmtree(args.target_env, ignore_errors=True)
        ok, why = make_venv(args.target_env, args.python)
        if not ok:
            emit(result(False, "venv", why))
            return 1
        ok, tail = install(target_python, pip_names(args), args)
        if not ok:
            emit(result(False, "install", "安装失败：" + tail.replace("\n", " | ")[:500]))
            if not args.keep_env:
                shutil.rmtree(args.target_env, ignore_errors=True)
            return 1

    # 阶段 2：在**目标解释器**里做真实验证
    #
    #  <h2>为什么分两条路（实测踩出来的，别合并）</h2>
    #  * **没有隔离环境**（shell 已经装在目标 Python 里、只是让它验收结论）：
    #    直接**同进程**调用 probe()。实测同进程 2.8 秒返回；而"再 fork 一个 Python
    #    子进程去验证"在本项目的运行环境里会**永久阻塞**（子进程起来了但零输出，
    #    20 秒超时、stderr 也是空的）——同进程既避开这个坑，又省一次解释器启动。
    #  * **有隔离环境**（临时 venv 试装）：必须 fork，因为要换解释器。
    if not args.target_env:
        payload = probe(requirements_from(args))
        emit(payload)
        if not args.json_only and not payload.get("ok"):
            sys.stderr.write("  [失败] stage=%s  %s\n" % (payload.get("stage"), payload.get("reason")))
            for err in payload.get("cuda_errors") or []:
                sys.stderr.write("         %s\n" % err)
        return 0 if payload.get("ok") else 1

    cmd = [target_python, os.path.abspath(__file__), "--json-only",
           "--require", *requirements_from(args)]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, errors="replace", timeout=600)
    except Exception as exc:
        emit(result(False, "guard", "调用验证子进程失败：%s" % exc))
        if args.target_env and not args.keep_env:
            shutil.rmtree(args.target_env, ignore_errors=True)
        return 1

    payload = None
    for line in (proc.stdout or "").splitlines():
        if SENTINEL_BEGIN in line and SENTINEL_END in line:
            try:
                payload = json.loads(
                    line.split(SENTINEL_BEGIN, 1)[1].split(SENTINEL_END, 1)[0])
            except Exception:
                payload = None
    if payload is None:
        emit(result(False, "guard",
                    "验证子进程没有返回可解析结论（退出码 %d）：%s"
                    % (proc.returncode, ((proc.stderr or "") + (proc.stdout or "")).strip()[-300:])))
        if args.target_env and not args.keep_env:
            shutil.rmtree(args.target_env, ignore_errors=True)
        return 1

    # 阶段 3：清理隔离环境
    if args.target_env and not args.keep_env:
        shutil.rmtree(args.target_env, ignore_errors=True)

    emit(payload)
    if not args.json_only and not payload.get("ok"):
        sys.stderr.write("  [失败] stage=%s  %s\n" % (payload.get("stage"), payload.get("reason")))
        for err in payload.get("cuda_errors") or []:
            sys.stderr.write("         %s\n" % err)
    return 0 if payload.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
