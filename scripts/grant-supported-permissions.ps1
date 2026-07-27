param([string]$Serial = '')
. "$PSScriptRoot\common.ps1"
Initialize-Adb -Serial $Serial
Confirm-TBX505F
if (-not (Test-PackageInstalled)) { throw 'Install the controller APK first.' }

# INTERNET, network-state, Wi-Fi-state, wake-lock and boot-completed are normal
# manifest permissions on Android 10. They are granted by Package Manager at install.
$dump = (Invoke-Adb shell dumpsys package $script:PackageName) -join "`n"
$required = @(
    'android.permission.INTERNET',
    'android.permission.ACCESS_NETWORK_STATE',
    'android.permission.ACCESS_WIFI_STATE',
    'android.permission.WAKE_LOCK',
    'android.permission.RECEIVE_BOOT_COMPLETED'
)
foreach ($permission in $required) {
    if ($dump -match [regex]::Escape($permission)) { Write-Host "OK  $permission" -ForegroundColor Green }
    else { throw "Package validation failed: $permission is missing." }
}

# These Android 10 app-ops are best-effort and do not grant access to private data.
& $script:AdbPath @script:AdbPrefix shell cmd appops set $script:PackageName RUN_IN_BACKGROUND allow 2>$null
& $script:AdbPath @script:AdbPrefix shell cmd appops set $script:PackageName RUN_ANY_IN_BACKGROUND allow 2>$null
Write-Host 'All supported permissions are present. No dangerous permission was silently granted.' -ForegroundColor Green
