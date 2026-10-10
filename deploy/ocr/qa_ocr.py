#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""qa-assistant 本地离线 OCR 脚本。

由 Java 侧（`RapidOcrEngine`）以子进程方式调用，**每次调用都是独立一次**：
启动 → 拆页 → 识别 → 输出 JSON → 退出。刻意不做常驻服务：

  * OCR 是偶发重活，常驻会一直占 200~500 MB 内存；
  * 子进程崩了不影响主进程，还能被超时强杀；
  * 冷启动只要 0.4 s，代价可以忽略。

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

## 退出码（Java 侧据此给不同提示，比解析文本可靠）

    0  成功
    2  依赖缺失（rapidocr / pymupdf 没装）
    3  参数错误（文件不存在、页范围非法）
    4  页数超限
    5  PDF 打不开（损坏或加密）
    6  识别结果为空
    7  未预期的内部错误

用法：
    python qa_ocr.py --check                       # 依赖自检（Java 侧用它判断可用性）
    python qa_ocr.py <pdf路径> [--max-pages 50] [--dpi 200] [--min-confidence 0.5]
"""

from __future__ import annotations

import argparse
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
    parser.add_argument("pdf", nargs="?", help="待识别的 PDF 路径")
    parser.add_argument("--check", action="store_true", help="只做依赖自检")
    parser.add_argument("--max-pages", type=int, default=50, help="页数上限，超出直接拒绝")
    parser.add_argument("--dpi", type=int, default=200, help="拆页渲染 DPI，越高越准也越慢")
    parser.add_argument("--min-confidence", type=float, default=0.5, help="低于此置信度的行丢弃")
    parser.add_argument("--preview-chars", type=int, default=500, help="回给 Java 的预览字数")
    args = parser.parse_args()

    if args.check:
        return check_dependencies()

    started = time.time()

    if not args.pdf:
        return fail(EXIT_BAD_ARGS, "未提供 PDF 路径")
    if not os.path.isfile(args.pdf):
        return fail(EXIT_BAD_ARGS, "文件不存在：%s" % args.pdf)

    # 依赖缺失要单独报，与"识别失败"区分开：前者用户装一下就好，后者要查文件
    try:
        import pymupdf
    except Exception:
        return fail(EXIT_NO_DEPENDENCY, "缺少依赖 pymupdf，请执行：pip install pymupdf")
    try:
        from rapidocr import RapidOCR
    except Exception:
        return fail(EXIT_NO_DEPENDENCY, "缺少依赖 rapidocr，请执行：pip install rapidocr")

    # ---- 打开 PDF ----
    try:
        doc = pymupdf.open(args.pdf)
    except Exception as exc:
        return fail(EXIT_PDF_UNREADABLE, "PDF 打不开（可能已损坏）：%s" % exc)
    if doc.needs_pass:
        doc.close()
        return fail(EXIT_PDF_UNREADABLE, "该 PDF 有密码保护，请先解除密码后重传")

    total_pages = doc.page_count
    if total_pages == 0:
        doc.close()
        return fail(EXIT_PDF_UNREADABLE, "该 PDF 没有任何页面")

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
    try:
        engine = RapidOCR()
    except Exception as exc:
        doc.close()
        return fail(EXIT_INTERNAL, "OCR 引擎初始化失败：%s" % exc)

    # ---- 逐页渲染 + 识别 ----
    page_texts: list[str] = []
    confidences: list[float] = []
    pages_with_text = 0
    try:
        zoom = args.dpi / 72.0
        matrix = pymupdf.Matrix(zoom, zoom)
        for index in range(total_pages):
            page = doc.load_page(index)
            pix = page.get_pixmap(matrix=matrix, alpha=False)
            png_bytes = pix.tobytes("png")

            result = engine(png_bytes)
            texts = []
            scores = []
            txts = getattr(result, "txts", None)
            if txts is not None:
                texts = [str(t) for t in txts]
                raw_scores = getattr(result, "scores", None)
                if raw_scores is not None:
                    scores = [float(s) for s in raw_scores]

            kept: list[str] = []
            for i, text in enumerate(texts):
                score = scores[i] if i < len(scores) else 1.0
                if score < args.min_confidence:
                    continue          # 低置信度宁缺毋滥：错的内容比没有更危险
                normalized = normalize(text)
                if normalized and not is_noise(normalized):
                    kept.append(normalized)
                    confidences.append(score)

            if kept:
                pages_with_text += 1
                page_texts.append("[第 %d 页]\n%s" % (index + 1, "\n".join(kept)))
    except Exception as exc:
        doc.close()
        return fail(EXIT_INTERNAL, "识别过程中出错：%s" % exc)
    finally:
        try:
            doc.close()
        except Exception:
            pass

    merged = "\n".join(page_texts)
    merged = "\n".join(dedup_repeated_lines(merged.split("\n"))).strip()

    if not merged:
        return fail(
            EXIT_EMPTY_RESULT,
            "未能从该文件中识别出文字（可能是纯图片、空白页，或置信度全部低于阈值）",
            totalPages=total_pages,
        )

    avg_confidence = round(sum(confidences) / len(confidences), 4) if confidences else None
    emit(
        {
            "ok": True,
            "engine": "rapidocr",
            "engineVersion": _package_version("rapidocr"),
            "totalPages": total_pages,
            "pagesWithText": pages_with_text,
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
