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
rem  Per-tier driver floors. They only decide whether a tier is WORTH TRYING --
rem  whether it actually works is decided by the real CUDA session self-test.
rem  Measured from PyPI metadata (2026-10-13):
rem    1.31.0          -> nvidia-cudnn-cu13 / nvidia-cuda-nvrtc~=13.0  (CUDA 13)
rem    1.21.0 ~ 1.26.0 -> nvidia-cudnn-cu12 / nvidia-cuda-nvrtc-cu12   (CUDA 12)
rem    1.20.0 and older -> declares NO nvidia deps at all             (install manually)
rem
rem  NOTE on a mistake worth not repeating: an earlier version sampled only
rem  1.27 / 1.28 / 1.29, found they declare cu13, and concluded "no usable CUDA 12
rem  build exists". Wrong -- 1.21~1.26 ARE genuine CUDA 12. List all candidates
rem  before declaring a path dead.
set "CUDA13_MIN_DRIVER=580"
set "CUDA12_MIN_DRIVER=525"
set "CUDA11_MIN_DRIVER=450"

rem  GPU runtime tiers, newest first. The script tries them in order and steps
rem  down whenever the real self-test cannot build a CUDA session.
rem  Columns: code | label | driver floor | arch need | onnxruntime spec | deps
rem
rem  DO NOT go back to the `onnxruntime-gpu[cudnn]` extra: measured on a clean
rem  machine it pulled in NO CUDA runtime at all (only the 160 MB main wheel),
rem  which is why the user saw only ~300 MB downloaded with no GPU effect.
rem  Deps must always be listed explicitly.
rem  NOTE: built up line by line with `>>` appends rather than one multi-line
rem  `set`. cmd cannot span a `set` across physical lines, and `^` line
rem  continuation does NOT work reliably here -- measured: the cu12 / cu11 rows
rem  still ended up executed as commands ("'cu12' is not recognized"). Appending
rem  to a temp file is the form that works, and it is also exactly what the tier
rem  lookup reads back, so the table exists in only one place.
set "TIER_FILE=%TEMP%\qa-gpu-tiers.txt"
> "%TIER_FILE%"  echo cu13^|CUDA 13^|580^|turing^|onnxruntime-gpu==1.31.0^|nvidia-cudnn-cu13~=9.0 nvidia-cublas nvidia-cuda-nvrtc~=13.0
>>"%TIER_FILE%" echo cu12^|CUDA 12^|525^|turing^|onnxruntime-gpu==1.26.0^|nvidia-cudnn-cu12~=9.0 nvidia-cublas-cu12 nvidia-cuda-nvrtc-cu12~=12.0
>>"%TIER_FILE%" echo cu11^|CUDA 11.8^|450^|maxwell^|onnxruntime-gpu==1.19.2^|nvidia-cudnn-cu11~=9.0 nvidia-cublas-cu11 nvidia-cuda-nvrtc-cu11~=11.8
set "NVIDIA_INDEX=https://pypi.nvidia.com"
set "PYPI_INDEX=https://pypi.org/simple"

rem  (The GPU package list now lives in the TIER_FILE table written above --
rem   one list per tier, because each tier needs a different CUDA generation.)
set "GPUN_FILE=%TEMP%\qa-gpu-name.txt"

rem  Auto source selection: the winning index is written to a temp FILE and read
rem  back from there. Why a file instead of parsing the child's stdout: the
rem  picker prints Chinese in UTF-8 while cmd's `for /f` decodes with the console
rem  code page, so parsing stdout yields mojibake. A one-line file avoids that.
set "SRC_FILE=%TEMP%\qa-gpu-source.txt"
set "SPEEDTEST_MB=%QA_GPU_SPEEDTEST_MB%"
if not defined SPEEDTEST_MB set "SPEEDTEST_MB=2"

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

