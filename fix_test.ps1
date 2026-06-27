$f="D:\code\rtklib_java\src\test\java\org\rtklib\java\RtkTest.java"
$c=[System.IO.File]::ReadAllText($f,[System.Text.Encoding]::UTF8)
$old="System.arraycopy(basePos, 0, rtk.rb, 0, 3)"
$new="System.arraycopy(basePos, 0, rtk.opt.rb, 0, 3)"
$c2=$c.Replace($old,$new)
if($c2 -eq $c){Write-Host "NOT FOUND"}else{[System.IO.File]::WriteAllText($f,$c2,[System.Text.UTF8Encoding]::new($false));Write-Host "DONE"}
