@echo off
setlocal EnableDelayedExpansion
rem ===========================================================================
rem  enable-gpu.cmd  --  one-click GPU acceleration for qa-assistant OCR
rem ===========================================================================
rem  WHY THIS SCRIPT EXISTS (and why we do not ship a prebuilt bundle):
rem    The GPU runtime is ~1.07 GB and, worse, it is LOCKED to the exact
rem    Python ABI (cp311 / cp312 / cp313 ...) and platform. A prebuilt pack
rem    would only work for users whose Python matches ours -- and we cannot
rem    know that. This script detects the environment and lets pip fetch the
rem    right build, so it works on whatever the user actually has.
rem
rem  WHAT IT DOES (6 steps)
rem    1. locate a Python interpreter that ACTUALLY RUNS (skips Store stubs)
rem    2. read nvidia-smi: GPU model + driver version
rem    3. check the driver can run CUDA 13; if not, say so and go CPU
rem    4. install the OCR baseline (rapidocr, pymupdf)
rem    5. install onnxruntime-gpu AND the CUDA/cuDNN runtime packages,
rem       listed EXPLICITLY (see caveat 1)
rem    6. REAL self-test: build a CUDA session from a bundled model
rem
rem  ==========================================================================
rem  WARNING -- version 1 of this script FAILED on a clean machine.
rem  Measured by the user: only ~300 MB downloaded and the GPU did not take
rem  effect, on hardware that was fully capable. Three independent causes, all
rem  reproduced and fixed here. Read this before changing anything:
rem
rem   1. DO NOT go back to the `onnxruntime-gpu[cudnn]` extra.
rem      Measured: on a clean machine pip installed the 160 MB onnxruntime-gpu
rem      wheel and pulled in NO CUDA runtime at all. The CUDA 13 set is required:
rem      nvidia-cudnn-cu13 (416 MB) + nvidia-cublas (423 MB) +
rem      nvidia-cuda-nvrtc (45 MB) + nvidia-cuda-runtime (3 MB).
rem      160 + 141 ~= 300 MB -- exactly the figure the user saw.
rem      Why it is easy to miss: with ONLY onnxruntime-gpu installed,
rem      get_available_providers() STILL lists CUDAExecutionProvider (the
rem      provider merely registers), but creating a session cannot load the
rem      CUDA DLLs and SILENTLY falls back to CPU. No error, just no speedup.
rem      Fix: enumerate every package explicitly in GPU_PACKAGES.
rem
rem   2. The old "driver < 580 -> use the CUDA 12 build" branch was a TRAP and
rem      has been REMOVED. Measured: the old onnxruntime-cuda-12 feed does list
rem      1.27 / 1.28 / 1.29 for win_amd64, but their metadata declares
rem      `nvidia-*-cu13` dependencies -- named 12, requiring 13. That path
rem      cannot be made to work at all. Real CUDA 12 builds stop around 1.19.
rem      Fix: driver < 580 -> say so plainly and install the CPU baseline.
rem
rem   3. Verification must BUILD A SESSION, not just list providers.
rem      get_available_providers() containing CUDA proves nothing (see 1).
rem      Fix: create a CUDA session from rapidocr's bundled
rem      ch_ppocr_mobile_v2.0_cls_mobile.onnx (0.56 MB) and require
rem      session.get_providers()[0] == 'CUDAExecutionProvider'.
rem      Measured cost on the dev machine: ~3 seconds.
rem      Verified both ways: with the CUDA libraries present it reports
rem      "CUDAExecutionProvider|CPUExecutionProvider" and passes; with the
rem      libraries removed it reports "CPUExecutionProvider" and fails --
rem      which is precisely the check version 1 was missing.
rem
rem  ==========================================================================
rem  SLOW / INTERRUPTED DOWNLOADS -- the robustness layer added afterwards:
rem    * pip already does resuming and retrying; this script raises the values to
rem      something usable for a 1 GB download:
rem        --retries 10 --timeout 60 --resume-retries 5
rem      The default --timeout is 15 seconds, which is far too short for a
rem      400 MB wheel -- a slow connection gets cut off again and again, and
rem      that is part of why only ~300 MB arrived.
rem      NOTE: --resume-retries only exists in pip 26+. Older pip would abort
rem      with "no such option", so it is probed for and only then added.
rem    * An outer retry loop waits 5 / 10 / 20 seconds and tries again, up to
rem      --retries times (default 3). Finished wheels stay in pip's cache, so a
rem      retry does not re-download everything.
rem    * Use a mirror (much faster on many networks, and it also removes the
rem      dependency on pypi.org / pypi.nvidia.com being reachable):
rem        enable-gpu.cmd --source https://pypi.tuna.tsinghua.edu.cn/simple
rem      Measured: that mirror carries onnxruntime-gpu, nvidia-cublas,
rem      nvidia-cudnn-cu13 and nvidia-cuda-nvrtc.
rem    * Proxy: --proxy http://127.0.0.1:7890
rem    * The source is probed BEFORE downloading, so an unreachable mirror is
rem      reported immediately instead of after ten minutes of timeouts.
rem    * --verify re-runs the self-test without installing anything, to answer
rem      "is GPU working right now?".
rem  ==========================================================================
rem
rem  NOTE: this file must stay pure ASCII + CRLF. cmd.exe reads .cmd files using
rem  the system code page (GBK on Chinese Windows); any UTF-8 Chinese text inside
rem  would be decoded as garbage and can break parsing. Chinese messages from
rem  the Python child process are handled correctly (it reconfigures UTF-8).
rem ===========================================================================

