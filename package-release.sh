#!/usr/bin/env bash
# ============================================================================
#  qa-assistant 发布包构建（Linux / macOS）
#
#  产物：release/qa-assistant-<版本>.zip 与同名 .sha256
#  包里只有"跑起来需要的东西"，且**不含** .env / target 源码 / node_modules。
#
#  需要 zip 命令；没有的话会给出提示（Debian/Ubuntu: apt install zip）。
# ============================================================================
set -uo pipefail

cd "$(dirname "$0")" || exit 1

echo
echo "============================================================"
echo "  qa-assistant 发布包构建"
echo "============================================================"
echo

# ---------- 1) 找 jar 并确定版本 ----------
JAR="$(ls -1 target/qa-assistant-*.jar 2>/dev/null | head -n 1)"
if [ -z "$JAR" ]; then
  echo "[X] 没有找到 target/qa-assistant-*.jar，请先执行：mvn clean package"
  exit 1
fi
JAR_NAME="$(basename "$JAR")"
VERSION="${JAR_NAME#qa-assistant-}"
VERSION="${VERSION%.jar}"
echo "[i] 版本：$VERSION"
echo "[i] 程序包：$JAR_NAME"

# ---------- 2) 校验 jar 里带前端（否则发出去的包打开是空白页）----------
if command -v unzip >/dev/null 2>&1; then
  CHECK_CMD="unzip -l"
else
  CHECK_CMD="jar tf"
fi
if ! $CHECK_CMD "$JAR" 2>/dev/null | grep -q 'BOOT-INF/classes/static/index.html'; then
  echo "[X] 程序包里没有前端页面，拒绝出包。"
  echo "    先执行：cd web && pnpm build   然后重新 mvn clean package"
  exit 1
fi
echo "[OK] 已确认程序包内含前端页面"

# ---------- 3) 准备临时目录 ----------
STAGE="release/stage"
rm -rf release
mkdir -p "$STAGE/deploy/ollama" "$STAGE/docs" "$STAGE/tools"

cp "$JAR"            "$STAGE/$JAR_NAME"
cp start.sh          "$STAGE/"
cp start.cmd         "$STAGE/"
cp .env.example      "$STAGE/"
cp LICENSE           "$STAGE/"
cp README.md         "$STAGE/"
cp docs/DESIGN.md    "$STAGE/docs/"
cp docs/ACCEPTANCE.md "$STAGE/docs/"
cp deploy/ollama/Modelfile.qa-embed "$STAGE/deploy/ollama/"
cp tools/build-design-doc.cjs      "$STAGE/tools/"

chmod +x "$STAGE/start.sh"

# 防止误发：.env 绝不能进包（哪怕它存在于工作目录）
rm -f "$STAGE/.env"

# ---------- 4) 校验没有敏感文件混入 ----------
echo
echo "[i] 安全校验："
LEAK=0
for p in .env .env.local qa.mv.db embedding-store.json; do
  if [ -e "$STAGE/$p" ]; then
    echo "    [X] 包里出现了 $p —— 这是不该发布的文件"
    LEAK=1
  fi
done
if [ "$LEAK" = "1" ]; then
  echo "[X] 存在敏感文件，拒绝出包。"
  exit 1
fi
echo "    [OK] 未包含 .env / 数据库文件 / 向量库落盘文件"

# ---------- 5) 生成 SHA256 ----------
if command -v sha256sum >/dev/null 2>&1; then
  ( cd "$STAGE" && sha256sum "$JAR_NAME" ) > release/SHA256SUMS.txt
else
  ( cd "$STAGE" && shasum -a 256 "$JAR_NAME" ) > release/SHA256SUMS.txt
fi
echo "[OK] 已生成 SHA256SUMS.txt"

# ---------- 6) 打包 ----------
ZIP="release/qa-assistant-$VERSION.zip"
if command -v zip >/dev/null 2>&1; then
  ( cd "$STAGE" && zip -r -q "../qa-assistant-$VERSION.zip" . )
else
  echo "[!] 没有 zip 命令，退回 python3 打包（Debian/Ubuntu 可 apt install zip）"
  python3 - "$STAGE" "$ZIP" <<'PY'
import os, sys, zipfile
stage, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(stage):
        for f in files:
            full = os.path.join(root, f)
            z.write(full, os.path.relpath(full, stage))
PY
fi
if [ ! -f "$ZIP" ]; then
  echo "[X] 压缩失败。"
  exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  HASH="$(sha256sum "$ZIP" | cut -d' ' -f1)"
else
  HASH="$(shasum -a 256 "$ZIP" | cut -d' ' -f1)"
fi
echo "$HASH  qa-assistant-$VERSION.zip" > "$ZIP.sha256"

rm -rf "$STAGE"

echo
echo "============================================================"
echo "  出包完成"
echo "============================================================"
echo "  文件：$(cd release && pwd)/qa-assistant-$VERSION.zip"
echo "  大小：$(wc -c < "$ZIP") 字节"
echo "  校验：$(cat "$ZIP.sha256")"
echo
echo "  包内清单："
if command -v unzip >/dev/null 2>&1; then
  unzip -l "$ZIP" | awk 'NR>3 && NF>=4 {print "    " $4}' | grep -v '^    $' || true
else
  jar tf "$ZIP" | sed 's/^/    /'
fi
echo
