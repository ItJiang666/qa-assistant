@echo off
setlocal EnableDelayedExpansion
rem ===========================================================================
rem  enable-ocr.cmd  --  one-click setup for scanned-PDF text recognition (CPU)
rem ===========================================================================
rem  WHEN TO USE THIS
rem    * You have no NVIDIA GPU, or you do not want the ~1 GB CUDA runtime;
rem    * You only want the "read text from scanned PDFs / images" feature.
rem
rem  It installs exactly three packages (~50 MB; models ship inside the wheel,
rem  so OCR works fully offline afterwards):
rem      rapidocr      the actual recognizer (PP-OCR family)
rem      onnxruntime   its inference backend (CPU build)
rem      pymupdf       rasterizes PDF pages to bitmaps
rem
rem  NOTE: difference from `pip install -r deploy\ocr\requirements.txt`:
rem     This installs the SAME three packages. The script just adds environment
rem     checks -- it skips unusable Python interpreters, prints the absolute path
rem     of the environment it is about to modify, and verifies afterwards that all
rem     three packages import (the same criterion the application itself uses).
rem     The requirements.txt route still works fine.
rem
rem  Want GPU acceleration instead? Use enable-gpu.cmd -- it installs these three
rem  first and then adds the GPU runtime.
rem
rem  NOTE: this file must stay pure ASCII + CRLF. cmd.exe reads .cmd files using
rem  the system code page (GBK on Chinese Windows), so UTF-8 Chinese text inside
rem  would be decoded as garbage and can break parsing.
rem ===========================================================================

set "PY="
set "PYVER="
set "PYM="
set "PYN="

echo ============================================================
echo   qa-assistant  -  enable scanned-document OCR (CPU mode)
echo ============================================================
echo.

rem ---- 1. find a WORKING python ---------------------------------------------
rem  Verified by actually running it: on Windows with the Store build of Python
rem  installed, PATH contains a WindowsApps\python3.exe redirect stub that
rem  `where` finds but cannot execute. Picking it blind would make this script
rem  claim "no Python found" on a machine that clearly has one.
set "PY="
call :try_python "py" "-3"
if not defined PY call :try_python "python" ""
if not defined PY call :try_python "python3" ""
if not defined PY (
  echo [X] No working Python found. This feature needs Python 3.10+ installed.
  echo     It is optional: Q^&A, upload and search all work without it.
  goto :fail
)
for /f "delims=" %%v in ('!PY! -c "import sys;print(sys.executable)" 2^>nul') do set "PYPATH=%%v"
echo [1/3] Python : !PYVER!
echo       target : !PYPATH!

rem  Python 3.10+ is what the OCR dependencies require.
if !PYM! LSS 3 goto :old_python
if !PYM! EQU 3 if !PYN! LSS 10 goto :old_python

rem  Show WHICH environment is about to change and let the user back out.
set /p "ANS=      Install into THIS environment? [Y/n] "
if /i "!ANS!"=="n" (
  echo [i] Cancelled. Nothing was changed.
  goto :end
)

rem ---- 2. GUARD: never downgrade an existing GPU install ---------------------
rem  MEASURED PROBLEM (this actually broke a machine while testing):
rem  `pip install onnxruntime` installs the CPU distribution, whose files land in
rem  the SAME directory as onnxruntime-gpu (both import as `onnxruntime`). pip
rem  records them as two separate distributions, so it cheerfully installs the
rem  CPU wheel on top -- and CUDAExecutionProvider disappears. GPU acceleration
rem  is silently gone, with no error anywhere.
rem
rem  So: if onnxruntime-gpu is present, do NOT name onnxruntime at all.
rem  rapidocr / pymupdf get installed separately, which needs no onnxruntime
rem  argument (the existing GPU build already satisfies that dependency).
set "HASGPU="
set "SKIP_ORT="
for /f "delims=" %%v in ('%PY% -m pip list 2^>nul ^| findstr /b /c:"onnxruntime-gpu"') do set "HASGPU=1"
if defined HASGPU (
  set "SKIP_ORT=1"
  echo.
  echo [i] onnxruntime-gpu is already installed in this environment.
  echo     This script will NOT install the CPU build: it would overwrite the
  echo     GPU one ^(same files, two separate pip records^) and silently
  echo     disable GPU acceleration.
  echo     Installing the remaining OCR baseline ^(rapidocr, pymupdf^) only.
)

rem ---- 3. install the packages ----------------------------------------------
echo.
if defined SKIP_ORT (
  echo [2/3] Installing rapidocr, pymupdf...
  echo       About 50 MB; models ship inside the wheel, OCR works offline after.
  echo.
  %PY% -m pip install --upgrade --disable-pip-version-check rapidocr pymupdf
) else (
  echo [2/3] Installing rapidocr, onnxruntime, pymupdf...
  echo       About 50 MB; models ship inside the wheel, OCR works offline after.
  echo.
  %PY% -m pip install --upgrade --disable-pip-version-check rapidocr onnxruntime pymupdf
)
if errorlevel 1 (
  echo.
  echo [X] Install failed. Check the network, then run this script again.
  echo     Nothing was broken -- if a package was already there, it still is.
  goto :fail
)

rem ---- 3. verify (same criterion the application uses) ----------------------
echo.
echo [3/3] Verifying...
%PY% -c "import importlib.util as u;print('OK' if all(u.find_spec(n) for n in ('rapidocr','onnxruntime','pymupdf')) else 'NO')"
if errorlevel 1 (
  echo [X] Verification could not run. Re-run this script.
  goto :fail
)
echo.
echo ============================================================
for /f "delims=" %%v in ('%PY% -c "import importlib.util as u;print('OK' if all(u.find_spec(n) for n in ('rapidocr','onnxruntime','pymupdf')) else 'NO')" 2^>nul') do set "ALLPKG=%%v"
if /i "!ALLPKG!"=="OK" (
  echo   [OK] Scanned-document OCR is ready ^(CPU mode^).
  echo.
  echo   Restart the application, then upload a scanned PDF. Its status turns
  echo   into "pending confirmation"; open the result, check it, and confirm.
  echo   Measured speed on a 38-page scan: about 47 seconds.
) else (
  echo   [X] Self-check failed: some package still cannot be imported.
  echo       Run this script again, or install manually with:
  echo         %PY% -m pip install rapidocr onnxruntime pymupdf
)
echo ============================================================
goto :end

:old_python
echo [X] Python !PYVER! is too old. The OCR stack needs Python 3.10 or newer.
goto :fail

rem ---------------------------------------------------------------------------
rem  :try_python <exe> <extra-args>
rem  Probe one candidate. Sets PY / PYVER / PYM / PYN only when it actually
rem  executes and reports something that looks like a version.
rem
rem  NOTE: do NOT put a percent-format such as "%d.%d" into the -c string --
rem  cmd treats a bare % specially and eats it (measured: every candidate then
rem  looked broken). Take the plain version string and cut the fields in batch.
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
for /f "tokens=1,2 delims=." %%a in ("!PROBE!") do set /a "M=%%a" 2>nul & set /a "N=%%b" 2>nul
if !M! LSS 3 (
  echo [i] skipping unusable candidate: !CAND!  ^(unexpected version "!PROBE!"^)
  exit /b 0
)
set "PY=!CAND!"
set "PYVER=!PROBE!"
set "PYM=!M!"
set "PYN=!N!"
exit /b 0

:fail
echo.
exit /b 1

:end
echo.
echo Press any key to close this window...
pause >nul
exit /b 0
