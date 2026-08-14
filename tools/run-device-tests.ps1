# Runs :headless instrumented tests on a connected device, printing progress.
#
# Drives adb directly rather than going through connectedAndroidTest: on Windows
# Gradle holds locks on its own output files between runs, and a run that has to
# be killed leaves them behind.
#
#   .\tools\run-device-tests.ps1                       every test
#   .\tools\run-device-tests.ps1 -Class OffscreenHostTest
#   .\tools\run-device-tests.ps1 -SkipBuild

param(
    [string]$Class = "",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$pkg = "dev.headless.browser.test"
$runner = "$pkg/androidx.test.runner.AndroidJUnitRunner"
$apk = Join-Path $root "headless\build\outputs\apk\androidTest\debug\headless-debug-androidTest.apk"

function Step($text) { Write-Host "`n>> $text" -ForegroundColor Cyan }
function Ok($text)   { Write-Host "   OK    $text" -ForegroundColor Green }
function Bad($text)  { Write-Host "   FAIL  $text" -ForegroundColor Red }
function Note($text) { Write-Host "   $text" -ForegroundColor DarkGray }

Step "device"
if (-not ((adb devices) | Select-String "device$")) { Bad "no device attached"; exit 1 }
$model = (adb shell getprop ro.product.model).Trim()
$release = (adb shell getprop ro.build.version.release).Trim()
$webview = ((adb shell dumpsys package com.google.android.webview) | Select-String "versionName" | Select-Object -First 1) -replace ".*versionName=", ""
Ok "$model, Android $release, WebView $($webview.Trim())"

Step "lifting OEM restrictions"
adb shell settings put secure miui_optimization 0 2>&1 | Out-Null
adb shell dumpsys deviceidle whitelist +$pkg 2>&1 | Out-Null
adb shell appops set $pkg 10021 allow 2>&1 | Out-Null
adb shell cmd appops set $pkg SYSTEM_ALERT_WINDOW allow 2>&1 | Out-Null
adb shell settings put global stay_on_while_plugged_in 3 2>&1 | Out-Null
adb shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
$overlay = (adb shell appops get $pkg SYSTEM_ALERT_WINDOW) -join " "
Ok "overlay permission: $($overlay.Trim())"
Note "an overlay lets the host attach a window; without it the library runs detached"

if (-not $SkipBuild) {
    Step "building"
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $build = & (Join-Path $root "gradlew.bat") ":headless:assembleDebugAndroidTest" --console=plain 2>&1
    if ($LASTEXITCODE -ne 0) {
        Bad "build failed"
        $build | Select-String "^e: " | Select-Object -First 10 | ForEach-Object { Note $_ }
        exit 1
    }
    Ok "built in $([int]$sw.Elapsed.TotalSeconds)s"
}

Step "installing"
$install = adb install -r -t $apk 2>&1
if ($install -match "Success") { Ok "installed" } else { Bad "$install"; exit 1 }

Step "running tests"
$target = if ($Class) { "dev.headless.browser.platform.$Class" } else { "" }
$outFile = Join-Path $env:TEMP "headless-tests.out"
Remove-Item $outFile -ErrorAction SilentlyContinue

$arguments = @("shell", "am", "instrument", "-w")
if ($target) { $arguments += @("-e", "class", $target) }
$arguments += $runner

$sw = [Diagnostics.Stopwatch]::StartNew()
$proc = Start-Process adb -PassThru -NoNewWindow -RedirectStandardOutput $outFile -ArgumentList $arguments
while (-not $proc.HasExited -and $sw.Elapsed.TotalSeconds -lt 300) {
    Write-Host "`r   running $([int]$sw.Elapsed.TotalSeconds)s   " -NoNewline -ForegroundColor DarkGray
    Start-Sleep -Milliseconds 400
}
Write-Host "`r                        `r" -NoNewline
if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force; Bad "timed out after 300s"; exit 1 }

$output = Get-Content $outFile -ErrorAction SilentlyContinue
$seconds = [int]$sw.Elapsed.TotalSeconds

# The runner prints a dot per passing test and a block per failure. Neither is
# worth reading in full, so report the counts and every failure in detail.
$summary = $output | Where-Object { $_ -match "^(OK|FAILURES|Tests run)" }
$failures = $output | Where-Object { $_ -match "^\d+\) |junit\.|Assertion|Exception|shortMsg" }

if ($output -match "^OK \(") {
    Ok "$($summary -join ' ') in ${seconds}s"
} else {
    Bad "failed in ${seconds}s"
    foreach ($line in $failures | Select-Object -First 25) { Note $line.Trim() }
    exit 1
}
