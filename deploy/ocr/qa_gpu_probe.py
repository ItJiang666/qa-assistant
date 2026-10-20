#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""验证 GPU 是否真的能完成一次推理。

判据：用 CUDA 执行器跑一次 Relu 前向，输出必须与 CPU 计算一致。
只注册执行器、或只建起会话，都不算通过。

用法：python qa_gpu_probe.py [--require 模块名 ...] [--json]
退出码：0 = 可用，1 = 不可用。
"""
from __future__ import annotations

import argparse
import ctypes
import glob
import json
import os
import sys
import tempfile
import uuid

# stderr 中出现这些字样，说明是 CUDA 执行层失败，而非缺依赖
CUDA_MARKERS = (
    "CUDNN failure", "CUDNN_STATUS", "cuda_call.cc", "CUDA failure",
    "CUDA_ERROR", "no kernel image", "onnxruntime_providers_cuda",
)


def nvidia_lib_dirs() -> list[str]:
    """返回 pip 安装的 NVIDIA 库目录：Windows 取 bin，Linux 取 lib。"""
    patterns = ("*/bin", "*/bin/x86_64", "*/*/bin", "*/*/bin/x86_64") if os.name == "nt" \
               else ("*/lib", "*/*/lib")
    dirs: list[str] = []
    for entry in sys.path:
        root = os.path.join(entry, "nvidia") if entry else ""
        if not root or not os.path.isdir(root):
            continue
        for pattern in patterns:
            dirs.extend(glob.glob(os.path.join(root, pattern)))
    return [d for d in dict.fromkeys(dirs) if os.path.isdir(d)]


def preload() -> int:
    """把 NVIDIA 库加载进进程，返回成功数量。

    Windows 上 onnxruntime 通过 PATH 找执行器依赖；Linux 上 LD_LIBRARY_PATH
    在进程启动时已固定，只能靠 RTLD_GLOBAL 预加载。
    """
    dirs = nvidia_lib_dirs()
    if not dirs:
        return 0
    if os.name == "nt":
        os.environ["PATH"] = os.pathsep.join(dirs + [os.environ.get("PATH", "")])
    # Linux 的库文件名是 libxxx.so.13 这种带版本后缀的，只匹配 *.so 会一个都找不到
    pattern = "*.dll" if os.name == "nt" else "*.so*"
    loaded: set[str] = set()
    for directory in dirs:
        for path in sorted(glob.glob(os.path.join(directory, pattern))):
            name = os.path.basename(path)
            if name in loaded:
                continue
            try:
                ctypes.CDLL(path, mode=ctypes.RTLD_GLOBAL)
                loaded.add(name)
            except OSError:
                pass
    return len(loaded)


def build_model() -> bytes:
    """构造 Relu 单节点 ONNX 模型（1x3x32x32）。

    用 protobuf 动态描述符按 onnx.proto 字段号生成，避免依赖 onnx 包。
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

    def new(name):
        m = fdp.message_type.add()
        m.name = name
        return m

    m = new("Dim"); add(m, "dim_value", 1, F.TYPE_INT64)
    m = new("Shape"); add(m, "dim", 1, F.TYPE_MESSAGE, 3, "." + pkg + ".Dim")
    m = new("TensorType")
    add(m, "elem_type", 1, F.TYPE_INT32)
    add(m, "shape", 2, F.TYPE_MESSAGE, 1, "." + pkg + ".Shape")
    m = new("Type"); add(m, "tensor_type", 1, F.TYPE_MESSAGE, 1, "." + pkg + ".TensorType")
    m = new("ValueInfo")
    add(m, "name", 1, F.TYPE_STRING)
    add(m, "type", 2, F.TYPE_MESSAGE, 1, "." + pkg + ".Type")
    m = new("Node")
    add(m, "input", 1, F.TYPE_STRING, 3)
    add(m, "output", 2, F.TYPE_STRING, 3)
    add(m, "name", 3, F.TYPE_STRING)
    add(m, "op_type", 4, F.TYPE_STRING)
    m = new("Graph")
    add(m, "node", 1, F.TYPE_MESSAGE, 3, "." + pkg + ".Node")
    add(m, "name", 2, F.TYPE_STRING)
    add(m, "input", 11, F.TYPE_MESSAGE, 3, "." + pkg + ".ValueInfo")
    add(m, "output", 12, F.TYPE_MESSAGE, 3, "." + pkg + ".ValueInfo")
    m = new("Opset"); add(m, "version", 2, F.TYPE_INT64)
    m = new("Model")
    add(m, "ir_version", 1, F.TYPE_INT64)
    add(m, "graph", 7, F.TYPE_MESSAGE, 1, "." + pkg + ".Graph")
    add(m, "opset_import", 8, F.TYPE_MESSAGE, 3, "." + pkg + ".Opset")

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
    model.graph.name = "probe"
    for name, bucket in (("x", model.graph.input), ("y", model.graph.output)):
        vi = bucket.add()
        vi.name = name
        vi.type.tensor_type.elem_type = 1        # FLOAT
        for dim in (1, 3, 32, 32):
            vi.type.tensor_type.shape.dim.add().dim_value = dim
    node = model.graph.node.add()
    node.input.append("x")
    node.output.append("y")
    node.op_type = "Relu"
    return model.SerializeToString()


