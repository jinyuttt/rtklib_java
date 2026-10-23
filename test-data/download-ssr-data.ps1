# =====================================================================
# download-ssr-data.ps1 : PPP-RTK (SSR) / PPP-AR (OSB) test data fetcher
#
# Three independent sources, pick whichever works (mode -Source):
#   A "pppwizard" - CNES PPP-Wizard REAL_TIME cit*.bia (free, no account)
#       -> phase biases (Bias SINEX) for PPP-AR branch; coverage: from
#          2024-01-07 (GPS week 2298) up to 2024-01-10 (last file cit22983)
#   B "cddis"     - IGS RTS daily SSR files (free Earthdata account required)
#       -> text SSR CLK/ORB files; historical archive incl. 2024 DOY 060
#   C "ntrip"     - live NTRIP stream recording (free IGS caster account)
#       -> SSRC00CNE1 (CNES global IGS-SSR) raw RTCM/IGS-SSR frames
#
# Usage examples:
#   pwsh> .\download-ssr-data.ps1 -Source pppwizard -Year 2024 -Doy 10
#   pwsh> .\download-ssr-data.ps1 -Source cddis    -Year 2024 -Doy 60
#         ($env:EARTHDATA_USER / $env:EARTHDATA_PASS must be set)
#   pwsh> .\download-ssr-data.ps1 -Source ntrip -Seconds 7200
#         ($env:IGS_NTRIP_USER = "user:pass"; str2str.exe on PATH;
#          mounts default SSRC00CNE1, override with -Mount)
#
# Notes:
# - All products are from official public services (CNES/IGS/NASA).
# - Files land under test-data\gnss-validation\product\ which is
#   .gitignored: never commit downloaded data to the public repository.
# =====================================================================
param(
    [Parameter(Mandatory = $true)][ValidateSet("pppwizard", "cddis", "ntrip")][string]$Source,
    [int]$Year = 2024,
    [int]$Doy = 10,
    [string]$Mount = "SSRC00CNE1",
    [int]$Seconds = 7200
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$outRoot = Join-Path $repo "test-data\gnss-validation\product"

function Get-GpsWeek([datetime]$dt) {
    # Avoid Get-Date string parsing (locale-dependent on zh-CN hosts)
    $epoch = New-Object DateTime(1980, 1, 6)
    [int][math]::Floor(($dt.Date - $epoch).TotalDays / 7)
}

function Get-DoyDate([int]$y, [int]$d) {
    $dt = New-Object DateTime($y, 1, 1)
    $dt.AddDays($d - 1)
}

function Expand-Gzip([string]$gz, [string]$out) {
    # tar -xzf fails on plain single-file gzip ("Unrecognized archive format")
    $inStream = [System.IO.File]::OpenRead($gz)
    $gzip = New-Object System.IO.Compression.GzipStream($inStream, [System.IO.Compression.CompressionMode]::Decompress)
    $outStream = [System.IO.File]::Create($out)
    $gzip.CopyTo($outStream)
    $outStream.Dispose(); $gzip.Dispose(); $inStream.Dispose()
}

function Get-CddisCredentials {
    # Credentials NEVER go into the repo. Resolution order:
    #   1. env EARTHDATA_USER / EARTHDATA_PASS
    #   2. ~/.rtklib/credentials.properties  (outside the repo; format:
    #      cddis.username=YOURUSER
    #      cddis.password=YOURPASS
    #      same file already used by Java ProductDownloader, see
    #      GNSS_VALIDATION_PIPELINE.md 6.5)
    if ($env:EARTHDATA_USER -and $env:EARTHDATA_PASS) {
        return @{ U = $env:EARTHDATA_USER; P = $env:EARTHDATA_PASS }
    }
    $props = Join-Path $HOME ".rtklib\credentials.properties"
    if (Test-Path $props) {
        $map = @{}
        Get-Content $props | ForEach-Object {
            if ($_ -match '^\s*cddis\.username\s*=\s*(.+?)\s*$') { $map.U = $Matches[1] }
            if ($_ -match '^\s*cddis\.password\s*=\s*(.+?)\s*$') { $map.P = $Matches[1] }
        }
        if ($map.U -and $map.P) { return $map }
    }
    throw "No Earthdata credentials found. Create $props with cddis.username / cddis.password lines (kept outside the repo), or set EARTHDATA_USER/EARTHDATA_PASS."
}

function Save-File([string]$url, [string]$dst, [string]$user = "", [string]$pass = "", [switch]$Follow, [string]$NetrcFile = "") {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dst) | Out-Null
    $args = @("-sS", "-m", "300", "-o", $dst, "-w", "%{http_code}", $url)
    if ($user -ne "") { $args = @("-sS", "-m", "300", "-u", "$user`:$pass", "-o", $dst, "-w", "%{http_code}", $url) }
    if ($NetrcFile -ne "") { $args = @("-sS", "-m", "300", "--netrc-file", $NetrcFile, "-L", "-c", "$env:TEMP\cddis_cookies.txt", "-b", "$env:TEMP\cddis_cookies.txt", "-o", $dst, "-w", "%{http_code}", $url) }
    elseif ($Follow) { $args += @("-L", "-c", "$env:TEMP\cddis_cookies.txt", "-b", "$env:TEMP\cddis_cookies.txt") }
    $code = & curl.exe @args
    if ($code -ne "200") { Write-Warning "HTTP $code for $url"; if (Test-Path $dst) { Remove-Item $dst -Force }; return $false }
    Write-Host "OK  $url -> $dst ($( (Get-Item $dst).Length ) bytes)"
    return $true
}

