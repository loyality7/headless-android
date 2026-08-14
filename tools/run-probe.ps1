# Runs the device probe in chunks, printing progress as it goes.
#
# Each test is its own instrumentation run: a crash in one does not cost the
# results of the others, and the slow memory test is left for last.
#
#   .\tools\run-probe.ps1              all chunks
#   .\tools\run-probe.ps1 -Fast        skip the 100-cycle memory test
#   .\tools\run-probe.ps1 -Only socket run one chunk

param(
    [switch]$Fast,
    [switch]$SkipBuild,
    [string]$Only = ""
)

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$pkg = "dev.headless.probe.test"
$runner = "$pkg/androidx.test.runner.AndroidJUnitRunner"
$apk = Join-Path $root "probe\build\outputs\apk\androidTest\debug\probe-debug-androidTest.apk"
$log = Join-Path $root "probe-results.txt"

$chunks = @(
    @{ key = "socket";    test = "DevToolsEndpointTest#socketIsReachableInProcess"; what = "socket reachable in-process";      est = "10s" },
    @{ key = "discovery"; test = "DevToolsEndpointTest#discoveryEndpointsParse";    what = "GET /json and /json/version parse"; est = "15s" },
    @{ key = "roundtrip"; test = "DevToolsEndpointTest#protocolRoundTrips";         what = "WebSocket upgrade and evaluate";    est = "20s" },
    @{ key = "matrix";    test = "DevToolsEndpointTest#capabilityMatrix";           what = "capability matrix, 14 methods";     est = "30s" },
    @{ key = "attached";  test = "LifecycleAndMemoryTest#attachedVersusDetached";   what = "attached vs detached, timers";      est = "20s" },
    @{ key = "draw";      test = "LifecycleAndMemoryTest#viewportSizedViewDrawsThePage"; what = "bitmap capture by drawing";   est = "20s" },
    @{ key = "memory";    test = "LifecycleAndMemoryTest#hundredCyclesReturnToBaseline"; what = "100 open/close cycles";       est = "3m"; slow = $true }
)

function Step($text) { Write-Host "`n>> $text" -ForegroundColor Cyan }
function Ok($text)   { Write-Host "   OK    $text" -ForegroundColor Green }
function Bad($text)  { Write-Host "   FAIL  $text" -ForegroundColor Red }
function Note($text) { Write-Host "   $text" -ForegroundColor DarkGray }

# --- device -------------------------------------------------------------

Step "device"
$devices = (adb devices) | Select-String "device$"
if (-not $devices) { Write-Host "no device attached" -ForegroundColor Red; exit 1 }
$model = (adb shell getprop ro.product.model).Trim()
$release = (adb shell getprop ro.build.version.release).Trim()
$sdk = (adb shell getprop ro.build.version.sdk).Trim()
$webview = ((adb shell dumpsys package com.google.android.webview) | Select-String "versionName" | Select-Object -First 1) -replace ".*versionName=", ""
Ok "$model, Android $release (API $sdk), WebView $($webview.Trim())"

# --- MIUI and OEM restrictions -----------------------------------------

Step "lifting OEM restrictions"
adb shell settings put secure miui_optimization 0 2>&1 | Out-Null
adb shell dumpsys deviceidle whitelist +$pkg 2>&1 | Out-Null
adb shell appops set $pkg 10021 allow 2>&1 | Out-Null
adb shell cmd appops set $pkg SYSTEM_ALERT_WINDOW allow 2>&1 | Out-Null
adb shell setprop log.tag.probe VERBOSE 2>&1 | Out-Null
Ok "background activity start, doze whitelist, verbose logging"

# A locked or sleeping screen means the host activity never becomes visible and
# the test waits for it forever. Stay awake while charging over USB, and wake now.
Step "waking the screen"
adb shell settings put global stay_on_while_plugged_in 3 2>&1 | Out-Null
adb shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
adb shell input keyevent KEYCODE_MENU 2>&1 | Out-Null
$screen = (adb shell dumpsys power) | Select-String "mWakefulness=" | Select-Object -First 1
Ok "$($screen.ToString().Trim())"
Note "if the phone has a PIN, unlock it now: a locked screen blocks the host activity"

# --- build and install --------------------------------------------------

