<#
  make-release-zip.ps1 -- build the release ZIP (called by package-release.cmd)

  WHY NOT Compress-Archive (measured pitfall -- do not revert to it)
  -----------------------------------------------------------------
  Measured 2026-10-14: PowerShell 5.1's Compress-Archive writes entry names
  with BACKSLASHES as the directory separator ("deploy\ocr\qa_ocr.py").
  The ZIP spec (APPNOTE 4.4.17.1) requires FORWARD slashes, and it also
  stamps create_system = 0 (MS-DOS).

  The consequence is platform dependent:
    * On Windows everything looks fine -- which is exactly why this bug is
      easy to miss when testing locally.
    * On Linux / macOS a backslash is a LEGAL FILENAME CHARACTER, not a
      separator. unzip / Python then extract a FLAT file literally named
      "deploy\ocr\qa_ocr.py": the directory tree disappears and
      "bash ./enable-gpu.sh" cannot find anything.

  Evidence: extracting the old package inside WSL produced
    'deploy\\ocr\\qa_ocr.py', 'docs\\ACCEPTANCE.md', ... all flat.
  The same package extracted on Windows was perfectly fine.

  THE CORRECT WAY
  ---------------
  Build the entries ourselves with System.IO.Compression.ZipArchive and
  replace '\' with '/' in the relative path. It must be the static
  ZipFileExtensions::CreateEntryFromFile with an explicit entry name:
  ZipArchive.CreateEntry() derives the path itself and can still emit
  backslashes on Windows. After this change Python/unzip see
  "deploy/ocr/qa_ocr.py".

  NOTE: keep this file PURE ASCII. Windows PowerShell 5.1 reads BOM-less
  files using the ANSI code page, so UTF-8 Chinese comments would be
  mis-decoded and can break script parsing.
#>
param(
  [Parameter(Mandatory = $true)][string]$StageDir,
  [Parameter(Mandatory = $true)][string]$ZipPath
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

if (-not (Test-Path -LiteralPath $StageDir)) {
  Write-Host "[X] stage dir not found: $StageDir"
  exit 1
}

$parent = Split-Path -Parent $ZipPath
if ($parent -and -not (Test-Path -LiteralPath $parent)) {
  New-Item -ItemType Directory -Path $parent -Force | Out-Null
}
if (Test-Path -LiteralPath $ZipPath) {
  Remove-Item -LiteralPath $ZipPath -Force
}

$base = (Resolve-Path -LiteralPath $StageDir).Path.TrimEnd('\')
$files = @(Get-ChildItem -LiteralPath $StageDir -Recurse -File)
Write-Host ("  zip entries: {0} file(s)" -f $files.Count)

$stream = [System.IO.File]::Open($ZipPath, [System.IO.FileMode]::Create)
try {
  $archive = New-Object System.IO.Compression.ZipArchive($stream, [System.IO.Compression.ZipArchiveMode]::Create)
  try {
    foreach ($f in $files) {
      # forward slashes only -- this is the whole reason this script exists
      $entry = $f.FullName.Substring($base.Length).TrimStart('\').Replace('\', '/')
      [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $f.FullName, $entry, [System.IO.Compression.CompressionLevel]::Optimal)
      Write-Host ("    + " + $entry)
    }
  } finally {
    $archive.Dispose()
  }
} finally {
  $stream.Dispose()
}

# self check: never ship a package that cannot be unpacked off Windows
$verify = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
try {
  $bad = @($verify.Entries | Where-Object { $_.FullName.Contains('\') })
  if ($bad.Count -gt 0) {
    Write-Host ("[X] {0} entr(ies) still use backslashes" -f $bad.Count)
    exit 1
  }
  Write-Host ("  verified: {0} entries, all forward slashes" -f $verify.Entries.Count)
} finally {
  $verify.Dispose()
}
