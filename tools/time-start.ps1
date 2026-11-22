$adb = 'F:\oc\buildenv\sdk\platform-tools\adb.exe'
$pkg = 'ai.opencode.mobile'
& $adb shell "run-as $pkg sh -c 'rm -f files/manual.log'"
$devStart = (& $adb shell "date +%s") -join ''
& $adb shell "run-as $pkg sh -c 'nohup sh files/run.sh > files/manual.log 2>&1 &'"
$auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('opencode:test'))
$t0 = Get-Date
$ok = $false
while (((Get-Date) - $t0).TotalSeconds -lt 200) {
    $el = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)
    try {
        $r = Invoke-WebRequest -Uri 'http://127.0.0.1:5599/api/info' -TimeoutSec 2 -UseBasicParsing -Headers @{ Authorization = $auth }
        $devNow = (& $adb shell "date +%s") -join ''
        Write-Output ("READY after {0}s (device start={1} now={2} delta={3}s) status={4}" -f $el, $devStart.Trim(), $devNow.Trim(), ([int]$devNow - [int]$devStart), $r.StatusCode)
        Write-Output $r.Content
        $ok = $true
        break
    } catch {
    }
    Start-Sleep -Milliseconds 1000
}
if (-not $ok) { Write-Output "NOT READY within 200s" }
Write-Output '--- manual.log ---'
& $adb shell "run-as $pkg cat files/manual.log"
Write-Output '--- server log tail ---'
& $adb shell "run-as $pkg sh -c 'tail -n 40 files/home/.local/share/opencode/log/opencode.log'" | ForEach-Object { $_.Substring(0, [Math]::Min(200, $_.Length)) }
