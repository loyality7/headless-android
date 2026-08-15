$b64Str = adb shell "run-as dev.headless.browser.test base64 cache/example_screenshot.png"
$cleanB64 = ($b64Str -join '').Trim()
$bytes = [System.Convert]::FromBase64String($cleanB64)
[System.IO.File]::WriteAllBytes("d:\Projects\headless-android\example_screenshot.png", $bytes)
Write-Host "Successfully wrote $($bytes.Length) bytes to example_screenshot.png"
