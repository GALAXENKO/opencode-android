$ErrorActionPreference = 'Continue'
$adb = 'F:\oc\buildenv\sdk\platform-tools\adb.exe'
$pkg = 'ai.opencode.mobile'
$B = '/data/user/0/ai.opencode.mobile/files'

# stop the manual server so only the app's process is involved
& $adb shell "run-as $pkg sh -c 'pkill -f libopencode.so; killall libopencode.so'" 2>&1 | Out-Null
Start-Sleep -Seconds 1

& $adb shell "run-as $pkg sh -c 'rm -f files/manual.log files/probe.out files/home/logs/opencode.log'" 2>&1 | Out-Null
& $adb push F:\oc\buildenv\probe.py /data/local/tmp/probe.py 2>&1 | Out-Null
& $adb shell "run-as $pkg cp /data/local/tmp/probe.py files/probe.py" 2>&1 | Out-Null

& $adb logcat -c
& $adb shell "am force-stop $pkg"
Start-Sleep -Seconds 2
$t0 = Get-Date
& $adb shell "am start -n $pkg/.MainActivity" 2>&1 | Out-Null

$port = $null
for ($i = 0; $i -lt 60; $i++) {
    $log = (& $adb shell "run-as $pkg cat files/home/logs/opencode.log") -join "`n"
    if ($log -match 'listening on http://127\.0\.0\.1:(\d+)') { $port = $Matches[1]; break }
    Start-Sleep -Milliseconds 500
}
Write-Output ("port={0} seen at {1:N1}s" -f $port, ((Get-Date) - $t0).TotalSeconds)

$xml = (& $adb shell "run-as $pkg cat shared_prefs/server.xml") -join "`n"
if ($xml -match 'name="password">([^<]+)<') { $pw = $Matches[1] } else { $pw = 'MISSING' }
Write-Output ("password={0}" -f $pw)

& $adb forward tcp:$port tcp:$port | Out-Null

Write-Output '--- A) python as app uid ---'
$sw = [Diagnostics.Stopwatch]::StartNew()
& $adb shell "run-as $pkg sh -c 'PYTHONHOME=$B/python PYTHONNOUSERSITE=1 $B/python/bin/python3.12 files/probe.py $port $pw'" 2>&1 | ForEach-Object { $_ }
Write-Output ("python probe took {0:N1}s" -f $sw.Elapsed.TotalSeconds)

Write-Output '--- B) host through adb forward ---'
$auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("opencode:$pw"))
$sw2 = [Diagnostics.Stopwatch]::StartNew()
try {
    $r = Invoke-WebRequest -Uri "http://127.0.0.1:$port/api/info" -TimeoutSec 10 -UseBasicParsing -Headers @{ Authorization = $auth }
    Write-Output ("host got {0} in {1:N1}s" -f $r.StatusCode, $sw2.Elapsed.TotalSeconds)
} catch {
    Write-Output ("host ERR in {0:N1}s : {1}" -f $sw2.Elapsed.TotalSeconds, $_.Exception.Message)
}

Write-Output '--- app log ---'
& $adb shell "run-as $pkg cat files/home/logs/opencode.log" 2>&1 | ForEach-Object { $_.Substring(0, [Math]::Min(160, $_.Length)) }
