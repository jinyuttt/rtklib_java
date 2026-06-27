$javaFile = 'C:\Users\admin\Desktop\rtklib_java_results\2_spp_result.pos'
$cFile = 'D:\code\rtklib_java\540423494727\2026-06-08\spp_c_full.pos'

$javaLines = Get-Content $javaFile | Where-Object { $_ -match '^\s+20' }
$cLines = Get-Content $cFile | Where-Object { $_ -match '^2026' }

$cMap = @{}
foreach ($line in $cLines) {
    $parts = $line.Trim() -split '\s+'
    $key = $parts[0] + ' ' + $parts[1]
    $cMap[$key] = $parts
}

$diffs = @()
foreach ($line in $javaLines) {
    $parts = $line.Trim() -split '\s+'
    $date = $parts[0]
    $time = $parts[1]
    # Java time format: 17:00:33.000000, C format: 17:00:33.000
    # Truncate Java time seconds to 3 decimal places for matching
    $timeShort = $time -replace '(\d+\.\d{3})\d*', '$1'
    $key = $date + ' ' + $timeShort
    $cData = $cMap[$key]
    if ($cData -ne $null) {
        $jLat = [double]$parts[2]
        $jLon = [double]$parts[3]
        $jH = [double]$parts[4]
        $jNs = [int]$parts[6]
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

Write-Host ('Matched epochs: ' + $diffs.Count)
$n = $diffs.Count
if ($n -eq 0) { exit 0 }

$sumLat = ($diffs | Measure-Object -Property dLat -Sum).Sum
$sumLon = ($diffs | Measure-Object -Property dLon -Sum).Sum
$sumH = ($diffs | Measure-Object -Property dH -Sum).Sum
$maxLat = ($diffs | Measure-Object -Property dLat -Maximum).Maximum
$maxLon = ($diffs | Measure-Object -Property dLon -Maximum).Maximum
$maxH = ($diffs | Measure-Object -Property dH -Maximum).Maximum
$nsDiff = ($diffs | Where-Object { $_.jNs -ne $_.cNs }).Count

Write-Host ('Avg lat diff: ' + [math]::Round($sumLat/$n*111000, 1) + ' m, max: ' + [math]::Round($maxLat*111000, 1) + ' m')
Write-Host ('Avg lon diff: ' + [math]::Round($sumLon/$n*111000*[math]::Cos([math]::ToRadians(29.19)), 1) + ' m, max: ' + [math]::Round($maxLon*111000*[math]::Cos([math]::ToRadians(29.19)), 1) + ' m')
Write-Host ('Avg h diff: ' + [math]::Round($sumH/$n, 1) + ' m, max: ' + [math]::Round($maxH, 1) + ' m')
Write-Host ('Ns diff count: ' + $nsDiff + '/' + $n)

Write-Host ''
Write-Host '=== First 10 ==='
$diffs | Select-Object -First 10 | ForEach-Object { Write-Host ('  ' + $_.Time + ' dLat=' + [math]::Round($_.dLat*111000,1) + 'm dH=' + [math]::Round($_.dH,1) + 'm jNs=' + $_.jNs + ' cNs=' + $_.cNs + ' jLat=' + [math]::Round($_.jLat,7) + ' cLat=' + [math]::Round($_.cLat,7)) }

Write-Host ''
Write-Host '=== Last 5 ==='
$diffs | Select-Object -Last 5 | ForEach-Object { Write-Host ('  ' + $_.Time + ' dLat=' + [math]::Round($_.dLat*111000,1) + 'm dH=' + [math]::Round($_.dH,1) + 'm jNs=' + $_.jNs + ' cNs=' + $_.cNs) }

Write-Host ''
Write-Host '=== Largest lat diff (top 5) ==='
$diffs | Sort-Object -Property dLat -Descending | Select-Object -First 5 | ForEach-Object { Write-Host ('  ' + $_.Time + ' dLat=' + [math]::Round($_.dLat*111000,1) + 'm jLat=' + [math]::Round($_.jLat,7) + ' cLat=' + [math]::Round($_.cLat,7) + ' jH=' + [math]::Round($_.jH,1) + ' cH=' + [math]::Round($_.cH,1)) }

Write-Host ''
Write-Host '=== Smallest lat diff (top 5) ==='
$diffs | Sort-Object -Property dLat | Select-Object -First 5 | ForEach-Object { Write-Host ('  ' + $_.Time + ' dLat=' + [math]::Round($_.dLat*111000,1) + 'm jLat=' + [math]::Round($_.jLat,7) + ' cLat=' + [math]::Round($_.cLat,7) + ' jH=' + [math]::Round($_.jH,1) + ' cH=' + [math]::Round($_.cH,1)) }