rem ---- 3. work out which tiers are worth trying ------------------------------
rem  The driver version NO LONGER decides "can this machine use the GPU". That
rem  judgement is what locked an RTX 4060 + driver 560.94 out of a working
rem  install. Driver version only filters which tiers are worth a download; the
rem  real self-test in step 6 decides whether a tier actually works.
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
echo [3/6] driver major : !DRVNUM!  ^(used to pick candidate tiers^)

set "CANDIDATES="
for /f "usebackq tokens=1 delims=|" %%c in (`findstr /b /c:"cu13" /c:"cu12" /c:"cu11" "!TIER_FILE!"`) do (
  call :tier_eligible "%%c"
  if !errorlevel! EQU 0 set "CANDIDATES=!CANDIDATES! %%c"
)
if not defined CANDIDATES (
  echo.
  echo [X] No GPU runtime tier applies to this machine.
  echo     Driver !DRV! is too old even for CUDA 11.8 ^(needs ^>= %CUDA11_MIN_DRIVER%^),
  echo     or the GPU generation is older than Maxwell.
  echo     Please UPDATE YOUR NVIDIA DRIVER and run this script again -- that is
  echo     free and does not require changing the GPU.
  echo     CPU OCR is installed meanwhile and works ^(38 pages in ~47 s^).
  call :install_baseline
  if errorlevel 1 goto :fail
  goto :done_cpu
)
echo       tiers to try  :!CANDIDATES!

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

rem ---- 5+6. try tiers in order, self-test each, step down on failure ---------
echo [5/6] Will try these tiers in order:!CANDIDATES!
echo       A tier is accepted only after a REAL forward pass succeeds in an
echo       isolated temp venv. The driver floor only decides what is worth trying.
echo.
if defined PRE (
  echo [i] --check given: NOT installing. Re-run without --check to proceed.
  echo     ^(auto source selection and the reachability probe are skipped too, so
  echo      --check does not download a few MB just to measure speed^)
  goto :done_plan
)

rem  No --source given: measure the mirrors and use the fastest one.
if not defined SRC_INDEX call :auto_pick_source
if defined SRC_INDEX (
  echo       from index: !SRC_INDEX!
) else (
  echo       from index: %NVIDIA_INDEX%  ^(official^)
)
call :pip_args
echo       network   : %PIP_ARGS%
echo.

echo       Downloading up to ~1.5 GB ^(needs a working internet connection^).
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

rem  ==========================================================================
rem  v1.35 -- TRY -> VERIFY -> COMMIT, instead of "install then hope".
rem
rem  WHAT WAS WRONG (measured 2026-10-14, on a CLEAN Windows machine, RTX 4060
rem  Laptop + driver 596.36 + Python 3.13.16):
rem
rem    the old flow installed a tier straight into the user environment and then
rem    ran a self-test that only checked whether a CUDA session could be BUILT.
rem    It printed:
rem        PROBE session providers: CUDAExecutionProvider|CPUExecutionProvider
rem        [OK] GPU acceleration is ENABLED -- and a CUDA session was really built.
rem    ...and then real OCR produced ZERO text on all 38 pages, with onnxruntime
rem    logging CUDNN failure 5003: CUDNN_STATUS_EXECUTION_FAILED_CUDART.
rem
rem  So "provider registered + DLLs loaded + session built" can ALL be true while
rem  the GPU still cannot compute. The old self-test therefore reported a 100%
rem  false pass on a clean machine, and left the broken state installed.
rem
rem  The fix: install each candidate into an ISOLATED TEMP VENV, and accept it
rem  only after a REAL forward pass matches CPU output element-wise. A tier that
rem  cannot compute never touches the user environment. The verification itself
rem  lives in deploy\ocr\qa_gpu_probe.py so the .cmd and .sh versions cannot
rem  drift apart (a drift here is exactly what makes "self-test passed" mean
rem  nothing).
rem
rem  COST: worst case is ~1 GB per candidate tier; only the accepted tier is
rem  installed for real. Slower than before, but it cannot hand you a GPU that
rem  does not work -- which is the whole point.
rem  ==========================================================================

