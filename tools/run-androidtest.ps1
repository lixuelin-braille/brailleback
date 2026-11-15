#Requires -Version 5.1
<#
.SYNOPSIS
    在设备上跑 brailleback 的 androidTest 冒烟用例。

.DESCRIPTION
    AGP 9.2 的 connectedDebugAndroidTest 依赖 com.android.tools.utp 的插件，本机离线缓存没有，
    --offline 下必然失败。这里直接用 adb install + am instrument，效果相同且不依赖 UTP。
    细节见同目录 run-androidtest.md。

.PARAMETER Serial
    目标设备序列号，默认 emulator-5554。

.PARAMETER Class
    只跑某个测试类，默认跑整个包。

.PARAMETER Adb
    adb 路径，默认 C:\tools\android-sdk\platform-tools\adb.exe。
#>
param(
    [string]$Serial = 'emulator-5554',
    [string]$Class = '',
    [string]$Adb = 'C:\tools\android-sdk\platform-tools\adb.exe'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$appApk  = Join-Path $root 'brailleback\build\outputs\apk\debug\brailleback-debug.apk'
$testApk = Join-Path $root 'brailleback\build\outputs\apk\androidTest\debug\brailleback-debug-androidTest.apk'

foreach ($p in @($Adb, $appApk, $testApk)) {
    if (-not (Test-Path -LiteralPath $p)) {
        throw "缺失：$p`n先执行 .\gradlew.bat :brailleback:assembleDebug :brailleback:assembleDebugAndroidTest --offline"
    }
}

Write-Host "[1/3] 安装被测 APK 与测试 APK 到 $Serial"
& $Adb -s $Serial install -r $appApk
if ($LASTEXITCODE -ne 0) { throw "安装被测 APK 失败（exit $LASTEXITCODE）" }
& $Adb -s $Serial install -r -t $testApk
if ($LASTEXITCODE -ne 0) { throw "安装测试 APK 失败（exit $LASTEXITCODE）" }

$runner = 'com.googlecode.eyesfree.brailleback.test/androidx.test.runner.AndroidJUnitRunner'
$arg = if ($Class) { "-e class $Class" } else { '' }

Write-Host "[2/3] 运行 $runner"
$out = & $Adb -s $Serial shell "am instrument -w -r $arg $runner" 2>&1 | Out-String

Write-Host "[3/3] 结果"
$ok = $out -match 'OK \(\d+ tests?\)'
($out -split "`n" | Where-Object { $_ -match 'INSTRUMENTATION_STATUS: test=|^Time:|^OK|^FAILURES|junit\.framework|AssertionError|没有走到' }) |
    ForEach-Object { Write-Host ('  ' + $_.Trim()) }

if ($ok) {
    Write-Host 'ALL PASS' -ForegroundColor Green
    exit 0
} else {
    Write-Host 'FAILED' -ForegroundColor Red
    exit 1
}
