# UI automation helpers for the OpenCode mobile debug builds.
# usage: Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force; . F:\oc\tools\ui.ps1
$ErrorActionPreference = 'Stop'
$script:Adb = 'F:\oc\buildenv\sdk\platform-tools\adb.exe'
$script:UiDump = 'C:\Users\WALKIR\AppData\Local\Temp\opencode\ui-dump.xml'

function Get-UiNodes {
    & $script:Adb shell uiautomator dump /sdcard/ui-dump.xml | Out-Null
    & $script:Adb pull /sdcard/ui-dump.xml $script:UiDump | Out-Null
    $bytes = [IO.File]::ReadAllBytes($script:UiDump)
    $xml = [Text.Encoding]::UTF8.GetString($bytes)
    $list = @()
    foreach ($m in [regex]::Matches($xml, '<node\s[^>]*>')) {
        $n = $m.Value
        $bd = [regex]::Match($n, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $bd.Success) { continue }
        $x1 = [int]$bd.Groups[1].Value; $y1 = [int]$bd.Groups[2].Value
        $x2 = [int]$bd.Groups[3].Value; $y2 = [int]$bd.Groups[4].Value
        $class = ''
        $cm = [regex]::Match($n, 'class="([^"]*)"')
        if ($cm.Success) { $class = $cm.Groups[1].Value }
        $rid = ''
        $rm = [regex]::Match($n, 'resource-id="([^"]*)"')
        if ($rm.Success) { $rid = $rm.Groups[1].Value }
        $text = ''
        $tm = [regex]::Match($n, '\stext="([^"]*)"')
        if ($tm.Success) { $text = [System.Net.WebUtility]::HtmlDecode($tm.Groups[1].Value) }
        $desc = ''
        $dm = [regex]::Match($n, 'content-desc="([^"]*)"')
        if ($dm.Success) { $desc = [System.Net.WebUtility]::HtmlDecode($dm.Groups[1].Value) }
        $hint = ''
        $hm = [regex]::Match($n, 'hint="([^"]*)"')
        if ($hm.Success) { $hint = [System.Net.WebUtility]::HtmlDecode($hm.Groups[1].Value) }
        $list += [pscustomobject]@{
            Class   = $class
            Rid     = $rid
            Text    = $text
            Desc    = $desc
            Hint    = $hint
            Focused = $n -match 'focused="true"'
            Click   = $n -match 'clickable="true"'
            X1      = $x1; Y1 = $y1; X2 = $x2; Y2 = $y2
            Cx      = [int](($x1 + $x2) / 2); Cy = [int](($y1 + $y2) / 2)
        }
    }
    return $list
}

function Find-Node {
    param(
        [object[]] $Nodes,
        [string] $Rid,
        [string] $Text,
        [string] $Class
    )
    $hits = $Nodes
    if ($Rid) { $hits = $hits | Where-Object { $_.Rid -like "*$Rid" } }
    if ($Text) { $hits = $hits | Where-Object { $_.Text -eq $Text -or $_.Hint -eq $Text -or $_.Desc -eq $Text } }
    if ($Class) { $hits = $hits | Where-Object { $_.Class -like "*$Class" } }
    return @($hits)
}

function Tap-Node {
    param([object] $Node)
    & $script:Adb shell input tap $Node.Cx $Node.Cy | Out-Null
    Start-Sleep -Milliseconds 600
}

function Type-Text {
    param([string] $Value)
    $escaped = $Value -replace ' ', '%s'
    & $script:Adb shell input text $escaped | Out-Null
    Start-Sleep -Milliseconds 300
}

function Press-Key {
    param([string] $Key)
    & $script:Adb shell input keyevent $Key | Out-Null
    Start-Sleep -Milliseconds 300
}

# Fills the Add-provider dialog: each field is located by its placeholder right before
# tapping, because the soft keyboard resizes the dialog and moves every field.
function Set-FieldByHint {
    param([string] $Hint, [string] $Value)
    $nodes = Get-UiNodes
    $hit = Find-Node $nodes -Text $Hint
    if ($hit.Count -eq 0) { throw "field not found: $Hint" }
    Tap-Node $hit[0]
    Type-Text $Value
}
