$c = [IO.File]::ReadAllText('C:\Users\admin\Desktop\rtklib_java_results\2_spp_result.pos')
Write-Host $c.Substring(0, [Math]::Min(800, $c.Length))