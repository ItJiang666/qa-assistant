# check-ui-assets.ps1 — verify that the front-end assets inside the packaged jar are
# exactly the ones index.html references: none missing, none orphaned.
#
#   usage: powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-ui-assets.ps1 <jar>
#   exit : 0 = clean, 1 = problem, 2 = usage / jar unreadable
#
# Why this exists (measured, not hypothetical): vite names bundles with a content
# hash, so every build produces a new file name. Maven's incremental build does NOT
# clean stale files out of target\classes, so the previous index-xxx.js gets copied
# into the jar alongside the new one. That is not merely dead weight -- the stale
# bundle stays reachable by URL, so the shipped package would contain two versions
# of the front-end. The check must compare the two SETS; only verifying that what
# index.html references exists would silently pass an orphan.
#
# NOTE: this file is deliberately ASCII-only. PowerShell 5.1 reads a BOM-less .ps1
# as ANSI, so CJK literals here would be decoded into mojibake (and have broken
# parsing before). English messages are fine for a build-time tool.

param([Parameter(Mandatory = $true)][string]$Jar)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

if (-not (Test-Path -LiteralPath $Jar)) {
    Write-Host "[X] jar not found: $Jar"
    exit 2
}

$prefix = 'BOOT-INF/classes/static/'
$zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Jar).Path)
try {
    $indexEntry = $zip.Entries | Where-Object { $_.FullName -eq ($prefix + 'index.html') }
    if (-not $indexEntry) {
        Write-Host "[X] no $($prefix)index.html in the jar -- did you build the front-end?"
        exit 2
    }
    $reader = New-Object System.IO.StreamReader($indexEntry.Open())
    $html = $reader.ReadToEnd()
    $reader.Close()

    $referenced = @()
    foreach ($m in [regex]::Matches($html, '/assets/([^"''>\s]+)')) {
        $referenced += $m.Groups[1].Value
    }

    $present = @()
    foreach ($e in $zip.Entries) {
        if ($e.FullName.StartsWith($prefix + 'assets/') -and $e.Length -gt 0) {
            $present += $e.FullName.Substring(($prefix + 'assets/').Length)
        }
    }
} finally {
    $zip.Dispose()
}

$referenced = @($referenced | Sort-Object -Unique)
$present = @($present | Sort-Object -Unique)

$missing = @($referenced | Where-Object { $present -notcontains $_ })
$orphan = @($present | Where-Object { $referenced -notcontains $_ })

Write-Host ("    referenced by index.html : " + ($referenced -join ', '))
Write-Host ("    present in jar/assets    : " + ($present -join ', '))

if ($missing.Count -gt 0) {
    Write-Host ("[X] index.html references assets that are MISSING from the jar: " + ($missing -join ', '))
    Write-Host "    The app would load a blank page. Run: cd web && pnpm build && mvn clean package"
    exit 1
}
if ($orphan.Count -gt 0) {
    Write-Host ("[X] unreferenced front-end assets inside the jar (stale build residue): " + ($orphan -join ', '))
    Write-Host "    These are leftovers in target\classes from an earlier build."
    Write-Host "    Run: mvn clean package   (clean is what removes them), then re-run this script."
    exit 1
}

Write-Host ("[OK] front-end assets clean: " + $referenced.Count + " referenced, " + $present.Count + " present, no orphans")
exit 0
