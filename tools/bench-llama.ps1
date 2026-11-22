# Benchmarks the bundled llama-server with different thread counts on the device.
#
# usage: powershell -ExecutionPolicy Bypass -File tools/bench-llama.ps1 -Threads 4,6,8
param(
    [string]$Adb = 'F:\oc\buildenv\sdk\platform-tools\adb.exe',
    [string]$Model = 'Qwen3-4B-Q4_K_M.gguf',
    [int[]]$Threads = @(4, 6, 8),
    [int]$Context = 4096,
    [string]$Engine = 'bin/llama-fast',
    [int]$Port = 40020,
    [int]$Repeats = 60,
    [int]$MaxTokens = 32
)

$ErrorActionPreference = 'Stop'
$tmp = 'C:\Users\WALKIR\AppData\Local\Temp\opencode\bench.sh'

foreach ($t in $Threads) {
    Write-Host "=== threads $t"
    $script = @"
cd files/home
kill -9 `$(pgrep -f llama-fast) 2>/dev/null
sleep 3
nohup ./$Engine -m /data/user/0/ai.opencode.mobile/files/home/models/$Model \
  --host 127.0.0.1 --port $Port -c $Context -t $t -tb $t -np 1 --jinja \
  --no-warmup --no-webui -a local -fa on -ctk q8_0 -ctv q8_0 > logs/bench.log 2>&1 &
sleep 22
grep -c listening logs/bench.log
"@
    [IO.File]::WriteAllText($tmp, ($script -replace "`r`n", "`n"), (New-Object Text.UTF8Encoding($false)))
    & $Adb push $tmp /data/local/tmp/bench.sh | Out-Null
    & $Adb shell 'run-as ai.opencode.mobile sh /data/local/tmp/bench.sh'
    & $Adb forward "tcp:$Port" "tcp:$Port" | Out-Null
    node 'F:\oc\tools\bench-client.js' $Port $Repeats $MaxTokens
}
