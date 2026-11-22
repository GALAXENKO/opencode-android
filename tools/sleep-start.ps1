$ErrorActionPreference = 'Continue'
$adb = 'F:\oc\buildenv\sdk\platform-tools\adb.exe'
$pkg = 'ai.opencode.mobile'

$xml = (& $adb shell "run-as $pkg cat shared_prefs/server.xml") -join "`n"
if ($xml -match 'name="password">([^<]+)<') { $pw = $Matches[1] } else { $pw = 'MISSING' }
$auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("opencode:$pw"))

& $adb shell "am force-stop $pkg"
Start-Sleep -Seconds 2
Write-Output 'screen off'
& $adb shell "input keyevent 223" | Out-Null
Start-Sleep -Seconds 60
& $adb shell "dumpsys power" 2>&1 | Select-String -Pattern 'mWakefulness=' | Select-Object -First 1

& $adb shell "run-as $pkg sh -c 'rm -f files/home/logs/opencode.log'" 2>&1 | Out-Null
$t0 = Get-Date
& $adb shell "am start -n $pkg/.MainActivity" 2>&1 | Out-Null

$port = $null
for ($i = 0; $i -lt 200; $i++) {
    $log = (& $adb shell "run-as $pkg cat files/home/logs/opencode.log") -join "`n"
    if ($log -match 'listening on http://127\.0\.0\.1:(\d+)') { $port = $Matches[1]; break }
    Start-Sleep -Milliseconds 500
}
if (-not $port) { Write-Output 'no port after 100s'; & $adb shell "input keyevent 224" | Out-Null; exit 1 }
& $adb forward tcp:$port tcp:$port | Out-Null
Write-Output ("port={0} listening at {1:N1}s" -f $port, ((Get-Date) - $t0).TotalSeconds)

for ($i = 0; $i -lt 90; $i++) {
    $el = ((Get-Date) - $t0).TotalSeconds
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $res = 'ERR'
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:$port/api/info" -TimeoutSec 5 -UseBasicParsing -Headers @{ Authorization = $auth }
        $res = "$($r.StatusCode)"
    } catch { }
    Write-Output ("[{0:N1}s] host -> {1} ({2:N2}s)" -f $el, $res, $sw.Elapsed.TotalSeconds)
    if ($res -eq '200') { break }
    Start-Sleep -Seconds 2
}
Write-Output '--- app log ---'
& $adb shell "run-as $pkg cat files/home/logs/opencode.log" 2>&1 | Select-Object -Last 6 | ForEach-Object { $_.Substring(0, [Math]::Min(110, $_.Length)) }
Write-Output 'screen on'
& $adb shell "input keyevent 224" | Out-Null
