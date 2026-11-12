$ErrorActionPreference = 'Stop'
$v = "D:\code\rtklib_java\rtklib-otl\validate"
$md = Get-Content "$v\comparison_4stations.md"
$names = @('M2','S2','N2','K2','K1','O1','P1','Q1','Mf','Mm','Ssa')

$mdAmp = @{}
$mdPha = @{}
$cur = $null
foreach ($l in $md) {
    if ($l -match '## Station \d+: (\w+)') {
        $cur = $Matches[1]
        $mdAmp[$cur] = @{}
        $mdPha[$cur] = @{}
        continue
    }
    if ($cur -and $l -match '^\| (M2|S2|N2|K2|K1|O1|P1|Q1|Mf|Mm|Ssa)\*?\s*\|') {
        $p = $l -split '\|'
        $k = $p[1].Trim().TrimEnd('*')
        $mdAmp[$cur][$k] = [double]$p[2].Trim()
        $mdPha[$cur][$k] = [double]$p[5].Trim()
    }
}

"[1] MD Java column vs committed baseline BLQ (radial amp/phase)"
$bad = 0
foreach ($s in @('LHASA','LINZHI','CHENGDU','MENGDIGOU')) {
    $blq = Get-Content "$v\$($s.ToLower())_java.blq"
    $amp = @($blq[3].Trim() -split '\s+')
    $pha = @($blq[6].Trim() -split '\s+')
    for ($i = 0; $i -lt 11; $i++) {
        $k = $names[$i]
        $da = [math]::Abs($mdAmp[$s][$k] - [double]$amp[$i])
        $dp = [math]::Abs($mdPha[$s][$k] - [double]$pha[$i])
        if ($da -gt 0.00005 -or $dp -gt 0.00005) {
            "MISMATCH $s $k md=($($mdAmp[$s][$k]),$($mdPha[$s][$k])) blq=($($amp[$i]),$($pha[$i]))"
            $bad++
        }
    }
}
"mismatches: $bad / 44 groups (4 stations x 11 constituents)"

""
"[2] Rewritten output vs committed baseline BLQ (all 6 numeric rows)"
$new = "$env:TEMP\otl_new_run\validate"
foreach ($s in @('lhasa','linzhi','chengdu','mengdigou')) {
    $b = Get-Content "$v\$($s)_java.blq"
    $n = Get-Content "$new\$($s)_java.blq"
    $maxd = 0.0
    $rows = 0
    for ($r = 3; $r -le 8; $r++) {
        $bv = @($b[$r].Trim() -split '\s+')
        $nv = @($n[$r].Trim() -split '\s+')
        for ($c = 0; $c -lt 11; $c++) {
            $d = [math]::Abs([double]$bv[$c] - [double]$nv[$c])
            if ($d -gt $maxd) { $maxd = $d }
            $rows++
        }
    }
    $hdr = if ($b[2] -ceq $n[2]) { "header same" } else { "header differs (baseline has station column)" }
    "{0,-10} values={1}  maxAbsDiff={2}  {3}" -f $s.ToUpper(), $rows, $maxd, $hdr
}