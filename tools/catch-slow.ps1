$ErrorActionPreference = 'Continue'
$adb = 'F:\oc\buildenv\sdk\platform-tools\adb.exe'
$pkg = 'ai.opencode.mobile'
$B = '/data/user/0/ai.opencode.mobile/files'

& $adb shell "am force-stop $pkg"
Start-Sleep -Seconds 2
& $adb shell "run-as $pkg sh -c 'pkill -f libopencode.so'" 2>&1 | Out-Null
Start-Sleep -Seconds 1

$xml = (& $adb shell "run-as $pkg cat shared_prefs/server.xml") -join "`n"
if ($xml -match 'name="password">([^<]+)<') { $pw = $Matches[1] } else { $pw = 'MISSING' }
$basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("opencode:$pw"))
Write-Output "password=$pw"

# raw HTTP request file for the app-uid probe (port is patched per run)
function Write-Req([string]$port) {
    $req = "GET /api/info HTTP/1.1`r`nHost: 127.0.0.1:$port`r`nAuthorization: Basic $basic`r`nConnection: close`r`n`r`n"
    [IO.File]::WriteAllText('F:\oc\buildenv\req.txt', $req, [Text.Encoding]::ASCII)
    & $adb push F:\oc\buildenv\req.txt /data/local/tmp/req.txt 2>&1 | Out-Null
    & $adb shell "run-as $pkg cp /data/local/tmp/req.txt files/req.txt" 2>&1 | Out-Null
}

& $adb shell "run-as $pkg sh -c 'rm -f files/home/logs/opencode.log'" 2>&1 | Out-Null
& $adb logcat -c
$t0 = Get-Date
& $adb shell "am start -n $pkg/.MainActivity" 2>&1 | Out-Null

$port = $null
for ($i = 0; $i -lt 120; $i++) {
    $log = (& $adb shell "run-as $pkg cat files/home/logs/opencode.log") -join "`n"
    if ($log -match 'listening on http://127\.0\.0\.1:(\d+)') { $port = $Matches[1]; break }
    Start-Sleep -Milliseconds 300
}
$tListen = ((Get-Date) - $t0).TotalSeconds
Write-Output ("port={0} listening seen at {1:N1}s" -f $port, $tListen)
Write-Req $port
& $adb forward tcp:$port tcp:$port | Out-Null
$auth = "Basic $basic"

for ($i = 1; $i -le 6; $i++) {
    $el = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $out = (& $adb shell "run-as $pkg sh -c 'cat files/req.txt | nc -w 8 127.0.0.1 $port | head -1'" 2>&1) -join ' '
    $appMs = [math]::Round($sw.Elapsed.TotalSeconds, 2)
    $sw2 = [Diagnostics.Stopwatch]::StartNew()
    $hostOut = 'ERR'
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:$port/api/info" -TimeoutSec 8 -UseBasicParsing -Headers @{ Authorization = $auth }
        $hostOut = "$($r.StatusCode)"
    } catch { $hostOut = 'ERR' }
    $hostMs = [math]::Round($sw2.Elapsed.TotalSeconds, 2)
    Write-Output ("[{0}s] appuid-nc: {1} ({2}s) | host: {3} ({4}s)" -f $el, $out.Trim(), $appMs, $hostOut, $hostMs)
    Start-Sleep -Seconds 5
}

Write-Output '--- app log ---'
& $adb shell "run-as $pkg cat files/home/logs/opencode.log" 2>&1 | ForEach-Object { $_.Substring(0, [Math]::Min(120, $_.Length)) }
Write-Output '--- server log tail ---'
& $adb shell "run-as $pkg sh -c 'tail -n 25 files/home/.local/share/opencode/log/opencode.log'" 2>&1 | ForEach-Object { $_.Substring(0, [Math]::Min(150, $_.Length)) }
