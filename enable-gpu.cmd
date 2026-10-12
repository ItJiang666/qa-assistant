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
rem  WHAT IT DOES
rem    1. locate a Python interpreter
rem    2. check the NVIDIA driver (nvidia-smi) and the GPU generation
rem    3. pick the CUDA 13 index (default) or the CUDA 12 index (older cards)
rem    4. install onnxruntime-gpu with the CUDA/cuDNN runtime libraries
rem    5. VERIFY that CUDAExecutionProvider really shows up
rem    6. print a plain-language verdict
rem
rem  NOTE: this file must stay pure ASCII. cmd.exe reads .cmd files using the
rem  system code page (GBK on Chinese Windows); any UTF-8 Chinese text inside
rem  would be decoded as garbage and can break parsing. Chinese messages from
rem  the Python child process are handled correctly (it reconfigures UTF-8).
rem ===========================================================================

set "PY="
set "CUDA_INDEX=https://pypi.nvidia.com"
set "ORT_SPEC=onnxruntime-gpu[cudnn]"
set "TARGET="
set "PRE="

rem ---- parse arguments ------------------------------------------------------
if /i "%~1"=="--check" set "PRE=1"
if /i "%~1"=="--cpu"   set "TARGET=cpu"

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
set "PYVER="
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

rem  Show WHICH environment is about to be modified and let the user back out.
rem  Measured pitfall: pre-pending a venv to PATH did NOT make the venv's python
rem  win here, so dependencies silently landed in the main environment instead.
rem  Printing the absolute interpreter path is the cheap guard against that.
if not defined PRE (
  set /p "ANS=      Install into THIS environment? [Y/n] "
  if /i "!ANS!"=="n" (
    echo [i] Cancelled. Nothing was changed.
    goto :end
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
  goto :done_cpu
)
echo [2/6] GPU    : !GPUNAME!
echo       driver : !DRV!

rem  CUDA 13 needs driver >= 580. Parse the major version and compare.
set "DRVMAJOR="
for /f "tokens=1 delims=." %%m in ("!DRV!") do set "DRVMAJOR=%%m"
set /a "DRVNUM=DRVMAJOR" 2>nul
if not defined DRVNUM set "DRVNUM=0"

if !DRVNUM! GEQ 580 (
  echo [3/6] CUDA   : 13  ^(driver !DRV! ^>= 580^)
) else (
  echo [3/6] CUDA   : 12  ^(driver !DRV! ^< 580, using the CUDA 12 build^)
  set "CUDA_INDEX=https://aiinfra.pkgs.visualstudio.com/PublicPackages/_packaging/onnxruntime-cuda-12/pypi/simple/"
  set "ORT_SPEC=onnxruntime-gpu==1.29.0"
)

rem  Very old cards: CUDA 12 dropped Maxwell/Pascal support in the runtime,
rem  so even the CUDA 12 build will not help. Say so instead of letting the
rem  user download 1 GB for nothing.
echo !GPUNAME! | findstr /i /c:"GTX 10" /c:"GTX 9" /c:"GTX 7" /c:"GT 10" /c:"GT 9" /c:"GT 7" /c:"Quadro K" /c:"Quadro P" /c:"Quadro M" >nul
if not errorlevel 1 (
  echo.
  echo [i] This GPU generation ^(GTX 10-series / older^) is no longer supported by
  echo     the CUDA runtimes that onnxruntime-gpu needs. The install would
  echo     succeed but CUDAExecutionProvider would fail to start, so we skip it
  echo     rather than waste a ~1 GB download.
  echo     Installing the CPU OCR baseline instead -- it already got a ~6x
  echo     speedup in v1.28 and needs no extra downloads.
  call :install_baseline
  goto :done_cpu
)

if defined TARGET (
  if "!TARGET!"=="cpu" (
    echo [i] --cpu given: skipping the GPU runtime.
    echo     Installing the CPU OCR baseline only.
    call :install_baseline
    goto :done_cpu
  )
)

rem ---- 4/5. OCR baseline, ALWAYS install -------------------------------------
rem  onnxruntime is only the INFERENCE BACKEND. What actually reads text is
rem  `rapidocr`, and PDF pages are rasterized by `pymupdf` -- neither is pulled
rem  in by onnxruntime-gpu. So a machine that only ran this script would still
rem  have no working OCR. Install the baseline here too.
rem
rem  No conflict with the GPU package: rapidocr only depends on `onnxruntime`
rem  (the CPU distribution). Once onnxruntime-gpu is present, pip treats that
rem  requirement as satisfied and does NOT pull the CPU build in as well.
rem
rem  Bonus effect: a machine with no usable GPU still ends up with a working
rem  CPU setup from this single command -- the safest possible outcome.
echo [4/6] Ensuring the OCR baseline ^(rapidocr, pymupdf^)...
call :install_baseline
if errorlevel 1 goto :fail
echo.

rem ---- 5/6. GPU runtime ------------------------------------------------------
echo [5/6] About to install:  %ORT_SPEC%
echo       from index:       !CUDA_INDEX!
echo.
if defined PRE (
  echo [i] --check given: NOT installing. Re-run without --check to proceed.
  goto :done_plan
)

echo       Downloading ~1.07 GB ^(needs a working internet connection^).
echo       This may take a few minutes. Do not close this window.
echo.
%PY% -m pip install --upgrade --disable-pip-version-check ^
  --extra-index-url !CUDA_INDEX! "%ORT_SPEC%"
if errorlevel 1 (
  echo.
  echo [X] pip install failed. Common causes:
  echo     - network blocked or proxy required
  echo     - the NVIDIA index is unreachable from your network
  echo     Nothing was broken: if onnxruntime was already installed it is
  echo     still there, and the application keeps working on CPU.
  goto :fail
)
echo.

rem ---- 5. verify ------------------------------------------------------------
echo [6/6] Verifying...
%PY% -c "import onnxruntime as o;s=o.get_available_providers();print('PYVER',o.__version__);print('PROVIDERS','|'.join(s));print('HASCUDA','CUDAExecutionProvider' in s)"
if errorlevel 1 (
  echo [X] onnxruntime could not be imported after install. Application will
  echo     fall back to CPU automatically. Reinstall with:
  echo       %PY% -m pip install --force-reinstall onnxruntime
  goto :fail
)

echo.
echo ============================================================
for /f "delims=" %%p in ('%PY% -c "import onnxruntime as o;s=o.get_available_providers();print('YES' if 'CUDAExecutionProvider' in s else 'NO')" 2^>nul') do set "HASCUDA=%%p"
if /i "!HASCUDA!"=="YES" (
  echo   [OK] GPU acceleration is ENABLED.
  echo.
  echo   Restart the application, then recognize any scanned file and look
  echo   for this line in the log ^(%%USERPROFILE%%\.qa-assistant\logs^):
  echo       OCR using GPU
  echo   Expected speedup on a 38-page scan: about 47s -^> 17s.
) else (
  echo   [!] CUDAExecutionProvider did NOT appear.
  echo.
  echo   The application will automatically fall back to CPU -- nothing is
  echo   broken, you just will not get the speedup. Most likely causes:
  echo     - driver too old for the CUDA runtime that got installed
  echo     - GPU generation not supported
  echo   Try updating the NVIDIA driver, then run this script again.
)
echo ============================================================
goto :end

:done_cpu
echo.
echo [i] Nothing was changed. OCR continues to run on CPU.
echo     CPU timings ^(measured^): 38-page scan about 47 seconds after the
echo     v1.28 optimizations. That is 6.6x faster than before them.
goto :end

:done_plan
echo.
echo [i] Dry run finished. The command that WOULD run is:
echo       %PY% -m pip install --extra-index-url !CUDA_INDEX! "%ORT_SPEC%"
goto :end

:fail
echo.
exit /b 1

rem ---------------------------------------------------------------------------
rem  :install_baseline
rem  Install what OCR needs regardless of GPU: rapidocr (the actual recognizer)
rem  and pymupdf (PDF -> bitmap). onnxruntime alone does nothing.
rem
rem  Installed together with the GPU package, pip will NOT also pull the CPU
rem  onnxruntime: rapidocr's dependency on `onnxruntime` is already satisfied by
rem  onnxruntime-gpu (same import name, same target directory).
rem
rem  Returns errorlevel 1 on failure, 0 otherwise (including the --check no-op).
rem ---------------------------------------------------------------------------
:install_baseline
if defined PRE (
  echo       --check: skipping.
  exit /b 0
)
%PY% -m pip install --upgrade --disable-pip-version-check rapidocr pymupdf
if errorlevel 1 (
  echo.
  echo [X] Could not install rapidocr / pymupdf. Check the network, then retry.
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
set "PROBE="
set "M="
set "N="
for /f "delims=" %%v in ('!CAND! -c "import sys;print(sys.version.split()[0])" 2^>nul') do set "PROBE=%%v"
if not defined PROBE (
  echo [i] skipping unusable candidate: !CAND!  ^(exists but cannot run^)
  exit /b 0
)
rem  set /a with delims extracts the two numeric fields straight out of
rem  "3.13.13"; a non-numeric field lands as 0, which then fails the
rem  "major >= 3" check below. No string matching involved.
for /f "tokens=1,2 delims=." %%a in ("!PROBE!") do set /a "M=%%a" 2>nul & set /a "N=%%b" 2>nul
if !M! LSS 3 (
  echo [i] skipping unusable candidate: !CAND!  ^(unexpected version "!PROBE!"^)
  exit /b 0
)
set "PY=!CAND!"
set "PYVER=!PROBE!"
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

:end
echo.
rem  Do not block automation: pause only when a human is watching.
if defined PRE goto :eof
echo Press any key to close this window...
pause >nul
exit /b 0
