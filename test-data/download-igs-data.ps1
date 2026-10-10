param(
    [string]$TargetDir = $PSScriptRoot,
    [string]$Year = "2024",
    [string]$Doy = "001",
    [switch]$SkipRinex,
    [switch]$SkipProduct,
    [switch]$SkipRtklibSample
)

$ErrorActionPreference = "Continue"
Write-Host ""
Write-Host "=== IGS/MGEX 公开数据下载工具 ===" -ForegroundColor Cyan
Write-Host "用途: 下载 PPP-AR 24h 静态验证 + 长基线 RTK 验证所需的公开数据" -ForegroundColor White
Write-Host "日期: $Year DOY $Doy" -ForegroundColor White
Write-Host ""

function Download-File {
    param([string]$Url, [string]$OutFile, [string]$Label)
    if (Test-Path $OutFile) {
        $size = (Get-Item $OutFile).Length
        if ($size -gt 1000) {
            Write-Host "  = $Label (exists, $size bytes)" -ForegroundColor Gray
            return $true
        }
    }
    Write-Host "  ↓ $Label" -ForegroundColor Yellow -NoNewline
    try {
        $netrcFile = Join-Path $env:USERPROFILE ".netrc"
        $netrcArgs = if (Test-Path $netrcFile) { "--netrc-file", $netrcFile } else { @() }
        $allArgs = @("-s", "-L", "-o", $OutFile, $Url, "--connect-timeout", "30", "--max-time", "300") + $netrcArgs
        & curl.exe @allArgs 2>$null
        if (Test-Path $OutFile) {
            $size = (Get-Item $OutFile).Length
            if ($size -gt 1000) {
                $isHtml = $false
                try {
                    $bytes = [System.IO.File]::ReadAllBytes($OutFile)
                    if ($bytes.Length -ge 5) {
                        $header = [System.Text.Encoding]::ASCII.GetString($bytes[0..4])
                        if ($header -match "<!DOC" -or $header -match "<html") { $isHtml = $true }
                    }
                } catch {}
                if ($isHtml) {
                    Write-Host " AUTH_REQUIRED" -ForegroundColor Red
                    Remove-Item $OutFile -Force -ErrorAction SilentlyContinue
                    return $false
                }
                Write-Host " OK ($size bytes)" -ForegroundColor Green
                return $true
            }
        }
        Write-Host " FAILED" -ForegroundColor Red
        if (Test-Path $OutFile) { Remove-Item $OutFile -Force -ErrorAction SilentlyContinue }
        return $false
    } catch {
        Write-Host " ERROR" -ForegroundColor Red
        return $false
    }
}

function Decompress-Gz {
    param([string]$GzFile, [string]$OutFile)
    if (Test-Path $OutFile) {
        Write-Host "  = $(Split-Path $OutFile -Leaf) (exists)" -ForegroundColor Gray
        return
    }
    Write-Host "  inflate $(Split-Path $GzFile -Leaf)" -ForegroundColor Yellow -NoNewline
    try {
        $inputStream = [System.IO.File]::OpenRead($GzFile)
        $gzipStream = New-Object System.IO.Compression.GZipStream($inputStream, [System.IO.Compression.CompressionMode]::Decompress)
        $outputStream = [System.IO.File]::Create($OutFile)
        $gzipStream.CopyTo($outputStream)
        $outputStream.Close()
        $gzipStream.Close()
        $inputStream.Close()
        Write-Host " OK" -ForegroundColor Green
    } catch {
        Write-Host " FAILED" -ForegroundColor Red
    }
}

$yy = $Year.Substring(2, 2)
$epoch = (Get-Date -Year ([int]$Year) -Month 1 -Day 1).AddDays([int]$Doy - 1)
$gpsEpoch = (Get-Date -Year 1980 -Month 1 -Day 6)
$gpsWeek = [int][math]::Floor(($epoch - $gpsEpoch).TotalDays / 7)
Write-Host "GPS Week: $gpsWeek" -ForegroundColor White