set "PY="
set "PYVER="
set "PYM="
set "PYN="
set "PROBE_RC="
set "CUDA13_MIN_DRIVER=580"
set "NVIDIA_INDEX=https://pypi.nvidia.com"
set "PYPI_INDEX=https://pypi.org/simple"

rem  GPU runtime packages -- explicit list, NO extras (caveat 1).
rem  Versions match the combination verified working on the dev machine:
rem  onnxruntime-gpu 1.31.0 + nvidia-cudnn-cu13 9.27 + nvidia-cublas 13.8 + nvrtc 13.4
set "GPU_PACKAGES=onnxruntime-gpu nvidia-cudnn-cu13~=9.0 nvidia-cublas nvidia-cuda-nvrtc~=13.0"

rem  Custom source / proxy / retries. NOTE: :parse_args CLEARS SRC_INDEX and
rem  PROXY, so these environment fallbacks must be (re)applied AFTER parsing --
rem  otherwise a value set here would be wiped for anyone passing only --check.
rem  That is done right after the call to :parse_args further down.
set "STEP_RETRIES=%QA_GPU_RETRIES%"
if not defined STEP_RETRIES set "STEP_RETRIES=3"

rem  pip network options. The default --timeout 15 is too short for a 400 MB
rem  wheel: one hiccup on a slow line restarts the whole download.
set "PIP_NET_OPTS=--retries 10 --timeout 60"

set "TARGET="
set "PRE="
set "VERIFY_ONLY="

rem ---- parse arguments ------------------------------------------------------
rem  All of the parsing quirks that had to be discovered the hard way are
rem  documented on :parse_args itself. Short version: parsing lives in ONE
rem  subroutine, because `goto :label` in the main flow resets %1..%9 and makes
rem  `shift` useless, while `%~1` read inside a ( ) block is expanded too early
rem  to ever see a shift.
call :parse_args %*
if errorlevel 1 exit /b 1

rem  Apply the environment fallbacks now, AFTER parsing cleared the two flags
rem  (see the note next to QA_GPU_RETRIES above).
if not defined SRC_INDEX if defined QA_GPU_SOURCE set "SRC_INDEX=%QA_GPU_SOURCE%"
if not defined PROXY     if defined QA_GPU_PROXY  set "PROXY=%QA_GPU_PROXY%"

echo ============================================================
echo   qa-assistant  -  enable GPU acceleration for OCR
echo ============================================================
echo.

rem ---- 1. locate python -----------------------------------------------------
rem  Must ACTUALLY RUN each candidate, not just check that it exists.
rem  Measured pitfall: on Windows with the Store build of Python installed,
rem  PATH contains a `WindowsApps\python3.exe` redirect stub. `where` finds it,
rem  but executing it fails (it just opens the Store). Picking it blind makes
rem  this script tell a machine that clearly HAS Python that "no Python found".
rem  So every candidate is verified by running it; the first working one wins.
rem  A venv is fine too: if one is activated, "python" already points at it,
rem  and that is the interpreter we want to install into.
rem
rem  This block sets: PY (command), PYVER (e.g. 3.13.13), PYM (3), PYN (13)
set "PY="
call :try_python "py" "-3"
if not defined PY call :try_python "python" ""
if not defined PY call :try_python "python3" ""
if not defined PY (
  echo [X] No working Python found. OCR itself needs Python 3.10+ installed;
  echo     GPU is optional, so the CPU path keeps working without this script.
  goto :fail
)
echo [1/6] Python : !PYVER!  (!PYARCH!)
for /f "delims=" %%v in ('!PY! -c "import sys;print(sys.executable)" 2^>nul') do set "PYPATH=%%v"
echo       target : !PYPATH!
if defined SRC_INDEX echo       source : !SRC_INDEX!  ^(custom^)
if defined PROXY     echo       proxy  : !PROXY!

rem  Show WHICH environment is about to be modified and let the user back out.
rem  Measured pitfall: pre-pending a venv to PATH did NOT make the venv's python
rem  win here, so dependencies silently landed in the main environment instead.
rem  Printing the absolute interpreter path is the cheap guard against that.
if not defined PRE (
  if not defined VERIFY_ONLY (
    rem  NOTE: the prompt text goes into a variable first. Writing
    rem  `set /p "ANS=... [Y/n] "` directly makes cmd scan the text for `!`,
    rem  which re-expands the trailing bracket and prints garbage (measured).
    set "Q=      Install into THIS environment? [Y/n] "
    set /p "ANS=!Q!"
    if /i "!ANS!"=="n" (
      echo [i] Cancelled. Nothing was changed.
      goto :end
    )
  )
)

