# Collects the Phase 0 performance baseline from a connected device.
# Usage: powershell -File tools/baseline.ps1 [-Package app.slimboard.debug]
param(
    [string]$Package = "app.slimboard.debug"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

Write-Output "=== Device ==="
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release

Write-Output ""
Write-Output "=== APK size ==="
foreach ($apk in @("app\build\outputs\apk\debug\app-debug.apk", "app\build\outputs\apk\release\app-release.apk")) {
    $path = Join-Path $root $apk
    if (Test-Path $path) {
        $mb = [math]::Round((Get-Item $path).Length / 1MB, 2)
        Write-Output "$apk : $mb MB"
    }
}

Write-Output ""
Write-Output "=== Show -> first draw (from logcat, most recent first) ==="
adb logcat -d -s SlimBoard:D | Select-String "first draw|onCreate|onCreateInputView" | Select-Object -Last 15

Write-Output ""
Write-Output "=== Memory (keyboard must be visible right now) ==="
$mem = adb shell dumpsys meminfo $Package
$mem | Select-String "TOTAL PSS|TOTAL RSS|TOTAL:" | ForEach-Object { $_.Line.Trim() }

Write-Output ""
Write-Output "=== Process ==="
adb shell pidof $Package
