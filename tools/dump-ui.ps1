param(
    [string]$So = 'F:\oc\OpenCodeAndroid\app\src\main\jniLibs\arm64-v8a\libopencode.so',
    [string]$Needle = 'showOpenFilePicker',
    [int]$Before = 2000,
    [int]$After = 4000,
    [string]$Out = 'F:\oc\buildenv\dump.txt'
)
$bytes = [System.IO.File]::ReadAllBytes($So)
$text = [System.Text.Encoding]::UTF8.GetString($bytes)
$i = $text.IndexOf($Needle)
if ($i -lt 0) { "not found: $Needle"; exit 1 }
$start = [Math]::Max(0, $i - $Before)
$len = [Math]::Min($Before + $After, $text.Length - $start)
$chunk = $text.Substring($start, $len)
$sb = New-Object System.Text.StringBuilder
foreach ($ch in $chunk.ToCharArray()) {
    $code = [int]$ch
    if (($code -ge 32 -and $code -lt 127) -or $code -gt 0x400) { [void]$sb.Append($ch) } else { [void]$sb.Append(' ') }
}
[System.IO.File]::WriteAllText($Out, $sb.ToString(), [System.Text.Encoding]::UTF8)
"found at $i -> $Out ($len chars)"