rem  Python 3.10+ is what the OCR dependencies require; say so plainly rather
rem  than letting pip fail later with a wall of resolver output.
if !PYM! LSS 3 goto :old_python
if !PYM! EQU 3 if !PYN! LSS 10 goto :old_python

rem  Warn early when the machine is 32-bit: no GPU wheels exist for win32.
if /i not "!PYARCH!"=="AMD64" (
  echo [X] 32-bit Python detected. onnxruntime-gpu ships win_amd64 wheels only.
  echo     Install 64-bit Python, or just keep using CPU ^(it still works^).
  goto :fail
)

rem ---- --verify : self-test only, install nothing ---------------------------
rem  Jumps forward to the shared self-test block instead of repeating it. An
rem  earlier version duplicated the probe call here and forgot `call :write_probe`,
rem  so --verify ran python with an empty PROBE and reported a failure that did
rem  not exist. One code path, one place to get it right.
if defined VERIFY_ONLY (
  echo.
  echo [verify] Running the self-test only -- nothing will be installed...
  goto :selftest
)

rem ---- 2. NVIDIA driver and GPU generation ---------------------------------
rem  Two separate queries on purpose: --query-gpu with several fields and
rem  --format=csv,noheader repeats the header once per GPU, which makes
rem  token parsing fragile. One field per call is unambiguous.
set "NVSMI=nvidia-smi"
where nvidia-smi >nul 2>&1 || set "NVSMI=%ProgramFiles%\NVIDIA Corporation\NVSMI\nvidia-smi.exe"
set "DRV="
set "GPUNAME="
for /f "usebackq delims=" %%v in (`""!NVSMI!" --query-gpu^=driver_version --format^=csv^,noheader 2^>nul"`) do (
  if not defined DRV set "DRV=%%v"
)
for /f "usebackq delims=" %%v in (`""!NVSMI!" --query-gpu^=name --format^=csv^,noheader 2^>nul"`) do (
  if not defined GPUNAME set "GPUNAME=%%v"
)

if not defined DRV (
  echo [2/6] NVIDIA driver : NOT FOUND
  echo.
  echo [i] No usable NVIDIA GPU detected on this machine -- skipping the GPU
  echo     runtime ^(it would be a pointless ~1 GB download^).
  echo     Installing the CPU OCR baseline instead, so the feature still works.
  call :install_baseline
  if errorlevel 1 goto :fail
  goto :done_cpu
)
echo [2/6] GPU    : !GPUNAME!
echo       driver : !DRV!

rem ---- 3. driver new enough? (no CUDA-12 fallback -- caveat 2) --------------
set "DRVMAJOR="
for /f "tokens=1 delims=." %%m in ("!DRV!") do set "DRVMAJOR=%%m"
set /a "DRVNUM=DRVMAJOR" 2>nul
if not defined DRVNUM set "DRVNUM=0"

if "!TARGET!"=="cpu" (
  echo [3/6] --cpu given: skipping the GPU runtime.
  echo     Installing the CPU OCR baseline only.
  call :install_baseline
  if errorlevel 1 goto :fail
  goto :done_cpu
)

if !DRVNUM! LSS %CUDA13_MIN_DRIVER% (
  echo [3/6] Driver too old for CUDA 13 : !DRV! ^< %CUDA13_MIN_DRIVER%
  echo.
  echo [i] The CUDA 13 runtime that onnxruntime-gpu needs requires driver ^>= %CUDA13_MIN_DRIVER%.
  echo     This script DELIBERATELY ships no older-CUDA fallback: measured, the
  echo     versions on the old CUDA 12 feed ^(1.27 / 1.28 / 1.29^) declare cu13
  echo     dependencies in their metadata, so that path cannot be made to work.
  echo     Please UPDATE YOUR NVIDIA DRIVER and run this script again -- updating
  echo     the driver is free and does not require changing the GPU.
  echo     Until then, CPU OCR is installed and works ^(38 pages in ~47 s^).
  call :install_baseline
  if errorlevel 1 goto :fail
  goto :done_cpu
)

