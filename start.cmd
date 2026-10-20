@echo off
setlocal enabledelayedexpansion

rem ============================================================================
rem  qa-assistant 一键启动（Windows）
rem    start.cmd              默认 8080
rem    start.cmd 8081         指定端口
rem  只做体检 + 启动；不自动拉模型（ollama pull 是 8.5 GB 下载）。
rem  本文件必须以 GBK(936) 保存：cmd.exe 按系统 ANSI 代码页解析 .cmd。
rem ============================================================================

cd /d "%~dp0"

set "PORT=8080"
if not "%~1"=="" (
    if /i "%~1"=="--port" (
        set "PORT=%~2"
    ) else (
        set "PORT=%~1"
    )
)

rem ---------- 端口自检：被占用时直接说明是哪个实例 ----------
rem netstat 的输出是英文固定格式，不受代码页影响；进程名交给 tasklist。
set "PORT_PID="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /r /c:"LISTENING" ^| findstr /r /c:":%PORT% "') do set "PORT_PID=%%p"
if defined PORT_PID (
    set "PORT_PROC="
    for /f "tokens=1 delims=," %%n in ('tasklist /FI "PID eq !PORT_PID!" /FO CSV /NH 2^>nul') do if not defined PORT_PROC set "PORT_PROC=%%~n"
    echo.
    echo [X] 端口 %PORT% 已被占用^(PID !PORT_PID!，进程 !PORT_PROC!^)，无法启动。
    echo     最可能的情况：已经有一个本程序在运行 —— 先看任务栏里有没有它的窗口。
    echo     换一个端口启动：  start.cmd 8081
    goto :fail
)
echo.
echo ============================================================
echo   qa-assistant 智能问答助手 - 启动前检查
echo ============================================================
echo.

rem ---------- 1) JDK ----------
where java >nul 2>nul
if errorlevel 1 (
    echo [X] 没有找到 java 命令。
    echo     本程序需要 JDK 17 或更高版本：https://adoptium.net/temurin/releases/?version=17
    echo     装完后重新打开这个窗口再运行本脚本。
    goto :fail
)

rem 解析主版本号：java -version 把版本打到 stderr，形如 "17.0.9" 或 "25.0.2"
set "JAVA_VER="
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    if not defined JAVA_VER set "JAVA_VER=%%~v"
)
for /f "tokens=1 delims=." %%m in ("!JAVA_VER!") do set "JAVA_MAJOR=%%m"

if not defined JAVA_MAJOR (
    echo [!] 无法解析 Java 版本，跳过版本检查，继续尝试启动。
) else (
    if !JAVA_MAJOR! LSS 17 (
        echo [X] 检测到 Java !JAVA_MAJOR!，但本程序需要 JDK 17 或更高。
        echo     常见现象是不检查版本直接启动，然后抛出 UnsupportedClassVersionError。
        echo     请安装 JDK 17+：https://adoptium.net/temurin/releases/?version=17
        goto :fail
    )
    echo [OK] Java !JAVA_MAJOR! ^(满足 JDK 17+^)
)

rem ---------- 2) 找到可执行的 jar ----------
rem 兼容两种布局：开发仓库（jar 在 target\）与发布包（jar 就在本目录）
set "JAR="
for %%f in ("%~dp0target\qa-assistant-*.jar") do set "JAR=%%~ff"
if not defined JAR (
    for %%f in ("%~dp0qa-assistant-*.jar") do set "JAR=%%~ff"
)
if not defined JAR (
    echo [X] 没有找到 qa-assistant-*.jar。
    echo     下载的发布包：说明包不完整，请重新下载并核对 SHA256。
    echo     从源码运行：请先构建 mvn clean package
    echo     ^(改了前端的话要先 cd web ^&^& pnpm build，再 mvn clean package^)
    goto :fail
)
echo [OK] 找到程序包: !JAR!

rem 校验 jar 里是否带前端产物 —— 缺了会打开一个空白页。
set "UI_FOUND="
for /f "delims=" %%l in ('jar tf "!JAR!" 2^>nul ^| findstr /c:"BOOT-INF/classes/static/index.html"') do (
    set "UI_FOUND=1"
)
if not defined UI_FOUND (
    echo [!] 警告：程序包里没有前端页面^(static/index.html^)。
    echo     启动后打开网页会是一片空白。请先在 web 目录构建前端再重新打包。
)

