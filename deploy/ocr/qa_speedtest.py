# -*- coding: utf-8 -*-
"""qa_speedtest.py —— 给 GPU 安装脚本挑一个"下得最快"的 PyPI 源。

为什么需要它
------------
GPU 运行库那套包很大（实测：cu13 约 1.05 GB、cu12 约 1.49 GB、cu11 约 1.2 GB），
而不同源的吞吐差别很大。更要紧的是：某些网络下官方源只有 0.02 MB/s，1.49 GB 要
21 小时，等于装不完 —— 所以"选对源"不是优化，而是能不能装完的分界线。

它怎么测
--------
对每个源：从它的索引页里挑一个**真实的 CUDA 轮子**，带 Range 头只下前若干 MB，
算吞吐。用真实轮子而不是随便找个文件，因为要测的正是"下这些大包"这条路径。

⚠️ 修过的一个坑（2026-10-13，我自己踩的）
----------------------------------------
国内镜像（清华/腾讯云/中科大…）返回的轮子链接是**相对路径**，形如
`../../packages/18/1c/xxxx/nvidia_cublas_cu12-12.9.2.10-py3-none-win_amd64.whl`，
而 PyPI 官方返回的是 `https://files.pythonhosted.org/...` 绝对地址。
直接用 `urljoin` 拼，`../../` 会被 `urlopen` 悄悄吞掉，于是拿到 404 —— 当时因此
把几个可用镜像全误判为"该源缺这个文件"，还得出过一个错误的加速比结论。

这里的正确做法：先 `urljoin`，再用 `posixpath.normpath` 把 `..` **在上层目录里**
解析掉（`packages/18/1c/../../x` → `packages/x`），而不是让它跑到域名根上。

输出格式（供 .sh / .cmd 解析，一行 `KEY<TAB>VALUE`）：
    OK<TAB><url><TAB>速度MB/s<TAB>说明
    SKIP<TAB><url><TAB>原因
    RANK<TAB>序号<TAB><url><TAB>速度
    BEST<TAB><url>
    EST<TAB>速度<TAB>分钟
"""

import argparse
import posixpath
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

UA = {'User-Agent': 'pip/24.0 (qa-assistant gpu setup)'}

CANDIDATES = [
    'https://mirrors.cloud.tencent.com/pypi/simple',
    'https://mirrors.ustc.edu.cn/pypi/simple',
    'https://pypi.tuna.tsinghua.edu.cn/simple',
    'https://pypi.org/simple',
]


