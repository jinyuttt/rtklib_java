Write-Host '=== RTKLIB C NAV - Line 4 (Toe) and Line 8 (ttr) ==='
 = Get-Content 'D:\code\rtklib_java\temp_compare\ROVER.nav' -Encoding ASCII
for (=0;  -lt .Count; ++) {
    if ([] -match '^C\d{2}\s') {
         = [].Substring(0,3)
         = [+3].Trim()
         = [+7].Trim()
        Write-Host  : Toe=
        Write-Host  : ttr=
        Write-Host ''
    }
}

Write-Host '=== Java NAV - Line 4 (Toe) and Line 8 (ttr) ==='
 = Get-Content 'D:\code\rtklib_java\temp_compare\java\ROVER.nav' -Encoding ASCII
for (=0;  -lt .Count; ++) {
    if ([] -match '^C\d{2}\s') {
         = [].Substring(0,3)
         = [+3].Trim()
         = [+7].Trim()
        Write-Host  : Toe=
        Write-Host  : ttr=
        Write-Host ''
    }
}