$netrcFile = Join-Path $env:USERPROFILE ".netrc"
$hasNetrc = Test-Path $netrcFile
if ($hasNetrc) {
    Write-Host "Auth: .netrc found at $netrcFile" -ForegroundColor Green
} else {
    Write-Host "Auth: No .netrc found - CDDIS downloads will fail" -ForegroundColor DarkYellow
    Write-Host "      Register at https://urs.earthdata.nasa.gov/users/new (free)" -ForegroundColor DarkYellow
    Write-Host "      Then create $netrcFile with:" -ForegroundColor DarkYellow
    Write-Host '      machine urs.earthdata.nasa.gov login YOUR_USER password YOUR_PASS' -ForegroundColor DarkYellow
}
Write-Host ""

if (-not $SkipRinex) {
    Write-Host "--- [1] RINEX 观测数据 (CDDIS) ---" -ForegroundColor Cyan
    $rinexDir = Join-Path $TargetDir "rinex"
    if (-not (Test-Path $rinexDir)) { New-Item -ItemType Directory -Path $rinexDir -Force | Out-Null }

    $stations = @(
        @{ Name = "WTZR"; Country = "DEU"; Role = "PPP-AR 24h static + long-baseline base" },
        @{ Name = "BRUX"; Country = "BEL"; Role = "Long-baseline rover (~600km from WTZR)" },
        @{ Name = "PORE"; Country = "FRA"; Role = "Medium-baseline rover (~800km from WTZR)" }
    )

    foreach ($st in $stations) {
        $obsName = "$($st.Name)00$($st.Country)_R_${Year}${Doy}0000_01D_30S_MO.rnx.gz"
        $obsUrl = "https://cddis.nasa.gov/archive/gnss/data/daily/$Year/$Doy/${yy}o/$obsName"
        $obsGz = Join-Path $rinexDir $obsName
        $obsFile = Join-Path $rinexDir ($obsName -replace '\.gz$', '')
        Write-Host "  [$($st.Name)] $($st.Role)" -ForegroundColor White
        $ok = Download-File -Url $obsUrl -OutFile $obsGz -Label $obsName
        if ($ok) {
            Decompress-Gz -GzFile $obsGz -OutFile $obsFile
        }
    }

    $navDir = Join-Path $TargetDir "nav"
    if (-not (Test-Path $navDir)) { New-Item -ItemType Directory -Path $navDir -Force | Out-Null }
    $navName = "BRDC00IGS_R_${Year}${Doy}0000_01D_MN.rnx.gz"
    $navUrl = "https://cddis.nasa.gov/archive/gnss/data/daily/$Year/$Doy/${yy}p/$navName"
    $navGz = Join-Path $navDir $navName
    $ok = Download-File -Url $navUrl -OutFile $navGz -Label $navName
    if ($ok) {
        $navFile = Join-Path $navDir ($navName -replace '\.gz$', '')
        Decompress-Gz -GzFile $navGz -OutFile $navFile
    }
    Write-Host ""
}

if (-not $SkipProduct) {
    Write-Host "--- [2] CODE MGEX 精密产品 (CDDIS) ---" -ForegroundColor Cyan
    $prodDir = Join-Path $TargetDir "product"
    if (-not (Test-Path $prodDir)) { New-Item -ItemType Directory -Path $prodDir -Force | Out-Null }

    $products = @(
        @{ Suffix = "05M_ORB.SP3.gz"; Type = "orbit" },
        @{ Suffix = "30S_CLK.CLK.gz"; Type = "clock" }
    )
    foreach ($prod in $products) {
        $prodName = "COD0MGXFIN_${Year}${Doy}0000_01D_$($prod.Suffix)"
        $prodUrl = "https://cddis.nasa.gov/archive/gnss/products/mgex/$gpsWeek/$prodName"
        $prodGz = Join-Path $prodDir $prodName
        $ok = Download-File -Url $prodUrl -OutFile $prodGz -Label "$($prod.Type): $prodName"
        if ($ok) {
            $prodFile = Join-Path $prodDir ($prodName -replace '\.gz$', '')
            Decompress-Gz -GzFile $prodGz -OutFile $prodFile
        }
    }

    $ionexName = "CODG${Doy}0.${yy}i.gz"
    $ionexUrl = "https://cddis.nasa.gov/archive/gnss/products/$gpsWeek/$ionexName"
    $ionexGz = Join-Path $prodDir $ionexName
    $ok = Download-File -Url $ionexUrl -OutFile $ionexGz -Label "ionex: $ionexName"
    if ($ok) {
        $ionexFile = Join-Path $prodDir ($ionexName -replace '\.gz$', '')
        Decompress-Gz -GzFile $ionexGz -OutFile $ionexFile
    }

    $erpName = "COD${Doy}0.${yy}e.gz"
    $erpUrl = "https://cddis.nasa.gov/archive/gnss/products/$gpsWeek/$erpName"
    $erpGz = Join-Path $prodDir $erpName
    $ok = Download-File -Url $erpUrl -OutFile $erpGz -Label "erp: $erpName"
    if ($ok) {
        $erpFile = Join-Path $prodDir ($erpName -replace '\.gz$', '')
        Decompress-Gz -GzFile $erpGz -OutFile $erpFile
    }
    Write-Host ""
}

