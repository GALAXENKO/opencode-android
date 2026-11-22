param([int]$Seconds = 200)
$adb = 'F:\oc\buildenv\sdk\platform-tools\adb.exe'
& $adb shell am force-stop ai.opencode.mobile | Out-Null
Start-Sleep -Seconds 2

$pkg = 'ai.opencode.mobile'
$libdir = (& $adb shell "dumpsys package $pkg | grep primaryCpuAbi" 2>&1) -join ''
$apk = (& $adb shell "pm path $pkg" 2>&1) -join ''
Write-Output "apk: $apk"
$apkPath = ($apk -split ':')[0] -replace 'package:', ''
$native = (($apkPath -replace '/base\.apk$', '') + '/lib/arm64')
Write-Output "native: $native"

$cmd = "cd files/home && OPENCODE_PASSWORD=test OPENCODE_DISABLE_AUTOUPDATE=1 OPENCODE_DISABLE_FFF=1 ${native}/ld-musl-aarch64.so --library-path ${native}:/data/user/0/ai.opencode.mobile/files/lib ${native}/libopencode.so serve --hostname 127.0.0.1 --port 5599"
& $adb shell "run-as $pkg sh -c '$cmd > files/manual.log 2>&1 &'" | Out-Null
Start-Sleep -Seconds 2
$srvPid = (& $adb shell "run-as $pkg sh -c 'ps -A -o PID,NAME | grep ld-musl'" 2>&1) -join ''
Write-Output "proc: $srvPid"

& $adb forward tcp:5599 tcp:5599 | Out-Null
$t0 = Get-Date
while (((Get-Date) - $t0).TotalSeconds -lt $Seconds) {
    $el = [int]((Get-Date) - $t0).TotalSeconds
    $io = (& $adb shell "run-as $pkg sh -c 'cat /proc/\$(pidof ld-musl-aarch64)/io 2>/dev/null | tr \`"\n\`" \`" \`"'" 2>&1) -join ''
    $wchan = (& $adb shell "run-as $pkg sh -c 'for t in /proc/\$(pidof ld-musl-aarch64)/task/*; do echo -n \$(basename \$t)=\$(cat \$t/wchan 2>/dev/null); echo; done'" 2>&1) -join ' '
    $code = 'none'
    try {
        $r = Invoke-WebRequest -Uri 'http://127.0.0.1:5599/api/info' -TimeoutSec 3 -UseBasicParsing -Headers @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('opencode:test')) }
        $code = [string]$r.StatusCode
    } catch { $code = 'ERR ' + $_.Exception.Message.Substring(0, [Math]::Min(40, $_.Exception.Message.Length)) }
    Write-Output ("[{0,4}s] info={1} | {2} | {3}" -f $el, $code, $io, $wchan)
    if ($code -eq '200') { break }
    Start-Sleep -Seconds 4
}
Write-Output '--- manual.log ---'
& $adb shell "run-as $pkg cat files/manual.log" 2>&1 | Select-Object -First 30
