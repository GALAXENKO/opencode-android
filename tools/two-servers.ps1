$ErrorActionPreference = 'Continue'
$adb = 'F:\oc\buildenv\sdk\platform-tools\adb.exe'
$pkg = 'ai.opencode.mobile'

& $adb shell "am force-stop $pkg"
Start-Sleep -Seconds 2
& $adb shell "run-as $pkg sh -c 'pkill -f libopencode.so'" 2>&1 | Out-Null
Start-Sleep -Seconds 1
& $adb shell "run-as $pkg sh -c 'rm -f files/a.log files/b.log'" 2>&1 | Out-Null

Write-Output 'starting two servers in parallel'
& $adb shell "run-as $pkg sh -c 'nohup sh files/run.sh > files/a.log 2>&1 & nohup sh files/run2.sh > files/b.log 2>&1 &'"
Start-Sleep -Seconds 2
& $adb shell "run-as $pkg sh -c 'ps -A -o PID,NAME | grep musl'" 2>&1

& $adb forward tcp:5599 tcp:5599 | Out-Null
& $adb forward tcp:5600 tcp:5600 | Out-Null
$auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('opencode:test'))

$t0 = Get-Date
for ($i = 0; $i -lt 25; $i++) {
    $el = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)
    $res = @()
    foreach ($p in 5599, 5600) {
        $sw = [Diagnostics.Stopwatch]::StartNew()
        try {
            $r = Invoke-WebRequest -Uri "http://127.0.0.1:$p/api/info" -TimeoutSec 6 -UseBasicParsing -Headers @{ Authorization = $auth }
            $res += ("{0}: {1} in {2:N2}s" -f $p, $r.StatusCode, $sw.Elapsed.TotalSeconds)
        } catch {
            $res += ("{0}: ERR in {1:N2}s" -f $p, $sw.Elapsed.TotalSeconds)
        }
    }
    Write-Output ("[{0}s] {1}" -f $el, ($res -join ' | '))
    if ($res -notmatch 'ERR') { break }
    Start-Sleep -Seconds 2
}

Write-Output '--- a.log ---'
& $adb shell "run-as $pkg cat files/a.log" 2>&1
Write-Output '--- b.log ---'
& $adb shell "run-as $pkg cat files/b.log" 2>&1
Write-Output '--- processes ---'
& $adb shell "run-as $pkg sh -c 'ps -A -o PID,NAME | grep musl'" 2>&1