if (-not $SkipBuild) {
    Step "building test APK"
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $build = & (Join-Path $root "gradlew.bat") ":probe:assembleDebugAndroidTest" --console=plain 2>&1
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

# Preflight: every test needs the host activity on screen. If it cannot appear,
# every chunk waits out its ceiling for the same reason, and that is worth five
# seconds to learn rather than minutes.
Step "preflight: can the host activity show?"
adb shell am force-stop $pkg 2>&1 | Out-Null
adb shell am start -n "$pkg/dev.headless.probe.HostActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 2
$top = (adb shell dumpsys activity activities) | Select-String "topResumedActivity|mResumedActivity" | Select-Object -First 1
if ($top -match "HostActivity") {
    Ok "host activity is resumed"
} else {
    Bad "host activity did not come to the foreground"
    Note "$($top -replace '\s+', ' ')"
    Note "unlock the phone, and check MIUI's 'Display pop-up windows while running in background' for the test app"
    Note "every chunk will time out until this works"
}
adb shell am force-stop $pkg 2>&1 | Out-Null

# --- run chunks ---------------------------------------------------------

$selected = if ($Only) { $chunks | Where-Object { $_.key -eq $Only } } else { $chunks }
if ($Fast) { $selected = $selected | Where-Object { -not $_.slow } }

"probe run $(Get-Date -Format 'yyyy-MM-dd HH:mm')" | Set-Content $log
"device: $model, Android $release (API $sdk), WebView $($webview.Trim())" | Add-Content $log

$passed = 0
$failed = 0
$total = @($selected).Count
$index = 0
$runClock = [Diagnostics.Stopwatch]::StartNew()

foreach ($chunk in $selected) {
    $index++
    $intoRun = "{0:mm\:ss}" -f $runClock.Elapsed
    Step "[$index/$total] $($chunk.what)   (about $($chunk.est), $intoRun into the run)"
    Note "running $($chunk.test)"

    adb logcat -c 2>&1 | Out-Null
    $sw = [Diagnostics.Stopwatch]::StartNew()

    # Redirect to a file rather than capturing a job's output. A running job
    # hands back nothing until it exits, so a chunk that has to be killed took
    # every measurement it had already produced down with it.
    $outFile = Join-Path $env:TEMP "probe-$($chunk.key).out"
    Remove-Item $outFile -ErrorAction SilentlyContinue
    $proc = Start-Process -FilePath "adb" -PassThru -NoNewWindow -RedirectStandardOutput $outFile `
        -ArgumentList @("shell", "am", "instrument", "-w", "-e", "class", "dev.headless.probe.$($chunk.test)", $runner)

    # Short ceiling. These chunks pass in seconds when they pass at all, so a
    # long wait only delays the diagnosis.
    $limit = if ($chunk.slow) { 240 } else { 35 }
    $shown = 0
    $printed = 0

    # Tail the file as it fills, so measurements appear the moment they happen.
    while (-not $proc.HasExited -and $sw.Elapsed.TotalSeconds -lt $limit) {
        if (Test-Path $outFile) {
            $lines = @(Get-Content $outFile -ErrorAction SilentlyContinue)
            if ($lines.Count -gt $shown) {
                foreach ($line in $lines[$shown..($lines.Count - 1)]) {
                    if ($line -cmatch "MEASUREMENT") {
                        Write-Host "`r   $($line.Trim())                    " -ForegroundColor Yellow
                        $line.Trim() | Add-Content $log
                        $printed++
                    }
                }
                $shown = $lines.Count
            }
        }
        Write-Host "`r   waiting $([int]$sw.Elapsed.TotalSeconds)s / ${limit}s   " -NoNewline -ForegroundColor DarkGray
        Start-Sleep -Milliseconds 400
    }
    Write-Host "`r                              `r" -NoNewline

    $timedOut = -not $proc.HasExited
    if ($timedOut) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        adb shell am force-stop $pkg 2>&1 | Out-Null
    }
    $seconds = [int]$sw.Elapsed.TotalSeconds
    $output = @(Get-Content $outFile -ErrorAction SilentlyContinue)
    if ($timedOut) { $output += "TIMEOUT after ${limit}s" }

    # Anything the tail missed, plus logcat as the backup channel. Case-sensitive:
    # matching loosely picked up Google Play services' own "measurement" logging.
    $measurements = $output | Where-Object { $_ -cmatch "MEASUREMENT" } | ForEach-Object { $_.Trim() }
    if (-not $measurements) {
        $measurements = (adb logcat -d 2>&1) |
            Where-Object { $_ -cmatch " I probe .*MEASUREMENT" } |
            ForEach-Object { "MEASUREMENT " + ($_ -split "MEASUREMENT ")[-1] }
    }

    # A chunk that finishes inside the first poll produces everything at once,
    # so print whatever the live tail did not already show.
    $remaining = @($measurements) | Select-Object -Skip $printed
    foreach ($m in $remaining) { Write-Host "   $m" -ForegroundColor Yellow; $m | Add-Content $log }

    if (-not $measurements) {
        Note "no measurements on either channel: the process died before reporting anything"
    }

    if ($output -match "^OK \(") {
        Ok "passed in ${seconds}s"
        "RESULT $($chunk.key) = pass (${seconds}s)" | Add-Content $log
        $passed++
    } else {
        Bad "failed in ${seconds}s"
        $reason = $output | Select-String "shortMsg|junit\.|Assertion|Exception|Error" | Select-Object -First 4
        foreach ($r in $reason) { Note $r.ToString().Trim(); $r.ToString().Trim() | Add-Content $log }
        "RESULT $($chunk.key) = fail (${seconds}s)" | Add-Content $log
        $failed++
    }
}

# --- summary ------------------------------------------------------------

Write-Host ""
Write-Host "=================================================="
Write-Host " probe: $passed passed, $failed failed, of $total"
Write-Host " total time: $("{0:mm\:ss}" -f $runClock.Elapsed)"
Write-Host " results written to probe-results.txt"
Write-Host "=================================================="
if ($failed -gt 0) { exit 1 }
