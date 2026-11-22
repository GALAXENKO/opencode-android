param(
    [Parameter(Mandatory = $true)][string]$Path,
    [string]$Pkg = 'ai.opencode.mobile',
    [switch]$Bg
)
$ErrorActionPreference = 'Continue'
$adb = 'F:\oc\buildenv\sdk\platform-tools\adb.exe'
$name = Split-Path $Path -Leaf
& $adb push $Path "/data/local/tmp/$name" | Out-Null
& $adb shell "run-as $Pkg cp /data/local/tmp/$name files/dev.sh" | Out-Null
if ($Bg) {
    & $adb shell "run-as $Pkg sh -c 'nohup sh files/dev.sh > files/dev.log 2>&1 &'"
} else {
    & $adb shell "run-as $Pkg sh files/dev.sh" 2>&1
}
