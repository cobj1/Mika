param(
    [string]$SdkRoot = "",
    [ValidateRange(1, 32)][int]$Jobs = 4,
    [ValidatePattern('^[A-Z]$')][string]$DriveLetter = "N"
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
if (-not $SdkRoot) { $SdkRoot = Join-Path $projectRoot "build/.local-tools/android-sdk" }
$revision = "a4d4f2fd4ec85ec6a0a9ece4870416c1b6cc24f5"
$ndkVersion = "28.2.13676358"
$cmakeVersion = "3.22.1"
$sourceRoot = Join-Path $projectRoot "build/vendor/llama.cpp"
$destination = Join-Path $projectRoot "app/src/main/jniLibs/arm64-v8a/libjevserver.so"
$drive = "${DriveLetter}:"

function Run-Native([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed: $LASTEXITCODE" }
}

if (-not (Test-Path "$SdkRoot/ndk/$ndkVersion/build/cmake/android.toolchain.cmake")) {
    throw "Install Android SDK packages ndk;$ndkVersion and cmake;$cmakeVersion first."
}
if (-not (Test-Path $sourceRoot)) {
    New-Item -ItemType Directory -Force (Split-Path $sourceRoot) | Out-Null
    Run-Native -Program git -Arguments @("-c", "http.sslBackend=openssl", "clone", "--no-checkout", "--depth", "1", "https://github.com/ggml-org/llama.cpp.git", $sourceRoot)
}
$head = & git -C $sourceRoot rev-parse HEAD
if ($head -ne $revision) {
    Run-Native -Program git -Arguments @("-C", $sourceRoot, "-c", "http.sslBackend=openssl", "fetch", "--depth", "1", "origin", $revision)
    Run-Native -Program git -Arguments @("-C", $sourceRoot, "checkout", "--detach", $revision)
}

# 仅在固定源码的构建副本中替换入口，补 Android 父进程退出联动。
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "android-main.cpp") -Destination "$sourceRoot/tools/server/main.cpp" -Force
if (Test-Path "$drive/") { throw "$drive is already in use; pass another -DriveLetter." }
Run-Native -Program subst -Arguments @($drive, $projectRoot)
try {
    if (-not (Test-Path "$drive/")) { throw "Cannot access temporary build drive $drive." }
    $sdk = (Resolve-Path $SdkRoot).Path.Replace($projectRoot, "$drive").Replace('\', '/')
    $source = "$drive/build/vendor/llama.cpp"
    $build = "$drive/build/jev-native-arm64"
    $cmake = "$sdk/cmake/$cmakeVersion/bin/cmake.exe"
    $llvm = "$sdk/ndk/$ndkVersion/toolchains/llvm/prebuilt/windows-x86_64/bin"
    Run-Native -Program $cmake -Arguments @(
        "-S", $source, "-B", $build, "-G", "Ninja",
        "-DCMAKE_MAKE_PROGRAM=$sdk/cmake/$cmakeVersion/bin/ninja.exe",
        "-DCMAKE_TOOLCHAIN_FILE=$sdk/ndk/$ndkVersion/build/cmake/android.toolchain.cmake",
        "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-30", "-DANDROID_STL=c++_static",
        "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_SHARED_LIBS=OFF", "-DGGML_NATIVE=OFF",
        "-DGGML_OPENMP=OFF", "-DGGML_LLAMAFILE=OFF", "-DGGML_CPU_ALL_VARIANTS=OFF",
        "-DGGML_CPU_ARM_ARCH=armv8-a", "-DLLAMA_OPENSSL=OFF", "-DLLAMA_SUBPROCESS=OFF",
        "-DLLAMA_BUILD_TESTS=OFF", "-DLLAMA_BUILD_EXAMPLES=OFF", "-DLLAMA_BUILD_APP=OFF",
        "-DLLAMA_BUILD_SERVER=ON", "-DLLAMA_BUILD_TOOLS=ON", "-DLLAMA_BUILD_UI=OFF",
        "-DLLAMA_USE_PREBUILT_UI=OFF", "-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
    )
    Run-Native -Program $cmake -Arguments @("--build", $build, "--target", "llama-server", "--parallel", "$Jobs")
    New-Item -ItemType Directory -Force (Split-Path $destination) | Out-Null
    Copy-Item -LiteralPath "$build/bin/llama-server" -Destination $destination -Force
    Run-Native -Program "$llvm/llvm-strip.exe" -Arguments @("--strip-unneeded", $destination)
    Run-Native -Program "$llvm/llvm-readelf.exe" -Arguments @("-h", "-l", "-d", $destination)

    # 收集静态依赖许可证。上游只有 llama-app 生成 license.cpp，server 需显式汇总。
    $licenseText = ""
    foreach ($license in @("LICENSE", "licenses/LICENSE-jsonhpp", "vendor/cpp-httplib/LICENSE", "vendor/hash/xxhash/LICENSE", "vendor/hash/sha256/LICENSE", "vendor/hash/rotate-bits/LICENSE.md")) {
        $licenseText += "`n===== $license =====`n" + (Get-Content -Raw -LiteralPath "$source/$license")
    }
    foreach ($entry in @(
        @("vendor/stb/stb_image.h", 'This software is available under 2 licenses'),
        @("vendor/miniaudio/miniaudio.h", 'This software is available as a choice of the following licenses')
    )) {
        $header = Get-Content -Raw -LiteralPath "$source/$($entry[0])"
        $start = $header.LastIndexOf($entry[1])
        if ($start -lt 0) { throw "Missing license in $($entry[0])" }
        $licenseText += "`n===== $($entry[0]) =====`n" + $header.Substring($start)
    }
    $subprocessHeader = Get-Content -Raw -LiteralPath "$source/vendor/sheredom/subprocess.h"
    $licenseText += "`n===== subprocess.h =====`n" + $subprocessHeader.Substring(0, $subprocessHeader.IndexOf('#ifndef SHEREDOM'))
    $licenseText += "`n===== SHA-1 =====`nSHA-1 in C by Steve Reid <steve@edmweb.com>, 100% Public Domain.`n"
    $licenseText += "`n===== Android NDK toolchain (including static libc++ and compiler runtime) =====`n" + (Get-Content -Raw -LiteralPath "$sdk/ndk/$ndkVersion/NOTICE.toolchain")
    $licenseHeader = "Jev Android runtime: llama.cpp $revision`nAndroid NDK $ndkVersion; CPU arm64-v8a; API 30; static libc++; 16 KiB alignment.`n`n"
    [IO.File]::WriteAllText((Join-Path $projectRoot "licenses/JEV_RUNTIME_NOTICES.txt"), $licenseHeader + $licenseText, [Text.UTF8Encoding]::new($false))
    Copy-Item -LiteralPath (Join-Path $projectRoot "licenses/JEV_RUNTIME_NOTICES.txt") -Destination (Join-Path $projectRoot "app/src/main/assets/licenses/JevRuntime.txt") -Force
    $sha = (Get-FileHash -Algorithm SHA256 -LiteralPath $destination).Hash.ToLowerInvariant()
    $manifest = [ordered]@{
        upstream = "https://github.com/ggml-org/llama.cpp"; revision = $revision
        ndk = $ndkVersion; cmake = $cmakeVersion; abi = "arm64-v8a"; minSdk = 30
        file = "app/src/main/jniLibs/arm64-v8a/libjevserver.so"; sha256 = $sha
        bytes = (Get-Item -LiteralPath $destination).Length
    } | ConvertTo-Json
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot "runtime-manifest.json"), $manifest + "`n", [Text.UTF8Encoding]::new($false))
} finally {
    Run-Native -Program subst -Arguments @($drive, "/D")
}