def fetch(url, headers=None, timeout=25):
    req = urllib.request.Request(url, headers=headers or UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def absolutize(base_index_url, href):
    """把索引里的 href 变成可下载的绝对地址。

    ⚠️ 这里踩了三次坑才弄清规则。索引里的 href 是**相对当前索引页**的，
    而各源的路径前缀并不一样，**没有一个统一写法**，必须老老实实按相对路径解析：

      清华   https://pypi.tuna.tsinghua.edu.cn/simple/nvidia-cublas-cu12/
             + ../../packages/20/e2/x.whl
             → https://pypi.tuna.tsinghua.edu.cn/packages/20/e2/x.whl        ✓
      腾讯云 https://mirrors.cloud.tencent.com/pypi/simple/nvidia-cublas-cu12/
             + ../../packages/20/e2/x.whl
             → https://mirrors.cloud.tencent.com/pypi/packages/20/e2/x.whl   ✓ 有 /pypi
      官方   href 直接就是 https://files.pythonhosted.org/... 绝对地址

    三次错误做法（都实测 404）：
      1. 直接 `urljoin(base, href)`：base 只有 2 层目录、href 有 3 个 `../`，
         多出的那层被 urlopen 静默吞掉；
      2. 先 urljoin 再 `posixpath.normpath`：normpath 把前导 `..` 也折叠了；
      3. 只取域名 + 去掉前导 `..`：换到清华能通，但腾讯云/中科大丢了 `/pypi` 前缀。

    正确做法：base 路径 + href 拼起来，保留前导 `..`，再 normpath 解析。
    """
    if href.startswith(('http://', 'https://')):
        return href
    parts = urllib.parse.urlsplit(base_index_url)
    origin = '%s://%s' % (parts.scheme, parts.netloc)
    combined = posixpath.join(parts.path, href)
    path = posixpath.normpath(combined)
    while path.startswith('//'):
        path = path[1:]
    if not path.startswith('/'):
        path = '/' + path
    return origin + path


def pick_wheel(index_url, package):
    """挑一个 win_amd64 轮子（版本号最大的）。

    注意：href 是相对**包页**（`<索引>/<包名>/`）的，所以传给 absolutize 的基准
    必须是包页地址而不是索引根 —— 少一层目录就会让 `..` 多吃掉一段路径
    （腾讯云/中科大的 `/pypi` 前缀就是这么丢的）。
    """
    page = index_url.rstrip('/') + '/%s/' % package
    html = fetch(page).decode('utf-8', 'replace')
    links = re.findall(r'href="([^"#]+\.whl)', html)
    cands = [l for l in links if 'win_amd64' in l]
    if not cands:
        return None, None

    def ver(u):
        m = re.search(r'-(\d+(?:\.\d+)+)(?:-|\.whl)', u)
        return tuple(int(x) for x in m.group(1).split('.')) if m else (0,)

    best = max(cands, key=ver)
    name = posixpath.basename(best.split('#')[0])
    return absolutize(page, best), name


def measure(index_url, package, limit, timeout):
    """返回 (速度MB/s 或 None, 说明)。"""
    try:
        wheel, name = pick_wheel(index_url, package)
    except Exception as exc:
        return None, '索引不可达（%s）' % type(exc).__name__
    if not wheel:
        return None, '该源没有 %s 的 win_amd64 轮子' % package
    try:
        headers = dict(UA, Range='bytes=0-%d' % (limit - 1))
        t0 = time.time()
        got = 0
        with urllib.request.urlopen(urllib.request.Request(wheel, headers=headers),
                                    timeout=timeout) as r:
            if r.status not in (200, 206):
                return None, 'HTTP %s' % r.status
            while got < limit:
                chunk = r.read(262144)
                if not chunk:
                    break
                got += len(chunk)
        dt = time.time() - t0
        if got < 1024 or dt <= 0:
            return None, '只下到 %d 字节' % got
        speed = got / dt / 1048576.0
        return speed, '%s：%.1f MB / %.2fs' % (name[:38], got / 1048576.0, dt)
    except urllib.error.HTTPError as exc:
        return None, 'HTTP %s' % exc.code
    except Exception as exc:
        return None, '下载失败（%s）' % type(exc).__name__


def main():
    ap = argparse.ArgumentParser(description='为 GPU 安装挑最快的 PyPI 源')
    ap.add_argument('--package', default='nvidia-cublas-cu12',
                    help='用哪个包测速（默认 nvidia-cublas-cu12，体积最大最有代表性）')
    ap.add_argument('--limit-mb', type=float, default=2.0, help='每源最多下多少 MB')
    ap.add_argument('--timeout', type=float, default=25.0, help='单源超时秒数')
    ap.add_argument('--indexes', nargs='*', default=CANDIDATES, help='候选源列表')
    ap.add_argument('--out', default='', help='把选定源写入这个文件（便于 .cmd 读取，'
                                              '避免解析 UTF-8 输出时的代码页问题）')
    args = ap.parse_args()

    limit = max(1, int(args.limit_mb * 1048576))
    results = []
    for url in args.indexes:
        speed, why = measure(url, args.package, limit, args.timeout)
        if speed is None:
            print('SKIP\t%s\t%s' % (url, why))
        else:
            print('OK\t%s\t%.2f\t%s' % (url, speed, why))
            results.append((speed, url))
        sys.stdout.flush()

    best_url = ''
    if results:
        results.sort(key=lambda x: -x[0])
        for i, (sp, url) in enumerate(results, 1):
            print('RANK\t%d\t%s\t%.2f' % (i, url, sp))
        best_speed, best_url = results[0]
        print('BEST\t%s' % best_url)
        print('EST\t%.1f\t%.1f' % (best_speed, 1526 / best_speed / 60 if best_speed else 0))
    else:
        print('BEST\t')
        print('NOTE\t所有候选源都不可用，将回退官方源')

    #  给 .cmd 用的落盘结果：只写一行 URL（空 = 没有可用源）
    if args.out:
        try:
            with open(args.out, 'w', encoding='utf-8', newline='\n') as f:
                f.write(best_url + '\n')
        except Exception as exc:
            print('NOTE\t写入 %s 失败：%s' % (args.out, exc))
    return 0


if __name__ == '__main__':
    sys.exit(main())
