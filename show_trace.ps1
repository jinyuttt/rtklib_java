$traceFile = 'D:\code\rtklib_java\540423494727\2026-06-08\spp_c_trace.pos.trace'
$lines = [IO.File]::ReadAllLines($traceFile)
Write-Host ('Total lines: ' + $lines.Count)

$idx = 0
foreach ($line in $lines) {
    if ($line -match 'rescode|estpos|pntpos|satposs|satexclude|prange') {
        Write-Host $line
        $idx++
        if ($idx -ge 60) { break }
    }
}