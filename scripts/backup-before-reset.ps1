param(
    [string]$Serial = '',
    [switch]$IncludeSharedStorage
)
. "$PSScriptRoot\common.ps1"
Initialize-Adb -Serial $Serial
Confirm-TBX505F

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$destination = Join-Path $PSScriptRoot "..\backups\TB-X505F-$stamp"
New-Item -ItemType Directory -Path $destination -Force | Out-Null

((Invoke-Adb shell getprop) -join "`n") | Set-Content -LiteralPath (Join-Path $destination 'getprop.txt') -Encoding UTF8
((Invoke-Adb shell pm list packages -f -i) -join "`n") | Set-Content -LiteralPath (Join-Path $destination 'packages.txt') -Encoding UTF8
((Invoke-Adb shell pm list packages -d) -join "`n") | Set-Content -LiteralPath (Join-Path $destination 'disabled-packages.txt') -Encoding UTF8
foreach ($scope in 'system','secure','global') {
    ((Invoke-Adb shell settings list $scope) -join "`n") | Set-Content -LiteralPath (Join-Path $destination "settings-$scope.txt") -Encoding UTF8
}
((Invoke-Adb shell dumpsys wifi) -join "`n") | Set-Content -LiteralPath (Join-Path $destination 'wifi-diagnostics.txt') -Encoding UTF8

if ($IncludeSharedStorage) {
    $shared = Join-Path $destination 'shared-storage'
    New-Item -ItemType Directory -Path $shared | Out-Null
    Invoke-Adb pull /sdcard/ $shared | Write-Host
}

Write-Host "Rootless diagnostic/package backup created at: $destination" -ForegroundColor Green
Write-Warning 'Android 10 ADB cannot make a complete backup of other apps private data, passkeys, DRM licenses, or authenticator secrets. Before factory reset, separately sync/export photos and files, confirm Google backups, export authenticator accounts, and record Wi-Fi credentials.'
