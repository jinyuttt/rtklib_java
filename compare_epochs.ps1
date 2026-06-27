$javaFile = "D:\rtklib\rtklib_java\RTKLIB_EX_2.5.0\rtk_java_result.pos"
$cFile = "D:\rtklib\rtklib_java\RTKLIB_EX_2.5.0\rtk_c_ref.pos"

$javaLines = Get-Content $javaFile | Where-Object { $_ -notmatch '^%' -and $_ -notmatch '^\s*$' }
$cLines = Get-Content $cFile | Where-Object { $_ -notmatch '^%' -and $_ -notmatch '^\s*$' }

Write-Host "Java数据行: $($javaLines.Count)  C数据行: $($cLines.Count)"
Write-Host ""
Write-Host "=== 逐行配对对比 (前25个历元) ==="
$fmt = "{0,4} {1,22} {2,10} {3,10} {4,10} {5,4} {6,4} | {7,8} {8,8} {9,8} {10,8}"
Write-Host ($fmt -f "Ep", "Time_C", "X_C", "Y_C", "Z_C", "Qj", "Qc", "dX", "dY", "dZ", "3D")
Write-Host ("-" * 110)

$sum3D = 0; $max3D = 0; $sumDx = 0; $sumDy = 0; $sumDz = 0
$count = [Math]::Min($javaLines.Count, $cLines.Count)

for ($i = 0; $i -lt $count; $i++) {
    $jParts = $javaLines[$i] -split '\s+'
    $cParts = $cLines[$i] -split '\s+'
    $jx = [double]$jParts[2]; $jy = [double]$jParts[3]; $jz = [double]$jParts[4]
    $cx = [double]$cParts[2]; $cy = [double]$cParts[3]; $cz = [double]$cParts[4]
    $dx = $jx - $cx; $dy = $jy - $cy; $dz = $jz - $cz
    $d3 = [Math]::Sqrt($dx*$dx + $dy*$dy + $dz*$dz)
    $sumDx += [Math]::Abs($dx); $sumDy += [Math]::Abs($dy); $sumDz += [Math]::Abs($dz)
    $sum3D += $d3
    if ($d3 -gt $max3D) { $max3D = $d3 }
    $cTime = "$($cParts[0]) $($cParts[1])"
    $qj = $jParts[5]; $qc = $cParts[5]
    if ($i -lt 25) {
        $mark = if ($d3 -gt 1.0) { " ***" } else { "" }
        Write-Host ($fmt -f ($i+1), $cTime, $cx, $cy, $cz, $qj, $qc, $dx, $dy, $dz, $d3) -NoNewline
        Write-Host $mark
    }
}

Write-Host ""
Write-Host "=== 统计 ($count 个历元) ==="
Write-Host ("平均偏差: |dX|={0:F4} |dY|={1:F4} |dZ|={2:F4} 3D={3:F4} m" -f ($sumDx/$count), ($sumDy/$count), ($sumDz/$count), ($sum3D/$count))
Write-Host ("最大3D偏差: {0:F4} m" -f $max3D)

# Show worst epochs
Write-Host ""
Write-Host "=== 偏差最大的10个历元 ==="
$deviations = @()
for ($i = 0; $i -lt $count; $i++) {
    $jParts = $javaLines[$i] -split '\s+'
    $cParts = $cLines[$i] -split '\s+'
    $dx = [double]$jParts[2] - [double]$cParts[2]
    $dy = [double]$jParts[3] - [double]$cParts[3]
    $dz = [double]$jParts[4] - [double]$cParts[4]
    $d3 = [Math]::Sqrt($dx*$dx + $dy*$dy + $dz*$dz)
    $deviations += [PSCustomObject]@{Ep=$i+1; CTime="$($cParts[0]) $($cParts[1])"; d3=$d3; dx=$dx; dy=$dy; dz=$dz}
}
$deviations | Sort-Object d3 -Descending | Select-Object -First 10 | Format-Table Ep, CTime, d3, dx, dy, dz -AutoSize