if (-not $SkipRtklibSample) {
    Write-Host "--- [3] RTKLIB 样例数据 ---" -ForegroundColor Cyan
    Write-Host "  RTKLIB demo5 的 data/ 目录仅含 TLE, 不含 RINEX 观测数据" -ForegroundColor Gray
    Write-Host "  车载 RTK 样例数据需手动获取:" -ForegroundColor White
    Write-Host "    - rtkexplorer.com: http://rtkexplorer.com/download/car-data-u-blox-f9p-and-u-blox-m8p/" -ForegroundColor DarkYellow
    Write-Host "    - Google SDC:      https://github.com/google-research-datasets/smartphone-decimeter-challenge" -ForegroundColor DarkYellow
    Write-Host "    - RTKLIB sample:   http://www.rtklib.com/rtklibsample.zip (6.1MB, GPS short baseline)" -ForegroundColor DarkYellow
    Write-Host ""
}

Write-Host "--- [4] IGS 参考坐标 ---" -ForegroundColor Cyan
Write-Host "  IGS20 SINEX: https://files.igs.org/pub/station/coord/IGS20/IGS20.snx" -ForegroundColor DarkYellow
Write-Host "  已知站坐标 (ITRF2020, ~2023.0):" -ForegroundColor White
Write-Host "    WTZR (Wettzell, DE):  X=4079145.424  Y=931779.648  Z=4801558.831" -ForegroundColor DarkCyan
Write-Host "    BRUX (Brussels, BE):  X=4027894.006  Y=307045.599  Z=4919542.097" -ForegroundColor DarkCyan
Write-Host "    PORE (Poree, FR):     X=4187485.753  Y=266541.208  Z=4770261.078" -ForegroundColor DarkCyan
Write-Host ""

Write-Host "=== 下载完成 ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "数据可得性总结:" -ForegroundColor White
Write-Host "  [可获取] PPP-AR 24h 静态:  IGS 24h RINEX + CODE MGEX SP3/CLK (CDDIS, 需注册)" -ForegroundColor Green
Write-Host "  [可获取] 长基线 RTK:       WTZR+BRUX (~600km) 或 WTZR+PORE (~800km) (CDDIS, 需注册)" -ForegroundColor Green
Write-Host "  [可获取] 动态车载 RTK:     RTKLIB sample / rtkexplorer F9P 数据 / Google SDC" -ForegroundColor Green
Write-Host "  [稀缺]   城市峡谷/深遮挡:  公开 base+rover 深遮挡成对数据基本不存在" -ForegroundColor Red
Write-Host "  [稀缺]   Moving-Base:      公开双移动接收机同步观测数据极少" -ForegroundColor Red
Write-Host "  [半可得] PPP-RTK SSR:      MADOCA/HAS/CLAS 可注册获取, 但需录制+对齐, 工程量大" -ForegroundColor Yellow
Write-Host ""
if (-not $hasNetrc) {
    Write-Host "!! CDDIS 下载需要 NASA Earthdata Login (免费注册):" -ForegroundColor Red
    Write-Host "   1. 注册: https://urs.earthdata.nasa.gov/users/new" -ForegroundColor White
    Write-Host "   2. 创建 $netrcFile :" -ForegroundColor White
    Write-Host '   3. 重新运行此脚本' -ForegroundColor White
}