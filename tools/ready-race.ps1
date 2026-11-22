param([int]$Runs = 5)
$ErrorActionPreference = 'Continue'
$adb = 'F:\oc\buildenv\sdk\platform-tools\adb.exe'
$pkg = 'ai.opencode.mobile'

$xml = (& $adb shell "run-as $pkg cat shared_prefs/server.xml") -join "`n"
if ($xml -match 'name="password">([^<]+)<') { $pw = $Matches[1] } else { $pw = 'MISSING' }
$auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("opencode:$pw"))

for ($run = 1; $run -le $Runs; $run++) {
    & $adb shell "am force-stop $pkg"
    Start-Sleep -Seconds 2
    & $adb shell "run-as $pkg sh -c 'rm -f files/home/logs/opencode.log'" 2>&1 | Out-Null
    $t0 = Get-Date
    & $adb shell "am start -n $pkg/.MainActivity" 2>&1 | Out-Null

    $port = $null
    for ($i = 0; $i -lt 100; $i++) {
        $log = (& $adb shell "run-as $pkg cat files/home/logs/opencode.log") -join "`n"
        if ($log -match 'listening on http://127\.0\.0\.1:(\d+)') { $port = $Matches[1]; break }
        Start-Sleep -Milliseconds 300
    }
    if (-not $port) { Write-Output "run $run : no port"; continue }
    & $adb forward tcp:$port tcp:$port | Out-Null
    $tListen = ((Get-Date) - $t0).TotalSeconds
    Write-Output ("=== run {0}: port={1} listening at {2:N1}s ===" -f $run, $port, $tListen)

    $hostReady = $null
    $appReady = $null
    for ($i = 0; $i -lt 60; $i++) {
        $el = ((Get-Date) - $t0).TotalSeconds
        if (-not $hostReady) {
            $sw = [Diagnostics.Stopwatch]::StartNew()
            try {
                $r = Invoke-WebRequest -Uri "http://127.0.0.1:$port/api/info" -TimeoutSec 4 -UseBasicParsing -Headers @{ Authorization = $auth }
                if ($r.StatusCode -eq 200) {
                    $hostReady = $el
                    Write-Output ("  host 200 at {0:N1}s (took {1:N2}s)" -f $el, $sw.Elapsed.TotalSeconds)
                }
            } catch { }
        }
        if (-not $appReady) {
            $log = (& $adb shell "run-as $pkg cat files/home/logs/opencode.log") -join "`n"
            if ($log -match 'server ready') {
                $appReady = $el
                Write-Output ("  app reports ready at {0:N1}s" -f $el)
            }
        }
        if ($hostReady -and $appReady) { break }
        if ($el -gt 130) { Write-Output "  timeout at $([math]::Round($el,1))s"; break }
        Start-Sleep -Seconds 1
    }
    Write-Output ("  => hostReady={0} appReady={1}" -f $(if ($hostReady) { [math]::Round($hostReady, 1) } else { 'never' }), $(if ($appReady) { [math]::Round($appReady, 1) } else { 'never' }))
    if ($hostReady -and $appReady -and ($appReady - $hostReady) -gt 8) {
        Write-Output '  ** app slower than host by >8s: client-side problem **'
        break
    }
    if ($hostReady -and $hostReady -gt 15) {
        Write-Output '  ** slow server start caught **'
        break
    }
}
