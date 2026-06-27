$path=D:\code\rtklib_java\src\test\java\org\rtklib\java\RinexConversionTest.java
$bytes=[System.IO.File]::ReadAllBytes($path)
$new=[System.IO.File]::ReadAllBytes($path)
Write-Host ($bytes.Length)