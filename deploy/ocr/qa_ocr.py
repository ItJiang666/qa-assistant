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


def extract_page_images(doc, engine, args, page_texts_by_index=None):
    """抽取内嵌图片并识别其中的文字，**丢掉与整页结果重复的部分**。

    <h2>为什么需要这一步</h2>
    主流程是"整页渲染 → 文字检测"。实测在 200 DPI 下它通常已经能读出页面上
    插图里的字；但有两种情况会漏：
      * 图很小或被缩放过，整页渲染后那部分字太细，检测不到；
      * 页面主体是模糊扫描件（低对比、倾斜），而插在其中的截图反而清晰。
    这时按图单独抽出来识别，才能把那部分文字捞回来。

    <h2>为什么要做重复判定（两轮实测的结论）</h2>
    第一版按"整页字数够多就跳过整页"来避免重复 —— **失败**：实测一页只读出 48 字，
    低于当时设的 50 阈值，于是同一张截图被识别两遍，其中图那遍还带错字
    （整页给 `dpi`/`200`，逐图放大后给 `! d p`/全角 `２00`）。
    改成逐行比对内容：图里识别出的行若大多已出现在该页的整页结果里，整块丢弃。
    这样"重复"由内容本身判定，不依赖任何字数阈值。

    @param page_texts_by_index 页码 → 该页整页识别文本（用于去重）
    @return (图片段落列表, 纳入识别的图片数, 其中有文字的图片数, 置信度列表, 去重丢弃数)
    """
    page_texts_by_index = page_texts_by_index or {}
    blocks: list[str] = []
    confidences: list[float] = []
    considered = 0
    with_text = 0
    duplicates = 0

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
            png_bytes = render_image(doc, info[0], args.min_image_side, args.min_image_pixels)
            if png_bytes is None:
                continue                       # 尺寸太小，判为图标/装饰

            considered += 1
            kept, kept_scores = recognize_bitmap(engine, png_bytes, args.min_confidence)
            if not kept:
                continue

            # 按内容判重：这一行在整页结果里出现过就算重复。
            # 用"包含"而不是"相等"——整页结果里同一行可能被切分或带前后缀。
            fresh = [line for line in kept if line not in page_text]
            if len(fresh) < max(1, int(len(kept) * (1 - IMAGE_DUP_RATIO))):
                duplicates += 1
                continue                       # 增量太小，整块丢弃

            with_text += 1
            confidences.extend(kept_scores)
            blocks.append("[第 %d 页·插图 %d]\n%s" % (index + 1, seq, "\n".join(fresh)))

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
    parser.add_argument("--max-pages", type=int, default=50, help="页数上限，超出直接拒绝")
    parser.add_argument("--dpi", type=int, default=200, help="拆页渲染 DPI，越高越准也越慢")
    parser.add_argument("--min-confidence", type=float, default=0.5, help="低于此置信度的行丢弃")
    parser.add_argument("--preview-chars", type=int, default=500, help="回给 Java 的预览字数")
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
    try:
        engine = RapidOCR()
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