class StderrCapture:
    """把进程 stderr 重定向到临时文件，退出后读回。

    onnxruntime 的 CUDNN 报错写在 fd 2 上，Python 侧捕获不到；
    不抓这一段，执行失败会伪装成"没有结果"。
    """

    def __enter__(self):
        fd, self.path = tempfile.mkstemp(prefix="qa-probe-", suffix=".log")
        os.close(fd)
        self.handle = open(self.path, "wb")
        sys.stderr.flush()
        self.saved = os.dup(2)
        os.dup2(self.handle.fileno(), 2)
        return self

    def __exit__(self, *exc):
        sys.stderr.flush()
        os.dup2(self.saved, 2)
        os.close(self.saved)
        self.handle.close()
        return False

    def cuda_errors(self) -> list[str]:
        try:
            with open(self.path, "rb") as fh:
                text = fh.read().decode("utf-8", "replace")
        except OSError:
            return []
        finally:
            try:
                os.unlink(self.path)
            except OSError:
                pass
        hits: list[str] = []
        for line in text.splitlines():
            if any(marker in line for marker in CUDA_MARKERS):
                clean = line.strip()[:300]
                if clean and clean not in hits:
                    hits.append(clean)
        return hits[:3]


def probe(require: list[str]) -> dict:
    """执行一次验证并返回结论。"""
    missing = []
    for name in require:
        try:
            __import__(name)
        except Exception:
            missing.append(name)
    if missing:
        return {"ok": False, "stage": "import",
                "reason": "missing modules: " + ", ".join(missing)}

    loaded = preload()
    try:
        import numpy as np
        import onnxruntime as ort
    except Exception as exc:
        return {"ok": False, "stage": "import", "reason": "import failed: %s" % exc}

    version = getattr(ort, "__version__", "?")
    providers = list(ort.get_available_providers())
    base = {"version": version, "providers": providers, "preloaded": loaded}
    if "CUDAExecutionProvider" not in providers:
        return dict(base, ok=False, stage="provider",
                    reason="onnxruntime %s has no CUDA provider (CPU build?)" % version)

    try:
        model = build_model()
    except Exception as exc:
        return dict(base, ok=False, stage="model", reason="cannot build the probe model: %s" % exc)

    options = ort.SessionOptions()
    options.log_severity_level = 3
    with StderrCapture() as cap:
        try:
            session = ort.InferenceSession(
                model, sess_options=options,
                providers=["CUDAExecutionProvider", "CPUExecutionProvider"])
        except Exception as exc:
            return dict(base, ok=False, stage="session",
                        reason="%s: %s" % (type(exc).__name__,
                                           str(exc).splitlines()[0][:200]),
                        cuda_errors=cap.cuda_errors())

        active = list(session.get_providers())
        if not active or active[0] != "CUDAExecutionProvider":
            return dict(base, ok=False, stage="session", session=active,
                        reason="session provider is %s, CUDA did not take over" % (active or ["?"]),
                        cuda_errors=cap.cuda_errors())

        # 输入含负数，Relu 结果才有区分度
        x = (np.random.RandomState(20261014).rand(1, 3, 32, 32).astype(np.float32) - 0.5) * 4.0
        expect = np.maximum(x, 0.0)
        try:
            got = session.run(None, {"x": x})[0]
        except Exception as exc:
            return dict(base, ok=False, stage="forward", session=active,
                        reason="forward pass raised %s: %s"
                               % (type(exc).__name__, str(exc).splitlines()[0][:200]),
                        cuda_errors=cap.cuda_errors())
        errors = cap.cuda_errors()

    if got.shape != expect.shape:
        return dict(base, ok=False, stage="forward", session=active,
                    reason="forward output shape %s, expected %s" % (got.shape, expect.shape))
    if not np.allclose(got, expect, rtol=1e-4, atol=1e-5):
        return dict(base, ok=False, stage="forward", session=active,
                    reason="forward output differs from CPU, max|diff|=%g"
                           % float(np.max(np.abs(got - expect))),
                    cuda_errors=errors)
    if errors:
        return dict(base, ok=False, stage="forward", session=active,
                    reason="result matches but stderr shows CUDA errors", cuda_errors=errors)

    return dict(base, ok=True, stage="forward", session=active,
                reason="CUDA forward matches CPU", cuda_errors=[])


def main() -> int:
    parser = argparse.ArgumentParser(description="验证 GPU 能否完成一次推理")
    parser.add_argument("--require", nargs="*", default=["numpy", "onnxruntime"],
                        help="必须能导入的模块名")
    parser.add_argument("--json", action="store_true", help="输出 JSON")
    args = parser.parse_args()

    result = probe([r for r in args.require if r.strip()])
    if args.json:
        print(json.dumps(result, ensure_ascii=False))
    elif result["ok"]:
        print("[OK] GPU usable: onnxruntime %s, provider %s, %s"
              % (result["version"], result["session"][0], result["reason"]))
    else:
        print("[X] GPU not usable (%s): %s" % (result["stage"], result["reason"]))
        for line in result.get("cuda_errors") or []:
            print("    " + line)
        if result.get("stage") == "forward":
            print("    Driver may not match the installed CUDA runtime.")
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
