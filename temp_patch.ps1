$f = "D:\code\rtklib_java\src\main\java\org\rtklib\java\pntpos\SppCore.java"
$lines = [System.IO.File]::ReadAllLines($f)
$LineNo = 247
$oldLine = $lines[$LineNo - 1]
Write-Host "OLD Line $LineNo: $oldLine"

# Create new lines (split into 3 lines)
$new1 = '                debugLog.append("  sat=").append(sat).append": sat2freq=0, code[0]=").append(obs[i].code[0]);'
$new2 = '                for (int fi = 0; fi < Constants.NFREQ + Constants.NEXOBS; fi++) {'
$new3 = '                    if (obs[i].code[fi] != 0) debugLog.append(" code[").append(fi).append("]=").append(obs[i].code[fi]).append(" P[").append(fi).append("]=").append(String.format("%.1f", obs[i].P[fi]));'
$new4 = '                }'
$new5 = '                debugLog.append(", skip\n");'
$lines[$LineNo - 1] = $new1
$newLines = @System.Collections.Generic.List[String]new()
$newLines.Add($new2)
$newLines.Add($new3)
$newLines.Add($new4)
$newLines.Add($new5)

$result = System.Collections.Generic.List[String]new()
foreach ($l in $lines) { $result.Add($l) }
[System.IO.File]::WriteAllLines($f, $result)
Write-Host "PATCHED"