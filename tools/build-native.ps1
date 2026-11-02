# Build the native payload that is intentionally NOT stored in git.
#
# The bundled OpenCode server is the official musl-linked CLI build
# (@opencode/cli-linux-arm64-musl). It is ~235 MB, above GitHub's per-file
# limit, so it is fetched from npm and patched locally instead of committed.
#
# Result:
#   OpenCodeAndroid/app/src/main/jniLibs/arm64-v8a/libopencode.so
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools/build-native.ps1
#   powershell -ExecutionPolicy Bypass -File tools/build-native.ps1 -Version 2.2.5

param(
    [string]$Version = '2.2.5',
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [switch]$KeepWork
)

$ErrorActionPreference = 'Stop'

$work     = Join-Path $RepoRoot 'buildenv\dl\opencode'
$tarball  = Join-Path $work "cli-linux-arm64-musl-$Version.tgz"
$extract  = Join-Path $work 'package'
$target   = Join-Path $RepoRoot 'OpenCodeAndroid\app\src\main\jniLibs\arm64-v8a\libopencode.so'
$patcher  = Join-Path $PSScriptRoot 'patch-binary.js'

New-Item -ItemType Directory -Force -Path $work | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null

$url = "https://registry.npmjs.org/@opencode/cli-linux-arm64-musl/-/cli-linux-arm64-musl-$Version.tgz"
if (-not (Test-Path $tarball)) {
    Write-Host "downloading $url"
    Invoke-WebRequest -Uri $url -OutFile $tarball -UseBasicParsing
} else {
    Write-Host "cached $tarball"
}

if (Test-Path $extract) { Remove-Item -Recurse -Force $extract }
Write-Host 'extracting ...'
& tar -xzf $tarball -C $work
if ($LASTEXITCODE -ne 0) { throw 'tar failed' }

$bin = Join-Path $extract 'bin\opencode'
if (-not (Test-Path $bin)) { throw "unexpected tarball layout: $bin is missing" }
Write-Host ("source binary: {0:N0} bytes" -f (Get-Item $bin).Length)

# /etc/resolv.conf and /etc/hosts do not exist for an app on Android, so the
# embedded Bun resolver is retargeted at relative paths resolved against the
# process working directory (the app sets cwd to its private HOME and writes
# both files there on every start).
Write-Host 'patching absolute paths ...'
& node $patcher $bin $target
if ($LASTEXITCODE -ne 0) { throw 'patch-binary.js failed' }

Write-Host ("wrote {0} ({1:N0} bytes)" -f $target, (Get-Item $target).Length)

if (-not $KeepWork) {
    Remove-Item -Recurse -Force $extract
    Remove-Item -Force $tarball
    Write-Host 'cleaned intermediate files'
}

Write-Host ''
Write-Host 'done. next:'
Write-Host '  gradle :app:assembleDebug'
