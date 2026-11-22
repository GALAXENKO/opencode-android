$ErrorActionPreference = 'Continue'
$adb = 'F:\oc\buildenv\sdk\platform-tools\adb.exe'
$pkg = 'ai.opencode.mobile'

& $adb shell "am force-stop $pkg"
Start-Sleep -Seconds 2
& $adb shell "run-as $pkg sh -c 'rm -f files/home/logs/opencode.log'" | Out-Null
& $adb shell "am start -n $pkg/.MainActivity" | Out-Null

$port = $null
for ($i = 0; $i -lt 120; $i++) {
    $log = (& $adb shell "run-as $pkg cat files/home/logs/opencode.log") -join "`n"
    if ($log -match 'listening on http://127\.0\.0\.1:(\d+)') { $port = $Matches[1]; break }
    Start-Sleep -Milliseconds 400
}
if (-not $port) { Write-Output 'no port'; exit 1 }
Write-Output "port=$port"
& $adb forward "tcp:$port" "tcp:$port" | Out-Null

$xml = (& $adb shell "run-as $pkg cat shared_prefs/server.xml") -join "`n"
if ($xml -match 'name="password">([^<]+)<') { $pw = $Matches[1] }
$auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("opencode:$pw"))

Start-Sleep -Seconds 3
foreach ($endpoint in @('/api/providers', '/api/models')) {
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:$port$endpoint" -TimeoutSec 60 -UseBasicParsing -Headers @{ Authorization = $auth }
        Write-Output "=== $endpoint -> $($r.StatusCode) len=$($r.Content.Length)"
        $r.Content | Out-File -Encoding utf8 "F:\oc\buildenv\resp$($endpoint.Replace('/','_')).json"
    } catch {
        Write-Output "=== $endpoint ERR $($_.Exception.Message)"
    }
}
Write-Output '--- app log tail ---'
& $adb shell "run-as $pkg cat files/home/logs/opencode.log" | Select-Object -Last 6
