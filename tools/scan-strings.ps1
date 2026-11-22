param(
    [string]$So = 'F:\oc\OpenCodeAndroid\app\src\main\jniLibs\arm64-v8a\libopencode.so',
    [string]$Pattern = 'attach',
    [int]$Max = 40
)
$bytes = [System.IO.File]::ReadAllBytes($So)
$text = [System.Text.Encoding]::UTF8.GetString($bytes)
$rx = [regex]::new($Pattern, 'IgnoreCase')
$n = 0
foreach ($m in $rx.Matches($text)) {
    if ($n -ge $Max) { break }
    $start = [Math]::Max(0, $m.Index - 120)
    $len = [Math]::Min(260, $text.Length - $start)
    $chunk = $text.Substring($start, $len)
    $sb = New-Object System.Text.StringBuilder
    foreach ($ch in $chunk.ToCharArray()) {
        $code = [int]$ch
        if (($code -ge 32 -and $code -lt 127) -or $code -gt 0x400) { [void]$sb.Append($ch) } else { [void]$sb.Append(' ') }
    }
    "$($m.Index): $($sb.ToString())"
    $n++
}
"total matches: $($rx.Matches($text).Count)"
