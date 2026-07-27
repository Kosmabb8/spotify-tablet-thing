param([string]$Serial = '')
. "$PSScriptRoot\common.ps1"
Initialize-Adb -Serial $Serial
Confirm-TBX505F
if (-not (Test-PackageInstalled)) { throw 'Install the controller APK first.' }

Invoke-Adb shell pm enable $script:PackageName | Out-Null
& $script:AdbPath @script:AdbPrefix shell cmd appops set $script:PackageName RUN_IN_BACKGROUND allow 2>$null
& $script:AdbPath @script:AdbPrefix shell cmd appops set $script:PackageName RUN_ANY_IN_BACKGROUND allow 2>$null
Invoke-Adb shell am start -n $script:MainComponent | Out-Null
Write-Host 'Boot receiver is enabled and the controller was started.' -ForegroundColor Green
Write-Host 'For reliable boot-to-interface behavior, also configure Device Owner. Without it, select Car Thing Controller as the default Home app on the tablet.'
