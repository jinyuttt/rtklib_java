$cFile = 'D:\code\rtklib_java\540423494727\2026-06-08\spp_c_full.pos'
$jFile = 'C:\Users\admin\Desktop\rtklib_java_results\2_spp_result.pos'

$cLines = [IO.File]::ReadAllLines($cFile) | Where-Object { $_ -match '^2026' }
$jLines = [IO.File]::ReadAllLines($jFile) | Where-Object { $_ -match '^\s+20' }

Write-Host ('C data epochs: ' + $cLines.Count)
Write-Host ('Java data epochs: ' + $jLines.Count)

Write-Host ''
Write-Host '=== C version first 3 ==='
$cLines | Select-Object -First 3 | ForEach-Object { Write-Host $_ }

Write-Host ''
Write-Host '=== Java version first 3 ==='
$jLines | Select-Object -First 3 | ForEach-Object { Write-Host $_ }

Write-Host ''
Write-Host '=== C version last 3 ==='
$cLines | Select-Object -Last 3 | ForEach-Object { Write-Host $_ }

Write-Host ''
Write-Host '=== Java version last 3 ==='
$jLines | Select-Object -Last 3 | ForEach-Object { Write-Host $_ }

Write-Host ''
$cLats = @(); $cHs = @()
foreach ($line in $cLines) {
    $p = $line.Trim() -split '\s+'
    $cLats += [double]$p[2]
    $cHs += [double]$p[4]
}
$cLatMin = ($cLats | Measure-Object -Minimum).Minimum
$cLatMax = ($cLats | Measure-Object -Maximum).Maximum
$cLatAvg = ($cLats | Measure-Object -Average).Average
$cHMin = ($cHs | Measure-Object -Minimum).Minimum
$cHMax = ($cHs | Measure-Object -Maximum).Maximum
$cHAvg = ($cHs | Measure-Object -Average).Average

Write-Host ('C lat: min=' + [math]::Round($cLatMin, 7) + ' max=' + [math]::Round($cLatMax, 7) + ' avg=' + [math]::Round($cLatAvg, 7))
Write-Host ('C h:   min=' + [math]::Round($cHMin, 1) + ' max=' + [math]::Round($cHMax, 1) + ' avg=' + [math]::Round($cHAvg, 1))

$jLats = @(); $jHs = @()
foreach ($line in $jLines) {
    $p = $line.Trim() -split '\s+'
    $jLats += [double]$p[2]
    $jHs += [double]$p[4]
}
$jLatMin = ($jLats | Measure-Object -Minimum).Minimum
$jLatMax = ($jLats | Measure-Object -Maximum).Maximum
$jLatAvg = ($jLats | Measure-Object -Average).Average
$jHMin = ($jHs | Measure-Object -Minimum).Minimum
$jHMax = ($jHs | Measure-Object -Maximum).Maximum
$jHAvg = ($jHs | Measure-Object -Average).Average

Write-Host ''
Write-Host ('Java lat: min=' + [math]::Round($jLatMin, 7) + ' max=' + [math]::Round($jLatMax, 7) + ' avg=' + [math]::Round($jLatAvg, 7))
Write-Host ('Java h:   min=' + [math]::Round($jHMin, 1) + ' max=' + [math]::Round($jHMax, 1) + ' avg=' + [math]::Round($jHAvg, 1))

Write-Host ''
$cLatVar = 0
foreach ($v in $cLats) { $cLatVar += [math]::Pow($v - $cLatAvg, 2) }
Write-Host ('C lat std: ' + [math]::Round([math]::Sqrt($cLatVar / $cLats.Count) * 111000, 1) + ' m')

$jLatVar = 0
foreach ($v in $jLats) { $jLatVar += [math]::Pow($v - $jLatAvg, 2) }
Write-Host ('Java lat std: ' + [math]::Round([math]::Sqrt($jLatVar / $jLats.Count) * 111000, 1) + ' m')

$cHVar = 0
foreach ($v in $cHs) { $cHVar += [math]::Pow($v - $cHAvg, 2) }
Write-Host ('C h std: ' + [math]::Round([math]::Sqrt($cHVar / $cHs.Count), 1) + ' m')

$jHVar = 0
foreach ($v in $jHs) { $jHVar += [math]::Pow($v - $jHAvg, 2) }
Write-Host ('Java h std: ' + [math]::Round([math]::Sqrt($jHVar / $jHs.Count), 1) + ' m')