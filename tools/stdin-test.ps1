$ErrorActionPreference = 'Continue'
$adb = 'F:\oc\buildenv\sdk\platform-tools\adb.exe'
$pkg = 'ai.opencode.mobile'

& $adb shell "am force-stop $pkg"
Start-Sleep -Seconds 2
& $adb shell "run-as $pkg sh -c 'pkill -f libopencode.so'" 2>&1 | Out-Null
Start-Sleep -Seconds 1
& $adb shell "run-as $pkg sh -c 'rm -f files/c.log'" 2>&1 | Out-Null

# server with stdin held open by a pipe (same shape as ProcessBuilder's default)
$inner = (& $adb shell "run-as $pkg cat files/run.sh") -join "`n"
$inner = $inner -replace '5599', '5601'
[IO.File]::WriteAllText('F:\oc\buildenv\run-server3.sh', $inner, [Text.Encoding]::ASCII)
& $adb push F:\oc\buildenv\run-server3.sh /data/local/tmp/r3.sh 2>&1 | Out-Null
& $adb shell "run-as $pkg cp /data/local/tmp/r3.sh files/run3.sh" 2>&1 | Out-Null
& $adb shell "run-as $pkg sh -c 'chmod 755 files/run3.sh; nohup sh -c ""sleep 600 | sh files/run3.sh"" > files/c.log 2>&1 &'"
Start-Sleep -Seconds 3
& $adb shell "run-as $pkg sh -c 'ps -A -o PID,NAME | grep musl'" 2>&1

& $adb forward tcp:5601 tcp:5601 | Out-Null
$auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('opencode:test'))
$t0 = Get-Date
for ($i = 0; $i -lt 30; $i++) {
    $el = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-WebRequest -Uri 'http://127.0.0.1:5601/api/info' -TimeoutSec 8 -UseBasicParsing -Headers @{ Authorization = $auth }
        Write-Output ("[{0}s] {1} in {2:N2}s" -f $el, $r.StatusCode, $sw.Elapsed.TotalSeconds)
        break
    } catch {
        Write-Output ("[{0}s] ERR in {1:N2}s" -f $el, $sw.Elapsed.TotalSeconds)
    }
    Start-Sleep -Seconds 3
}
Write-Output '--- c.log ---'
& $adb shell "run-as $pkg cat files/c.log" 2>&1
& $adb shell "run-as $pkg sh -c 'pkill -f libopencode.so'" 2>&1 | Out-Null
