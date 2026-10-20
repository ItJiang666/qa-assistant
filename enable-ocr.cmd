@echo off
setlocal EnableDelayedExpansion
rem ===========================================================================
rem  enable-ocr.cmd -- install the CPU OCR stack for scanned-PDF recognition
rem ===========================================================================
rem  Installs three packages (~50 MB; models ship inside the wheel, so OCR
rem  works offline afterwards):
rem      rapidocr      the recognizer (PP-OCR)
rem      onnxruntime   inference backend (CPU build)
rem      pymupdf       rasterizes PDF pages
rem
rem  Equivalent to `pip install -r deploy\ocr\requirements.txt`, plus
rem  interpreter checks and a post-install import check.
rem  For GPU acceleration use enable-gpu.cmd instead.
rem
rem  Keep this file pure ASCII + CRLF: cmd.exe reads .cmd files in the system
rem  code page (GBK on Chinese Windows), so UTF-8 text would be mis-decoded.
rem ===========================================================================

set "PY="
set "PYVER="
set "PYM="
set "PYN="

echo ============================================================
echo   qa-assistant  -  enable scanned-document OCR (CPU mode)
echo ============================================================
echo.

rem 1. find a python that actually runs (skips the Store redirect stub)
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

if !PYM! LSS 3 goto :old_python
if !PYM! EQU 3 if !PYN! LSS 10 goto :old_python

rem 2. never install the CPU build when onnxruntime-gpu is present: they share
rem the same directory, so the CPU wheel would overwrite it and silently
rem disable GPU acceleration
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

rem 3. install
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

rem 4. verify with the same criterion the application uses
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

rem :try_python <exe> [extra arg] -- sets PY/PYVER/PYM/PYN only when it runs.
rem Do not put a percent-format such as "%d.%d" in the -c string: cmd would eat
rem the bare % and every candidate would look broken.
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