switch ($Source) {

    "pppwizard" {
        # CNES cit files: name = GPSweek*10 + dayOfWeek(0=Sun), packed next day.
        # Known coverage of cit series: 2024-01-08 (cit22961) .. 2024-01-24 (cit22983).
        $dt = Get-DoyDate $Year $Doy
        for ($try = 0; $try -lt 3; $try++) {
            $d2 = $dt.AddDays($try)
            $week = Get-GpsWeek $d2
            $name = "cit$week$([int]$d2.DayOfWeek).bia.gz"
            $dst = Join-Path $outRoot "cnes_bia\$name"
            $url = "http://www.ppp-wizard.net/products/REAL_TIME/$name"
            if (Save-File $url $dst) {
                $plain = $dst -replace "\.gz$", ""
                if (Test-Path $plain) { Remove-Item $plain -Force }
                Expand-Gzip $dst $plain
                if (-not (Test-Path $plain)) { Write-Warning "decompress failed for $dst"; return }
                Write-Host "Bias SINEX ready: $plain ($( (Get-Item $plain).Length ) bytes, data day $($d2.ToString('yyyy-MM-dd')))"
                Write-Host "Pair with rover RINEX of the SAME day via ensureBjfsRinex, and WUM SP3/CLK via ProductDownloader."
                return
            }
        }
        Write-Warning "No cit file found within 3 days of $($dt.ToString('yyyy-MM-dd')); cit series ends 2024-01-24."
    }

    "cddis" {
        $cred = Get-CddisCredentials
        # CDDIS /gnss/products/rtpp: IGS RTS streams decoded into batch products
        #   igcWWWW{D}.sp3.Z / igcWWWW{D}.clk.Z  (30 s, IGC01 CoM solution)
        #   igtWWWW{D}.sp3.Z / igtWWWW{D}.clk.Z  (batch combination)
        # NOTE: these are standard SP3/CLK precision products (same role as WUM
        # SP3/CLK, extra redundancy source), NOT state-space SSR messages.
        # True SSR correction files come from ntrip recording (-Source ntrip).
        #
        # URS auth: CDDIS OAuth does not accept plain `curl -u user:pass`
        # (302 lands on the URS HTML login page). Officially supported method
        # is .netrc machine auth via curl --netrc-file. We materialize a
        # TRANSIENT netrc from the user-managed credentials.properties and
        # delete it immediately after the run (no second persistent copy).
        $netrc = Join-Path $env:TEMP "netrc_cddis_tmp"
        "machine cddis.nasa.gov login $($cred.U) password $($cred.P)`nmachine urs.earthdata.nasa.gov login $($cred.U) password $($cred.P)" | Set-Content $netrc -Encoding ASCII
        try {
            $dt = Get-DoyDate $Year $Doy
            $yyyy = $dt.ToString("yyyy")
            $dow = [int]$dt.DayOfWeek
            # REAL layout (verified 2026-10 by authenticated directory listing;
            # the NASA doc page's WWWW/ GPS-week scheme is outdated):
            #   igc{YYYY}{dow}.sp3.Z / .clk.Z   (IGC01 real-time stream, CoM, 30 s)
            #   igt{YYYY}{dow}.clk.Z / .sum.Z   (batch combination; no sp3)
            $base = "https://cddis.nasa.gov/archive/gnss/products/rtpp/$yyyy"
            foreach ($name in @("igc$yyyy$dow.sp3", "igc$yyyy$dow.clk", "igt$yyyy$dow.clk", "igt$yyyy$dow.sum")) {
                $dst = Join-Path $outRoot "igs_rts\$name.Z"
                Save-File "$base/$name.Z" $dst -NetrcFile $netrc | Out-Null
            }
        } finally {
            Remove-Item $netrc -Force -ErrorAction SilentlyContinue
        }
    }

    "ntrip" {
        if (-not $env:IGS_NTRIP_USER) { throw "Set `$env:IGS_NTRIP_USER='user:pass' (self-service account: https://igs.bkg.bund.de/ntrip, register once)" }
        $str2str = Get-Command str2str.exe -ErrorAction SilentlyContinue
        if (-not $str2str) { throw "str2str.exe (RTKLIB C tools) not on PATH; alternatively use BKG Ntrip Client (BNC) GUI to record." }
        $out = Join-Path $outRoot ("ntrip\{0}_{1}.rtcm3" -f $Mount, (Get-Date -Format "yyyyMMdd_HHmmss"))
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $out) | Out-Null
        Write-Host "Recording $Mount for $Seconds s -> $out  (Ctrl+C to stop early)"
        & str2str.exe -in "ntrip://$($env:IGS_NTRIP_USER)@products.igs-ip.net:2101/$Mount" -out "file://$out" -t 1
        Write-Host "Raw stream saved. Feed to SsrDecoder/CssrDiagTest path for nav.ssr population."
    }
}
