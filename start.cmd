@echo off
setlocal enabledelayedexpansion

rem ============================================================================
rem  qa-assistant 一键启动（Windows）
rem
rem  这个脚本刻意只做"体检 + 启动"，不自动拉模型：
rem    ollama pull qwen3.5:9b 是 8.5 GB 下载，不能藏在"双击启动"背后偷偷跑。
rem  它会把该执行的命令打印出来，由用户自己决定什么时候拉。
rem
rem  ?? 本文件必须以 **GBK(936)** 保存：cmd.exe 按系统 ANSI 代码页解析 .cmd，
rem     存成 UTF-8 会让中文注释变成乱码命令并直接报错。
rem     同理不要在这里 chcp 65001 —— 控制台本来就是 936，切过去反而更乱。
rem
rem  无参双击即可运行；也支持：
rem    start.cmd 8081        指定端口
rem    start.cmd --port 8081 同上
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
set "JAR="
for %%f in ("target\qa-assistant-*.jar") do (
    set "JAR=%%~ff"
)
if not defined JAR (
    echo [X] 在 target\ 下没有找到 qa-assistant-*.jar。
    echo     请先构建：mvn clean package
    echo     ^(改了前端的话要先 cd web ^&^& pnpm build，再 mvn clean package^)
    goto :fail
)
echo [OK] 找到程序包: !JAR!

rem 校验 jar 里是否带前端产物 —— 缺了会打开一个空白页，这比启动失败更难排查。
rem 用 JDK 自带的 jar 命令而不是 PowerShell 解压：前者是硬前提，且不用引号/通配符的坑。
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

rem ---------- 5) 首次运行提示 ----------
if "%OLLAMA_OK%"=="1" (
    echo.
    echo [i] 首次使用本地模式，请确认这两个模型已拉取：
    echo       ollama pull qwen3.5:9b
    echo       ollama pull qwen3-embedding:4b
    echo     并生成项目专用的向量模型^(把上下文压到 2048，否则它会独占约 10GB 显存^)：
    echo       ollama create qa-embed:4b -f deploy\ollama\Modelfile.qa-embed
    echo     漏掉最后一步的表现是"每一问都要等 7~10 秒"。
)

echo.
echo ============================================================
echo   正在启动，服务地址 http://localhost:%PORT%
echo   关闭本窗口即停止服务
echo ============================================================
echo.

rem 稍等几秒再开浏览器：给 Spring Boot 一点启动时间，避免打开白页。
rem 用 ping 而不是 timeout 做延时 —— timeout 在 stdin 被重定向时会直接报错退出。
start "" /min cmd /c "ping -n 9 127.0.0.1 >nul & start http://localhost:%PORT%"

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