set "PROBE_JSON="
set "PROBE_REASON="
set "PROBE_EVIDENCE="
set "TIER_OK="
set "TIER_TRIED=0"
for %%c in (!CANDIDATES!) do (
  set /a "TIER_TRIED+=1"
  echo.
  echo ------------------------------------------------------------
  echo [!TIER_TRIED!] trying tier: %%c
  echo ------------------------------------------------------------
  call :show_tier "%%c"
  call :probe_candidate "%%c"
  if "!PROBE_RC!"=="0" set "TIER_OK=%%c"
  if not defined TIER_OK (
    echo.
    echo [!] Tier %%c did not pass verification -- not usable on this machine.
    echo     reason: !PROBE_REASON!
    if defined PROBE_EVIDENCE (
      echo     evidence from onnxruntime:
      echo !PROBE_EVIDENCE!
    )
  )
  if defined TIER_OK goto :tier_done
)
:tier_done
if defined TIER_OK goto :commit_tier

echo.
echo ============================================================
echo   [!] No candidate tier passed verification.
echo
echo   This check exists because a CUDA session can be BUILT successfully and
echo   still fail on the very first inference -- measured on a clean machine:
echo   provider registered, DLLs loaded, session created, and then
echo     CUDNN failure 5003: CUDNN_STATUS_EXECUTION_FAILED_CUDART
echo   with every page OCRing to nothing. The old self-test called that
echo   "enabled" and left the broken stack installed.
echo
echo   The per-tier reasons are printed above, with the raw onnxruntime lines.
echo   Common causes:
echo     - driver older than the CUDA runtime that got installed -> update driver
echo     - GPU generation no longer supported by the CUDA runtime -> CPU only
echo     - two CUDA generations mixed in one site-packages -> see the hint below
echo     - a broken download -> just re-run; finished wheels are reused
echo   OCR still works on CPU meanwhile.
echo ============================================================
goto :fail

rem ---- COMMIT: only now does anything touch the user environment -----------
:commit_tier
echo.
echo ============================================================
echo   Tier !TIER_OK! passed a REAL forward pass in a temp environment.
echo   Installing it into the real interpreter now: %PY%
echo ============================================================
call :purge_gpu_tiers
call :install_tier_with_fallback "!TIER_OK!"
if errorlevel 1 (
  echo.
  echo [X] The verified tier failed to install into the real environment.
  echo     The isolated run proved this combination works on this machine, so
  echo     re-running the script is enough ^(finished wheels are reused^).
  goto :fail
)
echo.
echo       Re-verifying inside the REAL environment ^(not optional: an isolated
echo       venv is NOT the same as the interpreter you already have^)...
call :run_probe
if not "!PROBE_RC!"=="0" (
  echo.
  echo [X] Passed in isolation, failed here -- something in this interpreter is
  echo     interfering. Most common cause: TWO CUDA GENERATIONS MIXED in the
  echo     same site-packages ^(pip keeps separate records, the files collide^).
  echo     Clean up and re-run:
  echo       %PY% -m pip uninstall -y onnxruntime onnxruntime-gpu
  echo       %PY% -m pip list --format=freeze ^| findstr /i nvidia
  echo     then uninstall every name that command lists, and re-run this script.
  goto :fail
)

if defined TIER_OK goto :selftest
echo.
echo ============================================================
echo   [!] Every candidate tier failed to build a CUDA session.
echo
echo   This check exists on purpose: with only onnxruntime-gpu installed,
echo   get_available_providers^(^) STILL lists CUDAExecutionProvider, but
echo   creating a session cannot load the CUDA DLLs and falls back to CPU
echo   without any error. The PROBE lines above show where it stopped.
echo   Common causes:
echo     - driver too old even for CUDA 11.8 ^(needs ^>= %CUDA11_MIN_DRIVER%^)
echo     - GPU generation older than Maxwell, which CUDA dropped
echo     - a broken download ^(just re-run; finished wheels are reused^)
echo   OCR still works on CPU meanwhile.
echo ============================================================
goto :fail