rem  Very old cards: the CUDA runtimes dropped Maxwell/Pascal, so even with a
rem  new driver the provider cannot start. Say so instead of letting the user
rem  download ~1 GB for nothing.
rem
rem  NOTE: written WITHOUT a pipe, and the exit code is captured immediately.
rem  `echo !GPUNAME! | findstr ... >nul` followed by `if not errorlevel 1` looks
rem  natural but is fragile twice over: GPUNAME can be empty, and this project
rem  has measured a pipe-related cmd failure that killed a script mid-run (the
rem  --resume-retries probe in :pip_args). findstr can read a file directly, and
rem  `set` after it would clobber errorlevel, hence OLDGPU_RC.
echo !GPUNAME! > "%TEMP%\qa-gpu-name.txt"
findstr /i /c:"GTX 10" /c:"GTX 9" /c:"GTX 7" /c:"GT 10" /c:"GT 9" /c:"GT 7" /c:"Quadro K" /c:"Quadro P" /c:"Quadro M" /c:"Tesla K" /c:"Tesla P" "%TEMP%\qa-gpu-name.txt" >nul
set "OLDGPU_RC=!errorlevel!"
del "%TEMP%\qa-gpu-name.txt" >nul 2>nul
if "!OLDGPU_RC!"=="0" (
  echo.
  echo [i] This GPU generation ^(GTX 10-series / older^) is no longer supported by
  echo     the CUDA runtimes that onnxruntime-gpu needs. The install would
  echo     succeed but CUDAExecutionProvider would fail to start, so we skip it
  echo     rather than waste a ~1 GB download.
  echo     Installing the CPU OCR baseline instead -- it already got a ~6.6x
  echo     speedup in v1.28 and needs no extra downloads.
  call :install_baseline
  if errorlevel 1 goto :fail
  goto :done_cpu
)
echo [3/6] CUDA   : 13  ^(driver !DRV! ^>= %CUDA13_MIN_DRIVER%^)

rem ---- 4. OCR baseline, ALWAYS install --------------------------------------
rem  onnxruntime is only the INFERENCE BACKEND. What actually reads text is
rem  `rapidocr`, and PDF pages are rasterized by `pymupdf` -- neither is pulled
rem  in by onnxruntime-gpu. So a machine that only ran this script would still
rem  have no working OCR. Install the baseline here too.
rem
rem  Bonus effect: a machine with no usable GPU still ends up with a working
rem  CPU setup from this single command -- the safest possible outcome.
echo [4/6] Ensuring the OCR baseline ^(rapidocr, pymupdf^)...
call :install_baseline
if errorlevel 1 goto :fail
echo.

rem ---- 5. GPU runtime, explicit package list (caveat 1) ---------------------
echo [5/6] About to install the GPU runtime:
echo       %GPU_PACKAGES%
if defined SRC_INDEX (
  echo       from index: !SRC_INDEX!  ^(custom; pypi.org kept as a supplement^)
) else (
  echo       from index: %NVIDIA_INDEX%
)
call :pip_args
echo       network   : %PIP_ARGS%
echo.
if defined PRE (
  echo [i] --check given: NOT installing. Re-run without --check to proceed.
  goto :done_plan
)

echo       Downloading ~1.07 GB ^(needs a working internet connection^).
echo       This may take a few minutes. Do not close this window.
echo.

rem  Probe the source first: an unreachable mirror should be reported at once,
rem  not after ten minutes of timeouts.
call :check_source
if errorlevel 1 (
  echo.
  echo [X] That source is not reachable from this machine -- not starting the
  echo     download ^(no point waiting for it to fail^).
  echo     Things to try:
  echo       1^) a mirror: enable-gpu.cmd --source https://pypi.tuna.tsinghua.edu.cn/simple
  echo       2^) a proxy : enable-gpu.cmd --proxy http://127.0.0.1:7890
  echo       3^) check network / DNS / firewall, then re-run.
  echo     Nothing was broken: the application falls back to CPU automatically.
  goto :fail
)

call :pip_retry
if errorlevel 1 (
  echo.
  echo [X] Install failed. Common causes: network blocked, proxy required, or
  echo     the source is unreachable.
  echo     A retry does NOT re-download everything: finished wheels stay in
  echo     pip's cache. Try in this order:
  echo       1^) simply re-run this script ^(mirror hiccups usually clear up^)
  echo       2^) a mirror: enable-gpu.cmd --source https://pypi.tuna.tsinghua.edu.cn/simple
  echo       3^) a proxy : enable-gpu.cmd --proxy http://127.0.0.1:7890
  echo     Nothing was broken: the application falls back to CPU automatically.
  goto :fail
)
echo.

rem ---- 6. REAL self-test: build a CUDA session (caveat 3) -------------------
rem  Also the landing point for --verify, hence the conditional label text.
:selftest
if defined VERIFY_ONLY (
  echo [verify] Self-test: building a CUDA session from a bundled model ^(~3 s^)...
) else (
  echo [6/6] Self-test: building a CUDA session from a bundled model ^(~3 s^)...
)
rem  Order matters: build the probe file first, THEN run it and capture the exit
rem  code straight away. Do not put anything between the run and the capture --
rem  `set` and `del` overwrite errorlevel, and the probe's result would be lost.
rem  (Measured elsewhere in this project: a captured exit code that silently
rem  came from the wrong command made a working check report success.)
call :write_probe
%PY% "%PROBE%"
set "PROBE_RC=!errorlevel!"
del "%PROBE%" >nul 2>nul
goto :verdict

