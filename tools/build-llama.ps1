# Rebuilds the bundled llama-server for Android arm64.
#
# The stock build used GGML_NATIVE=OFF with no -march, so the binary only had
# the portable C kernels: prompt processing ran at ~8 tok/s on a 4B Q4_K model.
# Setting GGML_CPU_ARM_ARCH makes the compiler emit the ARMv8.2 dot-product
# (and FP16) intrinsics that ggml's quantized kernels and the weight-repack
# path need, which is what makes local inference usable on a phone.
#
# Result:
#   OpenCodeAndroid/app/src/main/jniLibs/arm64-v8a/libllama-server.so
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools/build-llama.ps1
#   powershell -ExecutionPolicy Bypass -File tools/build-llama.ps1 -Arch "armv8.2-a+dotprod+i8mm+fp16"

param(
    [string]$Arch = 'armv8.2-a+dotprod+fp16',
    [string]$Source = 'F:\oc\buildenv\dl\llama.cpp',
    [string]$BuildDir = 'F:\oc\buildenv\dl\llama.cpp\build-android-arm64-dp',
    [string]$Ndk = 'F:\oc\buildenv\dl\ndk\android-ndk-r27d',
    [string]$Cmake = 'F:\oc\buildenv\dl\tools\cmake-3.30.5-windows-x86_64\bin\cmake.exe',
    [string]$Ninja = 'F:\oc\buildenv\dl\tools\ninja.exe',
    [string]$Target = 'F:\oc\OpenCodeAndroid\app\src\main\jniLibs\arm64-v8a\libllama-server.so'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $Cmake)) { throw "cmake not found: $Cmake" }
if (-not (Test-Path $Ninja)) { throw "ninja not found: $Ninja" }
if (-not (Test-Path $Ndk))   { throw "ndk not found: $Ndk" }

Write-Host "configuring ($Arch) ..."
$configure = @(
    '-B', $BuildDir, '-S', $Source, '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$Ninja",
    "-DCMAKE_TOOLCHAIN_FILE=$Ndk\build\cmake\android.toolchain.cmake",
    '-DANDROID_ABI=arm64-v8a',
    '-DANDROID_PLATFORM=android-26',
    '-DCMAKE_BUILD_TYPE=Release',
    '-DGGML_NATIVE=OFF',
    '-DGGML_OPENMP=OFF',
    '-DGGML_LLAMAFILE=OFF',
    '-DGGML_CCACHE=OFF',
    '-DBUILD_SHARED_LIBS=OFF',
    '-DLLAMA_CURL=OFF',
    '-DLLAMA_BUILD_TESTS=OFF',
    '-DLLAMA_BUILD_EXAMPLES=OFF',
    '-DLLAMA_BUILD_TOOLS=ON',
    '-DLLAMA_BUILD_SERVER=ON',
    '-DLLAMA_BUILD_UI=OFF',
    '-DLLAMA_USE_PREBUILT_UI=OFF'
)
# An empty -Arch produces the portable baseline binary (no NEON quantised kernels).
if ($Arch -ne '') { $configure += "-DGGML_CPU_ARM_ARCH=$Arch" }

& $Cmake @configure
if ($LASTEXITCODE -ne 0) { throw 'cmake configure failed' }

Write-Host 'building llama-server ...'
& $Cmake --build $BuildDir --target llama-server -j 12
if ($LASTEXITCODE -ne 0) { throw 'build failed' }

$bin = Join-Path $BuildDir 'bin\llama-server'
if (-not (Test-Path $bin)) { throw "unexpected output: $bin is missing" }
$strip = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe'
if (Test-Path $strip) { & $strip --strip-all $bin }

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Target) | Out-Null
Copy-Item $bin $Target -Force
Write-Host ("wrote {0} ({1:N0} bytes)" -f $Target, (Get-Item $Target).Length)