rem ---------- 3) Ollama 体检（本地模式用；没有也不阻塞，可能是走云端）----------
set "OLLAMA_OK="
curl -s -m 3 http://localhost:11434/api/tags >nul 2>nul
if not errorlevel 1 (
    set "OLLAMA_OK=1"
    echo [OK] Ollama 可用 ^(http://localhost:11434^)
) else (
    echo [!] 连不上 Ollama ^(http://localhost:11434^)。
    echo     如果你要用本地模型，请先启动 Ollama；只用云端模式可以忽略这条。
)

rem ---------- 4) .env 体检 ----------
if exist ".env" (
    echo [OK] 找到 .env
) else (
    echo [i] 没有 .env ^(可选^)。需要配置云端 Key 时：
    echo     复制 .env.example 为 .env，填入 Key，再重新运行本脚本。
)

rem ---------- 4.5) 云端模式预检 ----------
rem 起因：QA_MODE=cloud 但缺 Key 时，应用会在装配期抛异常直接退出，
rem 用户看到的是一大串 Spring bean 堆栈（实测：缺少 SILICONFLOW_API_KEY）。
rem 这里提前判断，用一句话说清"缺什么、怎么办"，而不是让他去读堆栈。
if /i "%QA_MODE%"=="cloud" (
    set "CLOUD_OK=1"
    echo %DEEPSEEK_API_KEY%| findstr /c:"sk-" >nul || set "CLOUD_OK="
    echo %SILICONFLOW_API_KEY%| findstr /c:"sk-" >nul || set "CLOUD_OK="
    if not defined CLOUD_OK (
        echo.
        echo [X] QA_MODE=cloud，但 .env 里缺少云端 Key，程序无法启动。
        echo     云端模式需要两个 Key：对话 DEEPSEEK_API_KEY + 向量 SILICONFLOW_API_KEY。
        echo     二选一：
        echo       a^) 复制 .env.example 为 .env，把两个 Key 填上，再运行本脚本；
        echo       b^) 改用本地模式：把 .env 里的 QA_MODE 设成 local（或这一行整行删掉，默认就是 local）。
        goto :fail
    )
    echo [OK] 云端模式 Key 已配置
)

rem ---------- 5) 首次运行提示 ----------
if "%OLLAMA_OK%"=="1" (
    echo.
    echo [i] 首次使用本地模式，请确认这两个模型已拉取：
    echo       ollama pull qwen3.5:9b
    echo       ollama pull qwen3-embedding:4b
    echo     并生成项目专用的向量模型^(把上下文压到 2048，否则它会独占约 10GB 显存^)：
    echo       ollama create qa-embed:4b -f deploy\ollama\Modelfile.qa-embed
    echo     漏掉最后一步的表现是"每一问都要等 7~10 秒"。
    echo.
    echo [i] 要处理扫描版 PDF^(可选^)，还需要 Python 3.10+ 和下面两步：
    echo       cd /d "%~dp0"
    echo       python -m pip install -r deploy\ocr\requirements.txt
    echo     注意先在程序目录里执行^(这条命令用的是相对路径^)，这一步需要联网。
    echo     装完刷新页面按钮才会亮；不装不影响问答、上传、检索。
)

echo.
echo ============================================================
echo   正在启动，服务地址 http://localhost:%PORT%
echo   关闭本窗口即停止服务
echo ============================================================
echo.

rem 等端口真的能响应再开浏览器 —— 固定延时实测不够：
rem 启动实测 10.6 秒（其中 Hikari 建第一条数据库连接就占了 8.7 秒），而原先写死等 8 秒，
rem 于是浏览器早到 2~3 秒，页面加载失败，用户看到的就是"连接不上"。
start "" /min powershell -NoProfile -Command "$u='http://localhost:%PORT%'; for($i=0;$i -lt 120;$i++){ try{ $r=Invoke-WebRequest -Uri $u -UseBasicParsing -TimeoutSec 2; if($r.StatusCode -eq 200){ Start-Process $u; break } }catch{}; Start-Sleep -Milliseconds 500 }"

java -jar "!JAR!" --server.port=%PORT%
set "EXIT_CODE=%errorlevel%"

echo.
if not "%EXIT_CODE%"=="0" (
    echo [X] 程序退出，退出码 %EXIT_CODE%。常见原因：
    echo     - 端口 %PORT% 被占用：换一个端口运行，例如  start.cmd 8081
    echo     - 缺云端 Key：见上面的 .env 提示
    echo     - 数据目录不可写：默认在 %%USERPROFILE%%\.qa-assistant
)
pause
exit /b %EXIT_CODE%

:fail
echo.
pause
exit /b 1