rem ---- verdict -------------------------------------------------------------
:verdict
echo.
echo ============================================================
if "!PROBE_RC!"=="0" (
  echo   [OK] GPU acceleration is ENABLED -- and a CUDA session was really built.
  echo.
  echo   Restart the application, then recognize any scanned file and look for
  echo   this line in the log ^(%%USERPROFILE%%\.qa-assistant\logs^):
  echo       OCR using GPU
  echo   Expected speedup on a 38-page scan: about 47 s -^> about 17 s.
  echo ============================================================
  goto :end
)
echo   [!] CUDA session could NOT be built -- GPU will NOT take effect.
echo       ^(the application silently falls back to CPU^)
echo.
echo   This check exists on purpose: with only onnxruntime-gpu installed,
echo   get_available_providers^(^) STILL lists CUDAExecutionProvider, but
echo   creating a session cannot load the CUDA DLLs and falls back to CPU
echo   without any error. The PROBE lines above show where it stopped.
echo   Most likely causes:
echo     - CUDA runtime packages missing  ^(re-run this script^)
echo     - driver older than %CUDA13_MIN_DRIVER%
echo   Try updating the NVIDIA driver, then run this script again.
echo ============================================================
goto :fail

:done_cpu
echo.
echo [i] Nothing else was changed. OCR continues to run on CPU.
echo     CPU timing ^(measured^): a 38-page scan takes about 47 seconds after
echo     the v1.28 optimizations -- that is 6.6x faster than before them.
goto :end

:done_plan
echo.
echo [i] Dry run finished. The pip command that WOULD run is:
echo       %PY% -m pip install %PIP_ARGS% %GPU_PACKAGES%
goto :end

:fail
echo.
exit /b 1

:end
echo.
rem  Do not block automation: pause only when a human is watching.
if defined PRE goto :eof
if defined VERIFY_ONLY goto :eof
echo Press any key to close this window...
pause >nul
exit /b 0

