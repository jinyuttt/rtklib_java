param(
    [string]$RepoUrl = "https://github.com/jinyurrr/rtklib-java-data.git",
    [string]$TargetDir = $PSScriptRoot
)

$ErrorActionPreference = "Stop"
Write-Host "=== rtklib-java 测试数据下载工具 ===" -ForegroundColor Cyan
Write-Host ""

$gitCmd = Get-Command git -ErrorAction SilentlyContinue
if (-not $gitCmd) {
    Write-Host "[ERROR] git not found. Please install Git first." -ForegroundColor Red
    exit 1
}

$tempDir = Join-Path $TargetDir "_data_download"

if (Test-Path $tempDir) {
    Remove-Item $tempDir -Recurse -Force
}

Write-Host "[1/3] Cloning data repository..." -ForegroundColor Yellow
git clone --depth 1 $RepoUrl $tempDir
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Failed to clone data repository." -ForegroundColor Red
    exit 1
}

Write-Host "[2/3] Copying data files..." -ForegroundColor Yellow
$subdirs = @("rinex", "nav", "rtcm", "product", "reference", "nmea", "config", "tle")
foreach ($subdir in $subdirs) {
    $src = Join-Path $tempDir $subdir
    $dst = Join-Path $TargetDir $subdir
    if (Test-Path $src) {
        Get-ChildItem $src -File -Recurse | ForEach-Object {
            $relPath = $_.FullName.Substring($src.Length + 1)
            $dstFile = Join-Path $dst $relPath
            $dstDir = Split-Path $dstFile -Parent
            if (-not (Test-Path $dstDir)) { New-Item -ItemType Directory -Path $dstDir -Force | Out-Null }
            if (-not (Test-Path $dstFile)) {
                Copy-Item $_.FullName $dstFile -Force
                Write-Host "  + $subdir/$relPath" -ForegroundColor Green
            } else {
                Write-Host "  = $subdir/$relPath (exists, skipped)" -ForegroundColor Gray
            }
        }
    }
}

Write-Host "[3/3] Cleanup..." -ForegroundColor Yellow
Remove-Item $tempDir -Recurse -Force

Write-Host ""
Write-Host "=== Download complete! ===" -ForegroundColor Cyan
Write-Host "Data files are in: $TargetDir" -ForegroundColor White