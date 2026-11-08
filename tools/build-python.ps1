# Builds app/src/main/assets/python.zip - the embedded CPython runtime.
#
# The runtime is a python-build-standalone "install_only" build for
# aarch64-unknown-linux-musl, i.e. the same libc the embedded opencode CLI uses.
# Only the pieces a working interpreter needs are shipped:
#
#   bin/python3.12        (self-contained, links only against musl libc)
#   lib/python3.12/**     (stdlib + pip)
#   ssl/cacert.pem        (CA bundle for https:// - openssl is linked statically)
#
# include/, share/, tcl/tk and libpython3.12.so are left out (~50 MB saved).

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$root    = 'F:\oc'
$dl      = Join-Path $root 'buildenv\dl\python'
$tgz     = Join-Path $dl 'cpython-3.12.16-musl-aarch64.tar.gz'
$stage   = Join-Path $dl 'stage'
$assets  = Join-Path $root 'OpenCodeAndroid\app\src\main\assets'
$zipOut  = Join-Path $assets 'python.zip'

$version = '3.12.16'
$url = "https://github.com/astral-sh/python-build-standalone/releases/download/20261026/cpython-$version%2B20261026-aarch64-unknown-linux-musl-install_only.tar.gz"

New-Item -ItemType Directory -Force -Path $dl, $assets | Out-Null

if (!(Test-Path $tgz)) {
    Write-Host "downloading CPython $version (aarch64-musl) ..."
    Invoke-WebRequest -Uri $url -OutFile $tgz
}
Write-Host ("tarball: {0:N0} bytes" -f (Get-Item $tgz).Length)

if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force -Path $stage | Out-Null

Write-Host 'extracting bin/python3.12 and lib/python3.12 ...'
& tar -xzf $tgz -C $stage 'python/bin/python3.12' 'python/lib/python3.12'
if ($LASTEXITCODE -ne 0) { throw "tar failed with $LASTEXITCODE" }

# Re-pack into the layout the app expects: <root>/bin/python3.12 + <root>/lib/python3.12
$layout = Join-Path $dl 'layout'
if (Test-Path $layout) { Remove-Item -Recurse -Force $layout }
New-Item -ItemType Directory -Force -Path (Join-Path $layout 'bin'), (Join-Path $layout 'lib'), (Join-Path $layout 'ssl') | Out-Null
Move-Item (Join-Path $stage 'python\bin\python3.12') (Join-Path $layout 'bin\python3.12')
Move-Item (Join-Path $stage 'python\lib\python3.12') (Join-Path $layout 'lib\python3.12')

# CA bundle: the runtime links OpenSSL statically and has no certificate store on Android.
$cacert = Join-Path $dl 'cacert.pem'
if (!(Test-Path $cacert)) {
    Write-Host 'downloading cacert.pem ...'
    Invoke-WebRequest -Uri 'https://curl.se/ca/cacert.pem' -OutFile $cacert
}
Copy-Item $cacert (Join-Path $layout 'ssl\cacert.pem')

# Drop caches/bytecode that would only bloat the asset.
Get-ChildItem (Join-Path $layout 'lib') -Recurse -Directory -Filter '__pycache__' -ErrorAction SilentlyContinue |
    Remove-Item -Recurse -Force
Get-ChildItem (Join-Path $layout 'lib\python3.12') -Recurse -File -Include '*.pyc' -ErrorAction SilentlyContinue |
    Remove-Item -Force

$size = (Get-ChildItem $layout -Recurse -File | Measure-Object -Sum Length)
Write-Host ("staged: {0} files, {1:N0} bytes" -f $size.Count, $size.Sum)

if (Test-Path $zipOut) { Remove-Item -Force $zipOut }
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
# ZipFile::CreateFromDirectory writes entry names with '\' on .NET Framework, which the
# Android extractor would treat as a literal character in a file name. Add entries with
# explicit '/' separators so the archive is a portable prefix tree.
$base = (Resolve-Path $layout).Path
$zip = [System.IO.Compression.ZipFile]::Open($zipOut, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    Get-ChildItem $layout -Recurse -File | ForEach-Object {
        $rel = $_.FullName.Substring($base.Length + 1).Replace('\', '/')
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $_.FullName, $rel, [System.IO.Compression.CompressionLevel]::Optimal)
    }
} finally {
    $zip.Dispose()
}

$zipSize = (Get-Item $zipOut).Length
Write-Host ("asset: {0} ({1:N0} bytes)" -f $zipOut, $zipSize)
