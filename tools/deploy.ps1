param(
    [int]$WaitMinutes = 30,
    [int]$RunSeconds = 90,
    [string]$Apk = 'F:\oc\OpenCodeAndroid\app\build\outputs\apk\debug\app-debug.apk',
    [string]$OutDir = 'F:\oc\logs'
)

$ErrorActionPreference = 'Continue'
$adb = 'F:\oc\buildenv\sdk\platform-tools\adb.exe'
$pkg = 'ai.opencode.mobile'
$log = Join-Path $OutDir 'device-run.log'

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
function Say([string]$text) {
    $line = (Get-Date -Format 'HH:mm:ss') + '  ' + $text
    Write-Host $line
    Add-Content -Path $log -Value $line -Encoding utf8
}

Say "waiting for a device (up to $WaitMinutes min) ..."
$serial = $null
$deadline = (Get-Date).AddMinutes($WaitMinutes)
while ((Get-Date) -lt $deadline) {
    $lines = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice\s*$' })
    if ($lines.Count -gt 0) {
        $serial = ($lines[0] -replace '\s.*$', '')
        break
    }
    Start-Sleep -Seconds 3
}
if (-not $serial) {
    Say 'no device appeared - aborting'
    exit 2
}

Say "device: $serial"
$props = & $adb -s $serial shell getprop ro.product.cpu.abilist
Say "abilist: $($props -join '')"
Say "model: $((& $adb -s $serial shell getprop ro.product.model) -join '')"

Say 'installing apk ...'
$install = & $adb -s $serial install -r -g $Apk 2>&1
Say ($install -join ' ')
if (($install -join ' ') -notmatch 'Success') { exit 3 }

& $adb -s $serial logcat -c | Out-Null
Say 'launching app ...'
& $adb -s $serial shell am start -W -n "$pkg/.MainActivity" | Out-Null

Say "collecting logcat for $RunSeconds s ..."
$logcatFile = Join-Path $OutDir 'logcat.txt'
$job = Start-Job -ScriptBlock {
    param($adb, $serial, $file)
    & $adb -s $serial logcat -v time | Out-File -FilePath $file -Encoding utf8
} -ArgumentList $adb, $serial, $logcatFile
Start-Sleep -Seconds $RunSeconds
Stop-Job $job
Remove-Job $job -Force

Say 'server log file:'
$remote = & $adb -s $serial shell "run-as $pkg cat files/home/logs/opencode.log" 2>&1
$serverLog = Join-Path $OutDir 'server.log'
$remote | Out-File -FilePath $serverLog -Encoding utf8
$remote | Select-Object -Last 60 | ForEach-Object { Say $_ }

Say "done: $logcatFile / $serverLog"
