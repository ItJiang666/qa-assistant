@echo off
setlocal enabledelayedexpansion

rem ============================================================================
rem  qa-assistant 发布包构建（Windows）
rem
rem  产物：release\qa-assistant-<版本>.zip 与同名 .sha256
rem  包里只有"跑起来需要的东西"，且**不含** .env / target 源码 / node_modules。
rem
rem  ?? 本文件必须以 GBK(936) 保存（原因同 start.cmd）。
rem ============================================================================

cd /d "%~dp0"

echo.
echo ============================================================
echo   qa-assistant 发布包构建
echo ============================================================
echo.

rem ---------- 1) 找 jar 并确定版本 ----------
set "JAR="
for %%f in ("target\qa-assistant-*.jar") do set "JAR=%%~ff"
if not defined JAR (
    echo [X] 没有找到 target\qa-assistant-*.jar，请先执行：mvn clean package
    goto :fail
)
for %%f in ("!JAR!") do set "JAR_NAME=%%~nxf"

rem 从 qa-assistant-1.0.0-SNAPSHOT.jar 里取出 1.0.0-SNAPSHOT
set "VERSION=!JAR_NAME:qa-assistant-=!"
set "VERSION=!VERSION:.jar=!"
echo [i] 版本：!VERSION!
echo [i] 程序包：!JAR_NAME!

rem ---------- 2) 校验 jar 里带前端（否则发出去的包打开是空白页）----------
set "UI_FOUND="
for /f "delims=" %%l in ('jar tf "!JAR!" 2^>nul ^| findstr /c:"BOOT-INF/classes/static/index.html"') do set "UI_FOUND=1"
if not defined UI_FOUND (
    echo [X] 程序包里没有前端页面，拒绝出包。
    echo     先执行：cd web ^&^& pnpm build   然后重新 mvn clean package
    goto :fail
)
rem ---------- 2b) front-end asset self-check (no missing, no orphans) ----------
rem Why: vite renames bundles every build, and Maven's incremental build does not
rem clean stale files out of target\classes, so the previous index-xxx.js rides
rem along into the jar -- the package would then contain two front-ends. The
rem logic lives in tools\check-ui-assets.ps1 so there is no quote nesting here.
set "BAD_UI="
powershell -NoProfile -ExecutionPolicy Bypass -File "tools\check-ui-assets.ps1" "!JAR!"
if errorlevel 1 set "BAD_UI=1"
if defined BAD_UI (
    echo [X] Front-end asset check failed - refusing to package.
    echo     Fix: run  mvn clean package   then re-run this script.
    goto :fail
)
echo [OK] 已确认程序包内含前端页面

rem ---------- 3) 准备临时目录 ----------
set "STAGE=release\stage"
if exist "release" rmdir /s /q "release"
mkdir "!STAGE!" >nul 2>nul
mkdir "!STAGE!\deploy\ollama" >nul 2>nul
mkdir "!STAGE!\deploy\ocr" >nul 2>nul
mkdir "!STAGE!\docs" >nul 2>nul

copy /y "!JAR!" "!STAGE!\!JAR_NAME!" >nul
copy /y "start.cmd" "!STAGE!\" >nul
copy /y "start.sh" "!STAGE!\" >nul
copy /y "enable-gpu.cmd" "!STAGE!\" >nul
copy /y "enable-gpu.sh" "!STAGE!\" >nul
copy /y "enable-ocr.cmd" "!STAGE!\" >nul
copy /y "enable-ocr.sh" "!STAGE!\" >nul
copy /y ".env.example" "!STAGE!\" >nul
copy /y "LICENSE" "!STAGE!\" >nul
copy /y "README.md" "!STAGE!\" >nul
copy /y "docs\DESIGN.md" "!STAGE!\docs\" >nul
copy /y "docs\ACCEPTANCE.md" "!STAGE!\docs\" >nul
copy /y "deploy\ollama\Modelfile.qa-embed" "!STAGE!\deploy\ollama\" >nul
copy /y "deploy\ocr\qa_ocr.py" "!STAGE!\deploy\ocr\" >nul
copy /y "deploy\ocr\requirements.txt" "!STAGE!\deploy\ocr\" >nul
rem  qa_speedtest.py must ship too: enable-gpu.cmd calls it to pick the
rem  fastest mirror. The GPU runtime is 1~1.5 GB and measured throughput
rem  differs by ~18x between sources (4.31 MB/s vs 0.24 MB/s), so without
rem  it the script falls back to the official source and may never finish
rem  on a slow line.
copy /y "deploy\ocr\qa_speedtest.py" "!STAGE!\deploy\ocr\" >nul

rem 防止误发：.env 绝不能进包（哪怕它存在于工作目录）
if exist "!STAGE!\.env" del /q "!STAGE!\.env"

rem ---------- 4) 校验没有敏感文件混入 ----------
echo.
echo [i] 安全校验：
set "LEAK="
for %%p in (".env" ".env.local" "qa.mv.db" "embedding-store.json") do (
    if exist "!STAGE!\%%~p" (
        echo     [X] 包里出现了 %%p —— 这是不该发布的文件
        set "LEAK=1"
    )
)
if defined LEAK (
    echo [X] 存在敏感文件，拒绝出包。
    goto :fail
)
echo     [OK] 未包含 .env / 数据库文件 / 向量库落盘文件

rem ---------- 5) 生成 SHA256 ----------
echo.
pushd "!STAGE!"
certutil -hashfile "!JAR_NAME!" SHA256 | findstr /r /v "hash CertUtil" > "..\..\release\SHA256SUMS.txt"
popd
echo [OK] 已生成 SHA256SUMS.txt

rem ---------- 6) 打包 ----------
set "ZIP=release\qa-assistant-!VERSION!.zip"
powershell -NoProfile -Command "Compress-Archive -Path '!STAGE!\*' -DestinationPath '!ZIP!' -Force"
if not exist "!ZIP!" (
    echo [X] 压缩失败。
    goto :fail
)

rem 同时给 zip 本身算一个校验值，便于分发核对
powershell -NoProfile -Command "$h=(Get-FileHash '!ZIP!' -Algorithm SHA256).Hash.ToLower(); \"$h  qa-assistant-!VERSION!.zip\" | Set-Content -Encoding ascii '!ZIP!.sha256'" >nul

rmdir /s /q "!STAGE!"

echo.
echo ============================================================
echo   出包完成
echo ============================================================
for %%f in ("!ZIP!") do echo   文件：%%~ff   大小：%%~zf 字节
if exist "!ZIP!.sha256" (
    echo   校验：
    type "!ZIP!.sha256"
)
echo.
echo   包内清单：
powershell -NoProfile -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; $z=[System.IO.Compression.ZipFile]::OpenRead((Resolve-Path '!ZIP!').Path); $z.Entries | Sort-Object FullName | ForEach-Object { '    ' + $_.FullName }; $z.Dispose()"
echo.
rem 仅在双击(有控制台且未重定向)时暂停，命令行调用直接返回
if not defined QA_NO_PAUSE pause
exit /b 0

:fail
echo.
if not defined QA_NO_PAUSE pause
exit /b 1
