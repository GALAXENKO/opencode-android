param(
    [int]$Seconds = 200,
    [int]$Step = 3
)
$adb = 'F:\oc\buildenv\sdk\platform-tools\adb.exe'
& $adb shell am force-stop ai.opencode.mobile | Out-Null
Start-Sleep -Seconds 3
$last = (& $adb shell "run-as ai.opencode.mobile tail -n 1 files/home/logs/opencode.log" 2>&1) -join ' '
Write-Output ("before start: " + $last.Trim())
$t0 = Get-Date
& $adb shell am start -n ai.opencode.mobile/.MainActivity | Out-Null
$deadline = $t0.AddSeconds($Seconds)
while ((Get-Date) -lt $deadline) {
    $line = (& $adb shell "run-as ai.opencode.mobile tail -n 1 files/home/logs/opencode.log" 2>&1) -join ' '
    $elapsed = [int]((Get-Date) - $t0).TotalSeconds
    if ($line -ne $last) {
        Write-Output ("[{0,4}s] {1}" -f $elapsed, $line.Trim())
        $last = $line
    }
    if ($line -match 'server ready|did not start|opencode exited') { break }
    Start-Sleep -Seconds $Step
}
$appPid = (& $adb shell pidof ai.opencode.mobile).Trim()
Write-Output ("elapsed total: {0}s, app pid={1}" -f [int]((Get-Date) - $t0).TotalSeconds, $appPid)
