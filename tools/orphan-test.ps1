$ErrorActionPreference = 'Continue'
$adb = 'F:\oc\buildenv\sdk\platform-tools\adb.exe'
$pkg = 'ai.opencode.mobile'

& $adb shell "am force-stop $pkg"
Start-Sleep -Seconds 2
& $adb shell "run-as $pkg sh -c 'pkill -f libopencode.so'" 2>&1 | Out-Null
Start-Sleep -Seconds 2

function Get-ServerPid {
    $out = (& $adb shell "run-as $pkg sh -c 'ps -A -o PID,NAME | grep musl'") 2>&1
    foreach ($l in $out) { if ($l -match '^\s*(\d+)\s') { return $Matches[1] } }
    return $null
}

Write-Output '--- start first server (5601) ---'
& $adb shell "run-as $pkg sh -c 'rm -f files/c.log files/b.log; nohup sh files/run3.sh > files/c.log 2>&1 &'"
Start-Sleep -Seconds 3
$pid1 = Get-ServerPid
Write-Output "pid1=$pid1"
& $adb forward tcp:5601 tcp:5601 | Out-Null
$auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('opencode:test'))
try {
    $r = Invoke-WebRequest -Uri 'http://127.0.0.1:5601/api/info' -TimeoutSec 8 -UseBasicParsing -Headers @{ Authorization = $auth }
    Write-Output "first server: $($r.StatusCode)"
} catch { Write-Output "first server ERR $($_.Exception.Message)" }

Write-Output '--- SIGTERM the first server (what p.destroy() does) ---'
& $adb shell "run-as $pkg sh -c 'kill -TERM $pid1'" 2>&1
Start-Sleep -Seconds 4
$alive = (& $adb shell "run-as $pkg sh -c 'ps -A -o PID,NAME | grep musl'") 2>&1
Write-Output ("after SIGTERM +4s: " + (($alive -join ' ').Trim()))

Write-Output '--- start second server (5600) while the first may linger ---'
& $adb shell "run-as $pkg sh -c 'nohup sh files/run2.sh > files/b.log 2>&1 &'"
Start-Sleep -Seconds 3
& $adb forward tcp:5600 tcp:5600 | Out-Null
$t0 = Get-Date
for ($i = 0; $i -lt 12; $i++) {
    $el = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $res = 'ERR'
    try {
        $r = Invoke-WebRequest -Uri 'http://127.0.0.1:5600/api/info' -TimeoutSec 6 -UseBasicParsing -Headers @{ Authorization = $auth }
        $res = "$($r.StatusCode)"
    } catch { }
    Write-Output ("[{0}s] 5600 -> {1} in {2:N2}s" -f $el, $res, $sw.Elapsed.TotalSeconds)
    if ($res -eq '200') { break }
    Start-Sleep -Seconds 4
}

Write-Output '--- b.log ---'
& $adb shell "run-as $pkg cat files/b.log" 2>&1
Write-Output '--- processes ---'
& $adb shell "run-as $pkg sh -c 'ps -A -o PID,NAME | grep musl'" 2>&1
& $adb shell "run-as $pkg sh -c 'pkill -f libopencode.so'" 2>&1 | Out-Null