rem ---------------------------------------------------------------------------
rem  :parse_args <args...>
rem  Parse the command line. All state lands in globals (they survive `exit /b`):
rem    PRE=1, VERIFY_ONLY=1, TARGET=cpu, SRC_INDEX, PROXY, STEP_RETRIES
rem  Returns errorlevel 1 on a bad option, a missing value, or --help.
rem
rem  *** MEASURED TRAP 3 *** -- never read %~1 INSIDE a ( ) block after a shift.
rem  cmd expands a parenthesised block when it PARSES it, so every %~1 in the
rem  block is fixed before `shift` runs. Measured: `if "%~1"=="--source" ( shift
rem  & set "SRC=%~1" & ... )` stored the literal "--source" as the source URL --
rem  and, because the block also consumed a token, the real URL was skipped
rem  entirely. That is why the dry run printed `--index-url --source`.
rem
rem  The form that works is a ONE-TOKEN LOOKAHEAD decided at the loop top, where
rem  %~1 is evaluated fresh from the top level: seeing a value-taking flag sets
rem  NEXT=<variable> and shifts once, and the next iteration -- a different
rem  top-level parse -- stores that token. Verified against
rem  `--check --source URL --proxy P`.
rem
rem  `shift` itself is fine here: this subroutine owns its positional parameters,
rem  and `goto :label` INSIDE it does not reset them. (Jumping to a label in the
rem  main flow DOES reset them to the script's original args -- measured earlier.)
rem ---------------------------------------------------------------------------
:parse_args
set "SRC_INDEX="
set "PROXY="
set "TARGET="
set "PRE="
set "VERIFY_ONLY="
set "NEXT="
:parse_loop
if "%~1"=="" exit /b 0

rem  Second pass: the previous token asked for this one's value.
rem  NEXT holds the internal variable name, NEXTLABEL the flag as the user typed
rem  it -- messages must name the flag, not "SRC_INDEX".
if defined NEXT (
  set "NEXTCHK=%~1"
  if "!NEXTCHK:~0,2!"=="--" (
    echo [X] !NEXTLABEL! needs a value, but found the flag %~1 instead.
    echo     ^(use --help for usage^)
    exit /b 1
  )
  set "!NEXT!=%~1"
  set "NEXT="
  set "NEXTLABEL="
  shift
  goto :parse_loop
)

if /i "%~1"=="--check"   ( set "PRE=1" & shift & goto :parse_loop )
if /i "%~1"=="--verify"  ( set "VERIFY_ONLY=1" & shift & goto :parse_loop )
if /i "%~1"=="--cpu"     ( set "TARGET=cpu" & shift & goto :parse_loop )
if /i "%~1"=="--source"  ( set "NEXT=SRC_INDEX"  & set "NEXTLABEL=--source"  & shift & goto :parse_loop )
if /i "%~1"=="--proxy"   ( set "NEXT=PROXY"      & set "NEXTLABEL=--proxy"   & shift & goto :parse_loop )
if /i "%~1"=="--retries" ( set "NEXT=STEP_RETRIES" & set "NEXTLABEL=--retries" & shift & goto :parse_loop )
if /i "%~1"=="--help"    goto :usage
if /i "%~1"=="-h"        goto :usage
echo [X] Unknown option: %~1   ^(use --help for usage^)
exit /b 1

:usage
echo Usage: enable-gpu.cmd [options]
echo.
echo   (no option)        install the GPU runtime + OCR baseline
echo   --check            show what would be done, install nothing
echo   --verify           run the CUDA self-test only, install nothing
echo   --cpu              install the CPU OCR baseline only
echo   --source URL       use URL as the main package index, e.g.
echo                        --source https://pypi.tuna.tsinghua.edu.cn/simple
echo   --proxy ADDR       use a proxy, e.g. --proxy http://127.0.0.1:7890
echo   --retries N        retry a failed install N times (default 3)
echo   --help             this text
echo.
echo Environment fallbacks: QA_GPU_SOURCE, QA_GPU_PROXY, QA_GPU_RETRIES
echo.
echo Slow or interrupted download? A retry reuses pip's cache, so it does not
echo re-download finished packages. Switching to a mirror is usually fastest.
exit /b 1

rem ---------------------------------------------------------------------------
rem  :pip_args
rem  Build the pip argument string (source + proxy + network) into PIP_ARGS.
rem
rem    * With --source, that URL becomes the MAIN index (--index-url) and the
rem      official PyPI is attached as a supplement (--extra-index-url). The CUDA
rem      packages also exist on pypi.org, so keeping both guarantees resolution.
rem      Measured: the Tsinghua mirror carries all four packages.
rem    * Without --source: main index stays the default PyPI, and NVIDIA's index
rem      is attached for the CUDA packages.
rem
rem  NOTE: --index-url on the command line OUTRANKS environment variables and
rem  pip.conf, so it is only passed when the user explicitly asked for a source.
rem  Otherwise a user's own pip configuration would be silently overridden.
rem ---------------------------------------------------------------------------
:pip_args
set "PIP_ARGS="
if defined SRC_INDEX (
  set "PIP_ARGS=--index-url !SRC_INDEX! --extra-index-url %PYPI_INDEX%"
) else (
  set "PIP_ARGS=--extra-index-url %NVIDIA_INDEX%"
)
if defined PROXY set "PIP_ARGS=!PIP_ARGS! --proxy !PROXY!"
set "PIP_ARGS=!PIP_ARGS! %PIP_NET_OPTS%"
rem  --resume-retries only exists from pip 26 on; older pip aborts on it.
rem
rem  *** MEASURED TRAP *** -- do NOT write this as `!PY! -m pip install --help | findstr`:
rem  when PY holds a value WITH A SPACE (the preferred candidate is "py -3"), a
rem  command that feeds a PIPE is executed by a child cmd that treats the whole
rem  string as ONE program name -- it fails with "'py -3' is not recognized" and
rem  the error kills THIS SCRIPT mid-run (measured: execution stops at that line,
rem  exit code 1, and the caller's `call` never returns). The same command
rem  WITHOUT the pipe works, which is why this bug is easy to miss.
rem  Fix: dump the help to a temp file and search the file. No pipe, no problem.
!PY! -m pip install --help > "%TEMP%\qa-pip-help.txt" 2>nul
findstr /c:"--resume-retries" "%TEMP%\qa-pip-help.txt" >nul
del "%TEMP%\qa-pip-help.txt" >nul 2>nul
if not errorlevel 1 set "PIP_ARGS=!PIP_ARGS! --resume-retries 5"
exit /b 0

rem ---------------------------------------------------------------------------
rem  :check_source
rem  Probe the chosen source before downloading anything.
rem  Returns errorlevel 1 when it is unreachable.
rem
rem  Uses a temp .py file for the same reason the self-test does: this logic
rem  needs try/except, and cramming that into `python -c "..."` on a cmd command
rem  line means fighting both cmd's and Python's quoting at once. Measured
rem  earlier in this project: that fight is lost quietly, not loudly.
rem ---------------------------------------------------------------------------
:check_source
set "CHK_URL=%NVIDIA_INDEX%"
if defined SRC_INDEX set "CHK_URL=%SRC_INDEX%"
echo       Probing source: %CHK_URL% ...
set "CHKPY=%TEMP%\qa-src-probe.py"
> "%CHKPY%" echo import sys, urllib.request
>>"%CHKPY%" echo url = sys.argv[1]
>>"%CHKPY%" echo try:
>>"%CHKPY%" echo     urllib.request.urlopen(urllib.request.Request(url, method='HEAD'), timeout=20)
>>"%CHKPY%" echo     sys.exit(0)
>>"%CHKPY%" echo except Exception:
>>"%CHKPY%" echo     pass
>>"%CHKPY%" echo try:
>>"%CHKPY%" echo     urllib.request.urlopen(url, timeout=20).read(1)
>>"%CHKPY%" echo except Exception:
>>"%CHKPY%" echo     sys.exit(1)
%PY% "%CHKPY%" "%CHK_URL%"
set "CHK_RC=!errorlevel!"
del "%CHKPY%" >nul 2>nul
if not "!CHK_RC!"=="0" (
  echo       unreachable
  exit /b 1
)
echo       reachable
exit /b 0

rem ---------------------------------------------------------------------------
rem  :pip_retry
rem  Run the GPU install, retrying on failure with 5 / 10 / 20 second waits.
rem
rem  Why an outer loop when pip already has --retries: pip's --retries governs
rem  a SINGLE request, while one 1 GB install makes many. When the whole run
rem  fails part-way (connection killed, timeout, mirror hiccup) pip just exits
rem  and the user has to start over by hand. Wheels already downloaded stay in
rem  pip's cache, so a retry only costs the part that failed.
rem  Returns errorlevel 1 after the last attempt fails.
rem ---------------------------------------------------------------------------
:pip_retry
set /a "TRY_N=1"
set "WAIT_S=5"
set "MAX_TRY=%STEP_RETRIES%"
echo %MAX_TRY%| findstr /r "^[0-9][0-9]*$" >nul || set "MAX_TRY=3"
:pip_retry_loop
echo       Command: %PY% -m pip install %PIP_ARGS% %GPU_PACKAGES%
%PY% -m pip install --upgrade --disable-pip-version-check %PIP_ARGS% %GPU_PACKAGES%
if not errorlevel 1 exit /b 0
if !TRY_N! GEQ !MAX_TRY! (
  echo.
  echo [X] Install failed after !MAX_TRY! attempt^(s^).
  exit /b 1
)
echo.
echo [!] Attempt !TRY_N! failed. Retrying in !WAIT_S! seconds ^(already-downloaded
echo     packages are reused, not fetched again^)...
rem  ping is the portable sleep in cmd; "timeout" refuses to run without a console.
ping -n !WAIT_S! 127.0.0.1 >nul
set /a "TRY_N+=1"
set /a "WAIT_S*=2"
goto :pip_retry_loop

rem ---------------------------------------------------------------------------
rem  :write_probe
rem  Write the real self-test to %TEMP% and set PROBE to its path.
rem
rem  Why a FILE instead of `python -c "...":` the probe contains parentheses,
rem  quotes and dict lookups; keeping it out of the command line avoids cmd's
rem  quoting and escaping rules entirely. Those rules have already bitten this
rem  project twice -- a bare % inside a -c string got eaten, and a findstr
rem  end-anchor silently failed to match a perfectly good version string.
rem
rem  The DLL-directory discovery below mirrors `_nvidia_dll_dirs()` in
rem  deploy/ocr/qa_ocr.py ON PURPOSE -- all four glob layouts plus the
rem  os.path.isdir() filter. If this probe searched differently from the
rem  application, a green self-test would not prove the application works.
rem
rem  Escaping used below, inside a double-quoted echo:
rem    %%   -> a literal % in the file
rem    ^|   -> a literal | (otherwise it would be a pipe)
rem    ^(   -> a literal ( (harmless but explicit)
rem ---------------------------------------------------------------------------
:write_probe
set "PROBE=%TEMP%\qa-gpu-probe.py"
> "%PROBE%" echo import glob, os, sys
>>"%PROBE%" echo dirs = []
>>"%PROBE%" echo for entry in sys.path:
>>"%PROBE%" echo     if not entry or not os.path.isdir(entry):
>>"%PROBE%" echo         continue
>>"%PROBE%" echo     root = os.path.join(entry, 'nvidia')
>>"%PROBE%" echo     if not os.path.isdir(root):
>>"%PROBE%" echo         continue
>>"%PROBE%" echo     for pat in ('*/bin', '*/bin/x86_64', '*/*/bin', '*/*/bin/x86_64'):
>>"%PROBE%" echo         dirs += glob.glob(os.path.join(root, pat))
>>"%PROBE%" echo dirs = [d for d in dict.fromkeys(dirs) if os.path.isdir(d)]
>>"%PROBE%" echo print('PROBE nvidia dll dirs: %%d' %% len(dirs))
>>"%PROBE%" echo if dirs:
>>"%PROBE%" echo     os.environ['PATH'] = os.pathsep.join(dirs + [os.environ.get('PATH', '')])
>>"%PROBE%" echo try:
>>"%PROBE%" echo     import onnxruntime as ort
>>"%PROBE%" echo except Exception as e:
>>"%PROBE%" echo     print('PROBE import onnxruntime failed: %%s' %% e); sys.exit(1)
>>"%PROBE%" echo provs = ort.get_available_providers()
>>"%PROBE%" echo print('PROBE providers: %%s' %% '^|'.join(provs))
>>"%PROBE%" echo if 'CUDAExecutionProvider' not in provs:
>>"%PROBE%" echo     print('PROBE CUDA provider is not registered'); sys.exit(1)
>>"%PROBE%" echo try:
>>"%PROBE%" echo     import rapidocr
>>"%PROBE%" echo     model = os.path.join(os.path.dirname(rapidocr.__file__), 'models', 'ch_ppocr_mobile_v2.0_cls_mobile.onnx')
>>"%PROBE%" echo except Exception as e:
>>"%PROBE%" echo     print('PROBE import rapidocr failed: %%s' %% e); sys.exit(1)
>>"%PROBE%" echo if not os.path.exists(model):
>>"%PROBE%" echo     print('PROBE model missing: %%s' %% model); sys.exit(1)
>>"%PROBE%" echo so = ort.SessionOptions()
>>"%PROBE%" echo so.log_severity_level = 3
>>"%PROBE%" echo try:
>>"%PROBE%" echo     sess = ort.InferenceSession(model, sess_options=so, providers=['CUDAExecutionProvider', 'CPUExecutionProvider'])
>>"%PROBE%" echo except Exception as e:
>>"%PROBE%" echo     print('PROBE session failed: %%s' %% e); sys.exit(1)
>>"%PROBE%" echo actual = sess.get_providers()
>>"%PROBE%" echo print('PROBE session providers: %%s' %% '^|'.join(actual))
>>"%PROBE%" echo sys.exit(0 if actual and actual[0] == 'CUDAExecutionProvider' else 1)
exit /b 0

rem ---------------------------------------------------------------------------
rem  :install_baseline
rem  Install what OCR needs regardless of GPU: rapidocr (the actual recognizer)
rem  and pymupdf (PDF -> bitmap). onnxruntime alone does nothing.
rem
rem  Installed together with the GPU package, pip will NOT also pull the CPU
rem  onnxruntime: rapidocr's dependency on `onnxruntime` is already satisfied by
rem  onnxruntime-gpu (same import name, same target directory).
rem
rem  Deliberately does NOT name onnxruntime here. Naming it would install the
rem  CPU build over an existing GPU one -- measured, that silently downgraded a
rem  user's working onnxruntime-gpu to the CPU distribution.
rem
rem  Returns errorlevel 1 on failure, 0 otherwise (including the --check no-op).
rem ---------------------------------------------------------------------------
:install_baseline
if defined PRE (
  echo       --check: skipping.
  exit /b 0
)
call :pip_args
echo       Command: %PY% -m pip install %PIP_ARGS% rapidocr pymupdf
%PY% -m pip install --upgrade --disable-pip-version-check %PIP_ARGS% rapidocr pymupdf
if errorlevel 1 (
  echo.
  echo [X] Could not install rapidocr / pymupdf.
  echo     Check the network, or try a mirror / proxy ^(see --help^), then retry.
  exit /b 1
)
exit /b 0

rem ---------------------------------------------------------------------------
rem  :try_python <exe> <extra-args>
rem  Probe one Python candidate. Sets PY only when it actually executes and
rem  reports something that looks like a version, so Store redirect stubs
rem  (WindowsApps\python3.exe -- it opens the Store instead of running Python)
rem  are skipped rather than chosen and then failing.
rem
rem  NOTE: do NOT put a percent-format such as "%d.%d" into the -c string --
rem  cmd treats a bare % specially and eats it, which made every candidate
rem  look broken (measured). And do NOT validate with `findstr /r "^...$"`:
rem  measured, the end-anchor silently failed to match a perfectly good
rem  "3.13.13". Batch numeric comparison is both simpler and reliable.
rem ---------------------------------------------------------------------------
:try_python
where %~1 >nul 2>&1 || exit /b 0
set "CAND=%~1 %~2"
set "PROBEV="
set "M="
set "N="
for /f "delims=" %%v in ('!CAND! -c "import sys;print(sys.version.split()[0])" 2^>nul') do set "PROBEV=%%v"
if not defined PROBEV (
  echo [i] skipping unusable candidate: !CAND!  ^(exists but cannot run^)
  exit /b 0
)
rem  set /a with delims extracts the two numeric fields straight out of
rem  "3.13.13"; a non-numeric field lands as 0, which then fails the
rem  "major >= 3" check below. No string matching involved.
for /f "tokens=1,2 delims=." %%a in ("!PROBEV!") do set /a "M=%%a" 2>nul & set /a "N=%%b" 2>nul
if !M! LSS 3 (
  echo [i] skipping unusable candidate: !CAND!  ^(unexpected version "!PROBEV!"^)
  exit /b 0
)
set "PY=!CAND!"
set "PYVER=!PROBEV!"
set "PYM=!M!"
set "PYN=!N!"
for /f "delims=" %%v in ('!CAND! -c "import platform;print(platform.machine())" 2^>nul') do set "PYARCH=%%v"
exit /b 0

rem ---------------------------------------------------------------------------
rem  Python older than 3.10 cannot install the OCR dependencies.
rem ---------------------------------------------------------------------------
:old_python
echo [X] Python !PYVER! is too old. The OCR stack needs Python 3.10 or newer.
echo     Install a newer Python, or keep using CPU ^(unchanged by this script^).
goto :fail
