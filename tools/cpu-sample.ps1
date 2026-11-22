param([int]$Seconds = 120)
$adb = 'F:\oc\buildenv\sdk\platform-tools\adb.exe'
& $adb shell am force-stop ai.opencode.mobile | Out-Null
Start-Sleep -Seconds 3
& $adb shell am start -n ai.opencode.mobile/.MainActivity | Out-Null
$t0 = Get-Date
$srvPid = ''
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Milliseconds 700
    $line = (& $adb shell "ps -A -o PID,NAME" 2>&1 | Select-String -Pattern 'ld-musl-aarch64') -join ''
    if ($line -match '(\d+)\s+ld-musl') { $srvPid = $matches[1]; break }
}
Write-Output "server pid=$srvPid"
if (-not $srvPid) { exit 1 }
$prevCpu = 0
$deadline = (Get-Date).AddSeconds($Seconds)
while ((Get-Date) -lt $deadline) {
    $stat = (& $adb shell "cat /proc/$srvPid/stat" 2>&1) -join ''
    $el = [int]((Get-Date) - $t0).TotalSeconds
    if ($stat -match '\)\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+(\d+)\s+(\d+)') {
        $ut = [int]$matches[1]; $st = [int]$matches[2]
        $cpu = $ut + $st
        $delta = $cpu - $prevCpu
        $prevCpu = $cpu
        $appLine = (& $adb shell "run-as ai.opencode.mobile tail -n 1 files/home/logs/opencode.log" 2>&1) -join ' '
        $appLine = $appLine.Trim()
        if ($appLine.Length -gt 90) { $appLine = $appLine.Substring($appLine.Length - 90) }
        Write-Output ("[{0,4}s] cpuTicks+{1,-4} total={2,-6} | {3}" -f $el, $delta, $cpu, $appLine)
        if ($appLine -match 'server ready|did not start') { break }
    } else {
        Write-Output ("[{0,4}s] stat unreadable: {1}" -f $el, $stat)
    }
    Start-Sleep -Seconds 3
}
