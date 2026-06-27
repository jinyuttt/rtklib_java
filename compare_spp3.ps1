$jFile = 'C:\Users\admin\Desktop\rtklib_java_results\2_spp_result.pos'
$cFile = 'D:\code\rtklib_java\540423494727\2026-06-08\spp_c_full.pos'

$jLines = [IO.File]::ReadAllLines($jFile) | Where-Object { $_ -match '^\s+20' }
$cLines = [IO.File]::ReadAllLines($cFile) | Where-Object { $_ -match '^2026' }

Write-Host ('Java data epochs: ' + $jLines.Count)
Write-Host ('C data epochs: ' + $cLines.Count)

$cMap = @{}
foreach ($line in $cLines) {
    $p = $line.Trim() -split '\s+'
    $key = $p[0] + ' ' + $p[1]
    $cMap[$key] = $p
}

$diffs = @()
foreach ($line in $jLines) {
    $p = $line.Trim() -split '\s+'
    $date = $p[0]
    $time = $p[1]
    $timeShort = $time -replace '(\d+\.\d{3})\d*', '$1'
    $key = $date + ' ' + $timeShort
    $cData = $cMap[$key]
    if ($cData -ne $null) {
        $jLat = [double]$p[2]
        $jLon = [double]$p[3]
        $jH = [double]$p[4]
        $jNs = [int]$p[6]
        $cLat = [double]$cData[2]
        $cLon = [double]$cData[3]
        $cH = [double]$cData[4]
        $cNs = [int]$cData[6]
        $dLat = [math]::Abs($jLat - $cLat)
        $dLon = [math]::Abs($jLon - $cLon)
        $dH = [math]::Abs($jH - $cH)
        $diffs += [PSCustomObject]@{Time=$key; dLat=$dLat; dLon=$dLon; dH=$dH; jNs=$jNs; cNs=$cNs; jLat=$jLat; cLat=$cLat; jH=$jH; cH=$cH}
    }
}

$n = $diffs.Count
Write-Host ('Matched epochs: ' + $n)
if ($n -eq 0) { exit 0 }

$sumLat = ($diffs | Measure-Object -Property dLat -Sum).Sum
$sumH = ($diffs | Measure-Object -Property dH -Sum).Sum
$maxLat = ($diffs | Measure-Object -Property dLat -Maximum).Maximum
$maxH = ($diffs | Measure-Object -Property dH -Maximum).Maximum
$nsDiff = ($diffs | Where-Object { $_.jNs -ne $_.cNs }).Count

Write-Host ('Avg lat diff: ' + [math]::Round($sumLat/$n*111000, 2) + ' m, max: ' + [math]::Round($maxLat*111000, 2) + ' m')
Write-Host ('Avg h diff: ' + [math]::Round($sumH/$n, 2) + ' m, max: ' + [math]::Round($maxH, 2) + ' m')
Write-Host ('Ns diff count: ' + $nsDiff + '/' + $n)

Write-Host ''
Write-Host '=== First 5 ==='
$diffs | Select-Object -First 5 | ForEach-Object { Write-Host ('  ' + $_.Time + ' dLat=' + [math]::Round($_.dLat*111000,2) + 'm dH=' + [math]::Round($_.dH,2) + 'm jNs=' + $_.jNs + ' cNs=' + $_.cNs + ' jLat=' + [math]::Round($_.jLat,9) + ' cLat=' + [math]::Round($_.cLat,9)) }

Write-Host ''
Write-Host '=== Last 3 ==='
$diffs | Select-Object -Last 3 | ForEach-Object { Write-Host ('  ' + $_.Time + ' dLat=' + [math]::Round($_.dLat*111000,2) + 'm dH=' + [math]::Round($_.dH,2) + 'm jNs=' + $_.jNs + ' cNs=' + $_.cNs) }

Write-Host ''
$jLats = @(); $jHs = @()
foreach ($d in $diffs) { $jLats += $d.jLat; $jHs += $d.jH }
$cLats = @(); $cHs = @()
foreach ($d in $diffs) { $cLats += $d.cLat; $cHs += $d.cH }

$jLatAvg = ($jLats | Measure-Object -Average).Average
$cLatAvg = ($cLats | Measure-Object -Average).Average
$jHAvg = ($jHs | Measure-Object -Average).Average
$cHAvg = ($cHs | Measure-Object -Average).Average

$jLatVar = 0; foreach ($v in $jLats) { $jLatVar += [math]::Pow($v - $jLatAvg, 2) }
$cLatVar = 0; foreach ($v in $cLats) { $cLatVar += [math]::Pow($v - $cLatAvg, 2) }
$jHVar = 0; foreach ($v in $jHs) { $jHVar += [math]::Pow($v - $jHAvg, 2) }
$cHVar = 0; foreach ($v in $cHs) { $cHVar += [math]::Pow($v - $cHAvg, 2) }

Write-Host ('Java lat std: ' + [math]::Round([math]::Sqrt($jLatVar / $jLats.Count) * 111000, 2) + ' m')
Write-Host ('C lat std: ' + [math]::Round([math]::Sqrt($cLatVar / $cLats.Count) * 111000, 2) + ' m')
Write-Host ('Java h std: ' + [math]::Round([math]::Sqrt($jHVar / $jHs.Count), 2) + ' m')
Write-Host ('C h std: ' + [math]::Round([math]::Sqrt($cHVar / $cHs.Count), 2) + ' m')

Write-Host ''
Write-Host '=== Largest lat diff (top 3) ==='
$diffs | Sort-Object -Property dLat -Descending | Select-Object -First 3 | ForEach-Object { Write-Host ('  ' + $_.Time + ' dLat=' + [math]::Round($_.dLat*111000,2) + 'm jLat=' + [math]::Round($_.jLat,9) + ' cLat=' + [math]::Round($_.cLat,9) + ' jH=' + [math]::Round($_.jH,2) + ' cH=' + [math]::Round($_.cH,2)) }