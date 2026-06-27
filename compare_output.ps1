# Compare Java vs RTKLIB
$refDir = "C:\Users\admin\Desktop\rtklib_java_results\rtklib_ref"
$javaDir = "C:\Users\admin\Desktop\rtklib_java_results"
Write-Output "Loading RTKLIB reference..."
$refObs = Get-Content "$refDir\rover.obs"
$javaFiles = Get-ChildItem $javaDir -Filter "*_rover_obs.txt" | Sort-Object LastWriteTime -Descending
$javaObs = Get-Content $javaFiles[0].FullName
Write-Output "RTKLIB lines: $($refObs.Count)"
Write-Output "Java lines: $($javaObs.Count)"
Write-Output "Done."