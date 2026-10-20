@echo off
setlocal EnableDelayedExpansion
rem ===========================================================================
rem  enable-gpu.cmd -- install the GPU runtime for OCR (Windows)
rem ===========================================================================
rem  enable-gpu.cmd             install and verify
rem  enable-gpu.cmd --check     print detection result only, install nothing
rem  enable-gpu.cmd --verify    verify the current state only
rem  enable-gpu.cmd --cpu       skip GPU, install the CPU baseline only
rem  enable-gpu.cmd --help      usage
rem
rem  Flow: find Python -> print GPU and driver -> threshold check -> install ->
rem        real forward-pass verification.
rem  Fails fast when a threshold is not met. No tier fallback, no mirror speed
rem  test. Set HTTPS_PROXY in the environment if the download needs a proxy.
rem ===========================================================================

cd /d "%~dp0"

set "PRE="
set "VERIFY_ONLY="
set "TARGET=gpu"
set "SHOW_HELP="
set "STEP_RETRIES=3"
set "PY="
set "PYVER="
set "GPU_NAME="
set "DRV="
set "CAP="
set "PYMAJ=0"
set "PYMIN=0"

set "PYPI_INDEX=https://pypi.org/simple"
set "NVIDIA_INDEX=https://pypi.nvidia.com"
set "MIN_DRIVER=580"
set "MIN_CAPNUM=75"

rem CUDA 13 runtime. --force-reinstall is required in the install step:
rem different onnxruntime-gpu versions share one directory, so pip would
rem otherwise treat the requirement as satisfied and keep the old files.
set "GPU_SPEC=onnxruntime-gpu==1.31.0"
set "GPU_DEPS=nvidia-cudnn-cu13~=9.0 nvidia-cublas nvidia-cuda-nvrtc~=13.0 nvidia-cufft~=12.0 nvidia-curand~=10.0 nvidia-cuda-runtime~=13.0 nvidia-nvjitlink"
set "BASELINE_PKGS=rapidocr pymupdf"

for %%a in (%*) do (
  if /i "%%a"=="--check"  set "PRE=1"
  if /i "%%a"=="--verify" set "VERIFY_ONLY=1"
  if /i "%%a"=="--cpu"    set "TARGET=cpu"
  if /i "%%a"=="--help"   set "SHOW_HELP=1"
  if /i "%%a"=="-h"       set "SHOW_HELP=1"
)

echo ============================================================
echo   qa-assistant  -  enable GPU acceleration for OCR
echo ============================================================
echo.

if defined SHOW_HELP (
  echo   enable-gpu.cmd             install and verify
  echo   enable-gpu.cmd --check     print detection result only, install nothing
  echo   enable-gpu.cmd --verify    verify the current state only
  echo   enable-gpu.cmd --cpu       skip GPU, install the CPU baseline only
  echo.
  echo   Fails fast when the GPU, its compute capability or the driver does not
  echo   meet the CUDA 13 requirement. Proxy: set HTTPS_PROXY before running.
  goto :end
)

rem ---- 1. Python ----------------------------------------------------------
call :try_py python
if not defined PY call :try_py python3
if not defined PY call :try_py py -3
if not defined PY (
  echo [X] No usable Python found. OCR needs Python 3.10+.
  goto :fail
)
echo [1/5] Python : !PYVER!  ^(!PY!^)

call :version_parts
if !PYMAJ! LSS 3 (
  echo [X] Python !PYVER! is too old, 3.10+ is required.
  goto :fail
)
if !PYMAJ! EQU 3 if !PYMIN! LSS 10 (
  echo [X] Python !PYVER! is too old, 3.10+ is required.
  goto :fail
)

if defined VERIFY_ONLY (
  echo.
  echo [verify] verifying only, nothing is installed...
  call :verify
  if errorlevel 1 (
    echo [warn] GPU is not active right now; the app falls back to CPU.
    echo     Re-run without --verify to install.
    goto :fail
  )
  echo [OK] GPU acceleration is available.
  goto :end
)

rem ---- 2. GPU and driver --------------------------------------------------
call :nvidia_info
if not defined GPU_NAME (
  echo [2/5] GPU     : not detected
) else (
  echo [2/5] GPU     : !GPU_NAME!
  if defined CAP (
    echo       driver  : !DRV!  ^(compute capability !CAP!^)
  ) else (
    echo       driver  : !DRV!
  )
)

rem ---- 3. thresholds ------------------------------------------------------
if /i "!TARGET!"=="cpu" (
  echo [3/5] --cpu given, skipping the GPU runtime.
  call :install_baseline
  if errorlevel 1 goto :fail
  echo [OK] CPU OCR baseline installed.
  goto :end
)
call :check_thresholds
if errorlevel 1 goto :fail
echo [3/5] threshold check passed, target interpreter: %PY%

if defined PRE (
  echo.
  echo [i] --check: detection only, nothing installed.
  echo     Re-run without --check to install.
  goto :end
)

rem ---- 4. install ---------------------------------------------------------
echo.
echo [4/5] installing OCR baseline and GPU runtime ^(official source, ~1.5 GB^)...
call :install_baseline
if errorlevel 1 goto :fail
call :install_gpu
if errorlevel 1 goto :fail

