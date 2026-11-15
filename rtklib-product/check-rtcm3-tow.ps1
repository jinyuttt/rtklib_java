# RTCM3 SSR 电文时间连续性体检脚本
#
# 作用：逐帧解析 .rtcm3 文件，提取指定电文号（默认 1258）的 SSR 历元时间字段，
#       统计每个文件的帧数、首末历元、历元间隔异常数量与最大间隔，并汇总出
#       最干净的录制文件以及存在断层的文件。
#
# 用法：
#   .\check-rtcm3-tow.ps1
#   .\check-rtcm3-tow.ps1 -DataDir 'D:\gnss-val-workspace\lake\ssr\SSRA01CAS0\20261031'
#   .\check-rtcm3-tow.ps1 -MessageType 1057 -ExpectedDelta 5 -GapThreshold 60
#   .\check-rtcm3-tow.ps1 -CsvPath '.\rtcm3_tow_report.csv'
#
# 说明：1258 是 RTCM3 电文号（BDS SSR 轨道改正），不是帧数。历元字段位于
#       电文体第 12~31 位（20 位周内秒）；GLONASS SSR 电文则为 17 位日内秒。

[CmdletBinding()]
param(
    [string] $DataDir       = 'D:\gnss-val-workspace\lake\ssr\SSRA01CAS0\20261031',
    [int]    $MessageType   = 1258,
    [int]    $ExpectedDelta = 5,
    [int]    $GapThreshold  = 60,
    [string] $CsvPath       = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# GLONASS SSR 电文的历元字段是 17 位日内秒(TOD)，其余系统是 20 位周内秒(TOW)
$GloMessageTypes = @(1063, 1064, 1065, 1066, 1067, 1068, 1266)

$TypeNames = @{
    1057 = 'GPS SSR 轨道';   1058 = 'GPS SSR 钟差';   1059 = 'GPS SSR 码偏差'
    1240 = 'GAL SSR 轨道';   1241 = 'GAL SSR 钟差';   1242 = 'GAL SSR 码偏差'
    1258 = 'BDS SSR 轨道';   1259 = 'BDS SSR 钟差';   1260 = 'BDS SSR 码偏差'
    1265 = 'GPS SSR 相位偏差'; 1267 = 'GAL SSR 相位偏差'; 1270 = 'BDS SSR 相位偏差'
}

# 从字节数组的指定位偏移开始读取 Count 个比特（大端，高位在前）
function Get-Bits {
    param(
        [byte[]] $Buf,
        [int]    $StartBit,
        [int]    $Count
    )
    $v = 0
    for ($k = 0; $k -lt $Count; $k++) {
        $pos = $StartBit + $k
        $b   = [int]$Buf[($pos -shr 3)]
        $bit = ($b -shr (7 - ($pos -band 7))) -band 1
        $v   = ($v -shl 1) -bor $bit
    }
    return $v
}

# 解析单个 RTCM3 文件：逐帧跳读，只收集目标电文的历元时间
function Read-RtcmFile {
    param(
        [string] $Path,
        [int]    $Type
    )

    $buf     = [System.IO.File]::ReadAllBytes($Path)
    $n       = $buf.Length
    $isGlo   = $GloMessageTypes -contains $Type
    $towBits = if ($isGlo) { 17 } else { 20 }
    $wrap    = if ($isGlo) { 86400 } else { 604800 }

    $totalFrames = 0
    $tows        = New-Object System.Collections.Generic.List[long]

    $i = 0
    while ($i -le $n - 6) {
        if ($buf[$i] -ne 0xD3) { $i++; continue }
        $len = (([int]$buf[$i + 1] -band 0x03) -shl 8) -bor [int]$buf[$i + 2]
        if ($len -le 0 -or ($i + $len + 6) -gt $n) { $i++; continue }

        $payloadStart = $i + 3
        $msg = Get-Bits -Buf $buf -StartBit ($payloadStart * 8) -Count 12
        $totalFrames++

        if ($msg -eq $Type) {
            $tows.Add([long](Get-Bits -Buf $buf -StartBit ($payloadStart * 8 + 12) -Count $towBits))
        }
        $i = $payloadStart + $len + 3
    }

    # 历元间隔：相邻两帧的历元时间差，负值按跨周/跨日回绕处理
    $ne5  = 0
    $dup  = 0
    $max  = 0
    $span = 0
    $gaps = New-Object System.Collections.Generic.List[long]

    for ($j = 1; $j -lt $tows.Count; $j++) {
        $d = $tows[$j] - $tows[$j - 1]
        if ($d -lt 0) { $d += $wrap }
        $span += $d
        if ($d -eq 0) { $dup++ }
        if ($d -ne $ExpectedDelta) { $ne5++ }
        if ($d -gt $max) { $max = $d }
        if ($d -gt $GapThreshold) { $gaps.Add($d) }
    }

    [pscustomobject]@{
        File       = Split-Path $Path -Leaf
        FrameCount = $totalFrames
        TypeFrames = $tows.Count
        FirstTow   = if ($tows.Count -gt 0) { $tows[0] } else { $null }
        LastTow    = if ($tows.Count -gt 0) { $tows[$tows.Count - 1] } else { $null }
        SpanSec    = $span
        DeltaNe5   = $ne5
        Dup0       = $dup
        MaxDelta   = $max
        GapCount   = $gaps.Count
        Gaps       = ($gaps -join ',')
    }
}

if (-not (Test-Path -LiteralPath $DataDir)) {
    Write-Host ("目录不存在: {0}" -f $DataDir) -ForegroundColor Red
    exit 1
}

$files = Get-ChildItem -LiteralPath $DataDir -File -Filter '*.rtcm3' | Sort-Object Name
if ($files.Count -eq 0) {
    Write-Host ("目录下没有 .rtcm3 文件: {0}" -f $DataDir) -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host '==== RTCM3 时间连续性体检 ====' -ForegroundColor Cyan
Write-Host ("目录     : {0}" -f $DataDir)
Write-Host ("目标电文 : {0} ({1})" -f $MessageType, $TypeNames[$MessageType])
Write-Host ("期望间隔 : {0} s    断层阈值: {1} s" -f $ExpectedDelta, $GapThreshold)
Write-Host ''

$results = foreach ($f in $files) {
    Write-Progress -Activity '解析 RTCM3' -Status $f.Name -PercentComplete ([int]([array]::IndexOf($files, $f) / $files.Count * 100))
    Read-RtcmFile -Path $f.FullName -Type $MessageType
}
Write-Progress -Activity '解析 RTCM3' -Completed

$results | Format-Table -AutoSize -Property `
    @{ L = 'File';       E = { $_.File } },
    @{ L = 'FrameCount'; E = { $_.FrameCount } },
    @{ L = 'TypeFrames'; E = { $_.TypeFrames } },
    @{ L = 'FirstTow';   E = { $_.FirstTow } },
    @{ L = 'LastTow';    E = { $_.LastTow } },
    @{ L = 'SpanSec';    E = { $_.SpanSec } },
    @{ L = 'Delta!=5';   E = { $_.DeltaNe5 } },
    @{ L = 'Dup(0s)';    E = { $_.Dup0 } },
    @{ L = 'MaxDelta';   E = { $_.MaxDelta } },
    @{ L = 'Gaps>60s';   E = { $_.Gaps } }

if ($CsvPath -ne '') {
    $results | Export-Csv -LiteralPath $CsvPath -NoTypeInformation -Encoding UTF8
    Write-Host ("已导出 CSV: {0}" -f $CsvPath)
}

$candidates = @($results | Where-Object { $_.TypeFrames -ge 2 })
if ($candidates.Count -eq 0) {
    Write-Host '没有任何文件包含足够的样本帧，无法判定。' -ForegroundColor Yellow
    exit 0
}

# 录制断层会直接破坏回放，因此先排除有断层的文件，再在其中挑 delta!=5 最少、
# 最大 delta 最小的文件；只有当所有文件都有断层时才退化为纯计数排序。
$gapped   = @($results | Where-Object { $_.GapCount -gt 0 })
$gapFree  = @($candidates | Where-Object { $_.GapCount -eq 0 })
if ($gapFree.Count -gt 0) {
    $pool  = $gapFree
    $basis = '无断层文件'
}
else {
    $pool  = $candidates
    $basis = '全部文件均有断层，按 delta!=5 / 最大 delta 排序'
}

$cleanest = $pool |
    Sort-Object -Property DeltaNe5, MaxDelta, @{ Expression = 'TypeFrames'; Descending = $true } |
    Select-Object -First 1

Write-Host ''
Write-Host '--- 汇总 ---' -ForegroundColor Cyan
Write-Host ("最干净文件: {0}  (Delta!=5={1} (其中重复历元 {2}), MaxDelta={3}, 电文帧={4}, 跨度={5}s, 评选范围: {6})" -f `
    $cleanest.File, $cleanest.DeltaNe5, $cleanest.Dup0, $cleanest.MaxDelta, $cleanest.TypeFrames, $cleanest.SpanSec, $basis) `
    -ForegroundColor Green
Write-Host ("无断层文件: {0}" -f $(if ($gapFree.Count -gt 0) { ($gapFree | ForEach-Object { $_.File }) -join ', ' } else { '无' }))

Write-Host ''
Write-Host ("出现断层 (delta > {0}s) 的文件:" -f $GapThreshold) -ForegroundColor Yellow
if ($gapped.Count -eq 0) {
    Write-Host '  无'
}
else {
    foreach ($r in $gapped) {
        Write-Host ("  {0}: 最大 {1}s, 断层 [{2}]" -f $r.File, $r.MaxDelta, $r.Gaps)
    }
}