rem ---- 6. REAL self-test: build a CUDA session (caveat 3) -------------------
rem  Also the landing point for --verify, hence the conditional label text.
:selftest
if defined VERIFY_ONLY (
  echo [verify] Self-test: building a CUDA session from a bundled model ^(~3 s^)...
) else (
  echo [6/6] Self-test ^(tier !TIER_OK!^): building a CUDA session ^(~3 s^)...
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
echo [i] Dry run finished. The pip commands that WOULD run are printed above,
echo     one per candidate tier, each with its own CUDA generation.
goto :end

:fail
echo.
exit /b 1

:end
echo.
rem  Clean up the temp file the tier table was written to.
del "%TIER_FILE%" >nul 2>nul
rem  Do not block automation: pause only when a human is watching.
if defined PRE goto :eof
if defined VERIFY_ONLY goto :eof
echo Press any key to close this window...
pause >nul
exit /b 0

rem ---------------------------------------------------------------------------
rem  :arch_rank <gpu name>   -> sets ARCH_RANK
rem  Coarse generation rank, newest highest. The CUDA runtimes are compiled per
rem  compute capability, so what matters is the generation, not the exact model.
rem  Unknown names get rank 0, and :tier_eligible deliberately still tries them:
rem  refusing on an un-recognised name is exactly how the RTX 4060 + driver
rem  560.94 case got locked out. Let the self-test decide instead.
rem ---------------------------------------------------------------------------
:arch_rank
set "ARCH_RANK=0"
echo %~1> "%GPUN_FILE%"
findstr /i /c:"RTX 50" /c:"RTX 51" /c:"RTX 52" /c:"RTX 53" /c:"RTX 54" /c:"RTX 55" /c:"RTX 56" /c:"RTX 57" /c:"RTX 58" /c:"RTX 59" "%GPUN_FILE%" >nul && set "ARCH_RANK=90"
if "!ARCH_RANK!"=="0" findstr /i /c:"RTX 40" /c:"RTX 41" /c:"RTX 42" /c:"RTX 43" /c:"RTX 44" /c:"RTX 45" /c:"RTX 46" /c:"RTX 47" /c:"RTX 48" /c:"RTX 49" "%GPUN_FILE%" >nul && set "ARCH_RANK=80"
if "!ARCH_RANK!"=="0" findstr /i /c:"RTX 30" /c:"RTX 31" /c:"RTX 32" /c:"RTX 33" /c:"RTX 34" /c:"RTX 35" /c:"RTX 36" /c:"RTX 37" /c:"RTX 38" /c:"RTX 39" "%GPUN_FILE%" >nul && set "ARCH_RANK=70"
if "!ARCH_RANK!"=="0" findstr /i /c:"RTX 20" /c:"GTX 16" /c:"TITAN RTX" /c:"Quadro RTX" /c:"Quadro T" /c:"Tesla T" "%GPUN_FILE%" >nul && set "ARCH_RANK=60"
if "!ARCH_RANK!"=="0" findstr /i /c:"GTX 10" /c:"GT 10" /c:"GTX 9" /c:"GT 9" /c:"GTX 7" /c:"GT 7" /c:"Quadro P" /c:"Quadro M" /c:"Quadro K" /c:"Tesla P" /c:"Tesla K" /c:"Tesla M" /c:"MX" "%GPUN_FILE%" >nul && set "ARCH_RANK=50"
exit /b 0

rem ---------------------------------------------------------------------------
rem  :tier_line <code>   -> sets TIER_LINE to the whole matching row
rem  The table lives in a temp FILE because cmd cannot iterate a multi-line
rem  variable with `for /f` -- reading a file is the form that works.
rem ---------------------------------------------------------------------------
:tier_line
set "TIER_LINE="
for /f "usebackq delims=" %%L in ("%TIER_FILE%") do (
  if not defined TIER_LINE (
    for /f "tokens=1 delims=|" %%k in ("%%L") do (
      if /i "%%k"=="%~1" set "TIER_LINE=%%L"
    )
  )
)
if not defined TIER_LINE exit /b 1
exit /b 0

rem ---------------------------------------------------------------------------
rem  :tier_field <code> <1-6>   -> sets TIER_FIELD
rem ---------------------------------------------------------------------------
:tier_field
call :tier_line "%~1"
if errorlevel 1 exit /b 1
for /f "tokens=%~2 delims=|" %%v in ("!TIER_LINE!") do set "TIER_FIELD=%%v"
exit /b 0

rem ---------------------------------------------------------------------------
rem  :tier_eligible <code>
rem  0 = worth trying, 1 = generation unsupported, 2 = driver too old.
rem  Driver floors are deliberatively permissive: an over-eager "no" is the bug
rem  this version fixes, while an over-eager "yes" only costs a download that the
rem  self-test then rejects.
rem ---------------------------------------------------------------------------
:tier_eligible
call :tier_field "%~1" 3
set /a "T_MINDRV=TIER_FIELD" 2>nul
call :tier_field "%~1" 4
set "T_ARCHNEED=!TIER_FIELD!"
call :tier_field "%~1" 2
set "T_LABEL=!TIER_FIELD!"
call :arch_rank "!GPUNAME!"
set "T_NEED=60"
if /i "!T_ARCHNEED!"=="maxwell" set "T_NEED=50"
if "!ARCH_RANK!"=="0" (
  rem  Unknown model: try it. The self-test is the judge, not a name table.
  echo [i] !T_LABEL!: GPU "!GPUNAME!" is not in the known-generation table; trying anyway.
) else (
  if !ARCH_RANK! LSS !T_NEED! (
    echo [i] !T_LABEL!: GPU generation too old for this tier; skipping.
    exit /b 1
  )
)
if !DRVNUM! LSS !T_MINDRV! exit /b 2
exit /b 0

rem ---------------------------------------------------------------------------
rem  :show_tier <code>   print what this tier will install
rem ---------------------------------------------------------------------------
:show_tier
call :tier_field "%~1" 2
set "T_LABEL=!TIER_FIELD!"
call :tier_field "%~1" 5
set "T_ORT=!TIER_FIELD!"
call :tier_field "%~1" 6
set "T_DEPS=!TIER_FIELD!"
echo       tier      : !T_LABEL!  ^(%~1^)
echo       packages  : !T_ORT!  +  !T_DEPS!
exit /b 0

rem ---------------------------------------------------------------------------
rem  :purge_gpu_tiers
rem  Remove every GPU runtime before switching tiers. `nvidia-*` is passed to pip
rem  as a glob ON PURPOSE (unquoted) so pip expands it; these names match no real
rem  files in a normal directory, so the shell passes them through literally.
rem  Both onnxruntime distributions are removed because they share one directory.
rem ---------------------------------------------------------------------------
rem ---------------------------------------------------------------------------
rem  :purge_gpu_tiers   remove EVERY nvidia package plus both onnxruntime names
rem
rem  *** MEASURED BUG, fixed in v1.35 ***
rem  The previous version ran:  pip uninstall -y onnxruntime onnxruntime-gpu nvidia-*
rem  cmd does NOT expand wildcards for external programs, so pip received the
rem  LITERAL string `nvidia-*` and answered "no such package" -- meaning NOT ONE
rem  nvidia package was ever removed. On a machine that had tried cu12 first, the
rem  cu12 libraries stayed while cu13 was installed beside them, and onnxruntime
rem  then loaded the wrong generation. That is the same silent-CPU failure this
rem  whole script exists to prevent, and it was reproduced on a real machine
rem  (RTX 4060 Laptop): `pip list` showed BOTH generations, 14 packages, at once.
rem
rem  Correct approach: ASK pip what is installed, then uninstall each name.
rem  The findstr pattern also keeps the two onnxruntime names on the list, which
rem  is intended -- they share one directory and must be removed together.
rem ---------------------------------------------------------------------------
:purge_gpu_tiers
echo       removing any previous CUDA generation ^(avoid mixing DLLs^)...
set "PURGE_LIST=%TEMP%\qa-gpu-purge.txt"
%PY% -m pip list --format=freeze 2>nul | findstr /i "nvidia onnxruntime" > "!PURGE_LIST!"
if exist "!PURGE_LIST!" (
  for /f "tokens=1 delims=<>=" %%n in (!PURGE_LIST!) do (
    %PY% -m pip uninstall -y -q %%n >nul 2>nul
  )
)
del "!PURGE_LIST!" >nul 2>nul
exit /b 0

rem ---------------------------------------------------------------------------
rem  :run_probe   run the self-test file and set PROBE_RC
rem
rem  Nothing may sit between the run and the capture: `set` and `del` overwrite
rem  errorlevel, and a mis-captured exit code once made a broken check report
rem  success in this project. Hence this deliberately tiny subroutine.
rem ---------------------------------------------------------------------------
rem ---------------------------------------------------------------------------
rem  :probe_candidate <tier>   isolated install + REAL forward-pass verification
rem
rem  Sets PROBE_RC (0 = usable), PROBE_JSON (raw verdict), PROBE_REASON, PROBE_EVIDENCE.
rem
rem  Why a temp venv instead of installing straight into the target interpreter:
rem  the old flow could leave a broken CUDA stack in the user environment when a
rem  tier turned out to be unusable. With isolation, a failing tier costs only
rem  download time -- nothing is installed for real until verification passes.
rem
rem  The package list is joined with `;` and passed as ONE argument: items carry
rem  version constraints like `nvidia-cudnn-cu13~=9.0`, whose characters cmd would
rem  mangle if the list were split on spaces.
rem ---------------------------------------------------------------------------
:probe_candidate
set "PROBE_RC=1"
set "PROBE_JSON="
set "PROBE_REASON="
set "PROBE_EVIDENCE="
set "TRY_SPEC="
set "TRY_DEPS="
call :tier_field "%~1" 5 TRY_SPEC
call :tier_field "%~1" 6 TRY_DEPS
set "TRY_PKGS=!TRY_SPEC!;!TRY_DEPS: =;!"
set "TRY_ENV=%TEMP%\qa-gpu-try-%RANDOM%%RANDOM%"

set "GP_PROBE=%~dp0deploy\ocr\qa_gpu_probe.py"
if not exist "!GP_PROBE!" set "GP_PROBE=%~dp0qa_gpu_probe.py"
if not exist "!GP_PROBE!" (
  echo       qa_gpu_probe.py not found -- falling back to the shallow self-test
  echo       ^(it cannot do a real forward pass, so it can report a false pass^).
  call :install_tier_with_fallback "%~1"
  if errorlevel 1 exit /b 1
  call :run_probe
  set "PROBE_REASON=shallow self-test ^(no qa_gpu_probe.py^)"
  exit /b !PROBE_RC!
)

echo       verifying in an isolated temp environment with a real forward pass...
echo       ^(this downloads the tier once; ~1-2 minutes^)
set "TRY_OUT=%TEMP%\qa-gpu-try-out-%RANDOM%.txt"
"%PY%" "!GP_PROBE!" --json-only --pkgs "!TRY_PKGS!" --require numpy onnxruntime rapidocr --target-env "!TRY_ENV!" --install-first --force-reinstall --python "%PY%" --retries !STEP_RETRIES! --timeout 60 !PIP_SRC_ARGS! > "!TRY_OUT!" 2>&1
set "PROBE_RC=!errorlevel!"
if not "!PROBE_RC!"=="0" (
  rem  Keep only the tail: pip output can be long and the verdict is the point.
  for /f "usebackq delims=" %%l in (`powershell -NoProfile -Command "Get-Content -LiteralPath '!TRY_OUT!' -Tail 6"`) do echo       %%l
)
set "PROBE_JSON="
for /f "tokens=2 delims=#" %%j in ('findstr /c:"###QAGPUPROBE###" "!TRY_OUT!"') do set "PROBE_JSON=%%j"
del "!TRY_OUT!" >nul 2>nul
if exist "!TRY_ENV!" rd /s /q "!TRY_ENV!" >nul 2>nul

if defined PROBE_JSON call :probe_reason "!PROBE_JSON!"
exit /b !PROBE_RC!

rem ---------------------------------------------------------------------------
rem  :probe_reason <json>   turn the verdict into PROBE_REASON / PROBE_EVIDENCE
rem  No jq: a target machine is not guaranteed to have it.
rem ---------------------------------------------------------------------------
:probe_reason
set "PROBE_REASON="
set "PROBE_EVIDENCE="
set "TRY_STAGE="
set "TRY_WHY="
for /f "usebackq tokens=1,* delims=|" %%a in (`"%PY%" -c "import json,sys;d=json.loads(sys.argv[1]);print(d.get('stage','?')+'|'+d.get('reason','?'))" "%~1" 2^>nul`) do (
  set "TRY_STAGE=%%a"
  set "TRY_WHY=%%b"
)
if defined TRY_STAGE set "PROBE_REASON=!TRY_STAGE!: !TRY_WHY!"
for /f "usebackq delims=" %%e in (`"%PY%" -c "import json,sys;d=json.loads(sys.argv[1]);[print('      '+x) for x in (d.get('cuda_errors') or [])[:3]]" "%~1" 2^>nul`) do (
  set "PROBE_EVIDENCE=!PROBE_EVIDENCE!%%e"
)
exit /b 0

:run_probe
%PY% "%PROBE%"
set "PROBE_RC=!errorlevel!"
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
rem  :auto_pick_source
rem  Measure the candidate mirrors and set SRC_INDEX to the fastest one.
rem
rem  Why this exists: the GPU runtime is large (measured: cu13 ~1.05 GB,
rem  cu12 ~1.49 GB) and throughput varies enormously between sources. Measured on
rem  one machine on 2026-10-13, same 527 MB wheel, first 3 MB each:
rem      USTC 4.31 | Tencent 3.63 | Tsinghua 0.81 | PyPI official 0.24  (MB/s)
rem  At 1.49 GB that is about 6 minutes versus about 1.8 hours; on some networks
rem  the official source runs at 0.02 MB/s, which is over 20 hours -- the install
rem  simply never finishes. So picking the source is not an optimisation, it is
rem  the difference between working and not.
rem
rem  The probing logic lives in deploy\ocr\qa_speedtest.py so that .sh and .cmd
rem  share one implementation instead of drifting apart.
rem ---------------------------------------------------------------------------
:auto_pick_source
set "PICKER=%~dp0deploy\ocr\qa_speedtest.py"
if not exist "%PICKER%" set "PICKER=%~dp0qa_speedtest.py"
if not exist "%PICKER%" (
  echo [i] qa_speedtest.py not found; skipping auto source selection.
  exit /b 0
)
del "%SRC_FILE%" >nul 2>nul
echo [i] Measuring mirror speed ^(up to %SPEEDTEST_MB% MB each, ~5-10 s^)...
set "PYTHONIOENCODING=utf-8"
%PY% "%PICKER%" --limit-mb %SPEEDTEST_MB% --out "%SRC_FILE%" >nul 2>nul
set "PYTHONIOENCODING="
if not exist "%SRC_FILE%" (
  echo [i] Speed test produced no result; falling back to the official source.
  exit /b 0
)
set "PICKED="
set /p "PICKED="<"%SRC_FILE%"
if not defined PICKED (
  echo [i] No usable mirror found; falling back to the official source.
  exit /b 0
)
set "SRC_INDEX=!PICKED!"
echo [OK] Selected source: !SRC_INDEX!
exit /b 0

rem ---------------------------------------------------------------------------
rem  :install_tier_with_fallback <code>
rem  Install one tier; if that fails AND a mirror was in use, retry once against
rem  the official sources (PyPI + NVIDIA).
rem
rem  Why the extra layer: mirrors occasionally lag behind or serve a broken
rem  wheel. Without this the user just sees "install failed" and has to work out
rem  that switching source is the fix. pip's cache is purged before the retry so
rem  a half-downloaded file from the mirror is not reused.
rem ---------------------------------------------------------------------------
:install_tier_with_fallback
call :install_tier "%~1"
if not errorlevel 1 exit /b 0
if not defined SRC_INDEX exit /b 1
if /i "!SRC_INDEX!"=="%PYPI_INDEX%" exit /b 1
echo.
echo [!] Install failed with !SRC_INDEX!
echo     Retrying once against the official sources ^(PyPI + NVIDIA^)...
%PY% -m pip cache purge >nul 2>nul
set "SRC_INDEX="
call :install_tier "%~1"
exit /b %errorlevel%

rem ---------------------------------------------------------------------------
rem  :install_tier <code>
rem  One pip install for one tier, via the retry wrapper.
rem ---------------------------------------------------------------------------
:install_tier
call :tier_field "%~1" 5
set "T_ORT=!TIER_FIELD!"
call :tier_field "%~1" 6
set "T_DEPS=!TIER_FIELD!"
call :pip_retry !T_ORT! !T_DEPS!
exit /b %errorlevel%

rem ---------------------------------------------------------------------------
rem  :pip_retry <packages>
rem  Run one pip install, retrying on failure with 5 / 10 / 20 second waits.
rem
rem  Why an outer loop when pip already has --retries: pip's --retries governs
rem  a SINGLE request, while a 1 GB install makes many. When the whole run fails
rem  part-way pip just exits and the user has to start over by hand. Wheels
rem  already downloaded stay in pip's cache, so a retry only costs the part that
rem  failed. Returns errorlevel 1 after the last attempt fails.
rem
rem  Takes the package list as %* because each tier installs a different set.
rem ---------------------------------------------------------------------------
:pip_retry
set "INSTALL_PKGS=%*"
set /a "TRY_N=1"
set "WAIT_S=5"
set "MAX_TRY=%STEP_RETRIES%"
echo %MAX_TRY%| findstr /r "^[0-9][0-9]*$" >nul || set "MAX_TRY=3"
:pip_retry_loop
echo       Command: %PY% -m pip install %PIP_ARGS% %INSTALL_PKGS%
%PY% -m pip install --upgrade --disable-pip-version-check %PIP_ARGS% %INSTALL_PKGS%
if not errorlevel 1 exit /b 0
if !TRY_N! GEQ !MAX_TRY! (
  echo.
  echo [X] Install failed after !MAX_TRY! attempt^(s^).
  exit /b 1
)
echo.
rem  *** MEASURED TRAP *** the pipe form below is deliberate. Writing
rem  `echo [!TRY_N!] failed ...` DOES NOT expand the variables: the `|` right
rem  after `echo` makes cmd treat the `!` as a command separator, and the line
rem  came out as the literal garbage `[TRY_NWAIT_S seconds (already-downloaded`.
rem  Setting plain variables first and echoing those is the form that works.
set "MSG_TRY=!TRY_N!"
set "MSG_WAIT=!WAIT_S!"
echo [!MSG_TRY!] Install attempt failed. Retrying in !MSG_WAIT! seconds.
echo     Already-downloaded packages are reused, not fetched again.
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