rem ---- 5. verify ----------------------------------------------------------
echo.
echo [5/5] verifying: running one inference on CUDA and comparing with CPU...
call :verify
if errorlevel 1 (
  echo.
  echo [X] GPU installed but not usable. The app falls back to CPU.
  echo     To clean up and retry: %PY% -m pip uninstall -y onnxruntime-gpu
  goto :fail
)
echo.
echo ============================================================
echo   [OK] GPU acceleration is enabled and verified.
echo   Restart the app, recognize a scanned file, and look for
echo   "GPU" in %%USERPROFILE%%\.qa-assistant\logs.
echo   A 38-page scan: about 47 s on CPU -^> about 17 s on GPU.
echo ============================================================
goto :end

rem ===========================================================================
rem  subroutines
rem ===========================================================================

rem :try_py <candidate> [extra arg] -- set PY/PYVER when the candidate runs
:try_py
set "CAND=%~1"
if not "%~2"=="" set "CAND=%~1 %~2"
%CAND% -V >nul 2>&1 || exit /b 0
for /f "tokens=2" %%v in ('%CAND% -V 2^>^&1') do set "PYVER=%%v"
if not defined PYVER exit /b 0
set "PY=%CAND%"
exit /b 0

rem split PYVER into PYMAJ / PYMIN
:version_parts
for /f "tokens=1,2 delims=." %%a in ("!PYVER!") do (
  set "PYMAJ=%%a"
  set "PYMIN=%%b"
)
if not defined PYMAJ set "PYMAJ=0"
if not defined PYMIN set "PYMIN=0"
exit /b 0

rem read GPU name, driver version and compute capability into temp files:
rem nvidia-smi's --format=csv,noheader contains a comma, which for /f would
rem treat as a separator, so the output is captured with set /p instead
:nvidia_info
set "NV_TMP=%TEMP%\qa-gpu-info.txt"
nvidia-smi --query-gpu=name --format=csv,noheader > "!NV_TMP!" 2>nul
if exist "!NV_TMP!" set /p GPU_NAME=<"!NV_TMP!"
nvidia-smi --query-gpu=driver_version --format=csv,noheader > "!NV_TMP!" 2>nul
if exist "!NV_TMP!" set /p DRV=<"!NV_TMP!"
rem compute_cap is unsupported on older drivers; an empty value just skips the check
nvidia-smi --query-gpu=compute_cap --format=csv,noheader > "!NV_TMP!" 2>nul
if exist "!NV_TMP!" set /p CAP=<"!NV_TMP!"
del "!NV_TMP!" >nul 2>nul
exit /b 0

:check_thresholds
if not defined GPU_NAME (
  echo [X] No NVIDIA GPU detected, GPU acceleration is not possible.
  echo     nvidia-smi is missing or the driver is not installed.
  exit /b 1
)
if defined CAP (
  set /a "CAPNUM=0"
  for /f "tokens=1,2 delims=." %%a in ("!CAP!") do set /a "CAPNUM=%%a*10+%%b"
  if !CAPNUM! LSS !MIN_CAPNUM! (
    echo [X] GPU is too old: !GPU_NAME! ^(compute capability !CAP!, need 7.5^).
    echo     CUDA 13 requires Turing or newer ^(GTX 16 / RTX 20 series and up^).
    exit /b 1
  )
)
set "DRVMAJOR=0"
for /f "tokens=1 delims=." %%a in ("!DRV!") do set "DRVMAJOR=%%a"
if !DRVMAJOR! LSS !MIN_DRIVER! (
  echo [X] Driver !DRV! is too old, !MIN_DRIVER! or newer is required.
  echo     Update the NVIDIA driver and re-run.
  exit /b 1
)
exit /b 0

:confirm
set "ANSWER="
echo       target interpreter: %PY%
exit /b 0

:install_baseline
if defined PRE (
  echo       --check: skipping the baseline install.
  exit /b 0
)
set "PIP_CMD=%PY% -m pip install --upgrade --disable-pip-version-check --retries 10 --timeout 60 --index-url %PYPI_INDEX% %BASELINE_PKGS%"
call :retry_pip "install OCR baseline"
exit /b %errorlevel%

:install_gpu
set "PIP_CMD=%PY% -m pip install --upgrade --force-reinstall --disable-pip-version-check --retries 10 --timeout 60 --index-url %PYPI_INDEX% --extra-index-url %NVIDIA_INDEX% %GPU_SPEC% %GPU_DEPS%"
call :retry_pip "install GPU runtime"
exit /b %errorlevel%

rem :retry_pip <label> -- run %PIP_CMD% with backoff 5/10/20s
:retry_pip
set "LABEL=%~1"
set /a ATTEMPT=0
set /a WAIT=5
:retry_loop
call %PIP_CMD%
if not errorlevel 1 exit /b 0
set /a ATTEMPT+=1
if !ATTEMPT! GEQ !STEP_RETRIES! (
  echo [X] !LABEL! failed after !STEP_RETRIES! attempts.
  exit /b 1
)
echo [warn] !LABEL! attempt !ATTEMPT! failed, retrying in !WAIT! s
timeout /t !WAIT! /nobreak >nul
set /a WAIT*=2
goto :retry_loop

:verify
set "PROBE=%~dp0deploy\ocr\qa_gpu_probe.py"
if not exist "!PROBE!" set "PROBE=%~dp0qa_gpu_probe.py"
if not exist "!PROBE!" (
  echo [X] qa_gpu_probe.py not found, cannot verify.
  exit /b 1
)
%PY% "!PROBE!" --require numpy onnxruntime
exit /b %errorlevel%

:fail
if not defined QA_NO_PAUSE pause
endlocal
exit /b 1

:end
if not defined QA_NO_PAUSE pause
endlocal
exit /b 0
