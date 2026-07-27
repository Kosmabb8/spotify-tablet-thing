param([string]$Serial = '')
. "$PSScriptRoot\common.ps1"
Initialize-Adb -Serial $Serial
Confirm-TBX505F

# Deliberately excludes System UI, Settings, Package Installer, WebView, Chrome,
# Wi-Fi components, Lenovo launcher/update/recovery services, Google Play services,
# Play Store and every package whose role is ambiguous. Chrome remains available
# for the Spotify OAuth browser flow.
$allowList = [ordered]@{
    'com.google.android.youtube'          = 'YouTube video client'
    'com.google.android.apps.youtube.music' = 'YouTube Music client'
    'com.google.android.videos'           = 'Google TV / Play Movies client'
    'com.google.android.apps.photos'      = 'Google Photos client'
    'com.google.android.apps.docs'        = 'Google Drive client'
    'com.google.android.apps.maps'        = 'Google Maps client'
    'com.google.android.apps.tachyon'     = 'Google Duo / Meet client'
    'com.google.android.gm'               = 'Gmail client'
    'com.lenovo.anyshare.gps'             = 'Lenovo-bundled SHAREit client'
    'com.lenovo.anyshare'                 = 'Lenovo-bundled SHAREit client (alternate package)'
}

$installed = (Invoke-Adb shell pm list packages) | ForEach-Object { ($_ -replace '^package:', '').Trim() }
$alreadyDisabled = (Invoke-Adb shell pm list packages -d) | ForEach-Object { ($_ -replace '^package:', '').Trim() }
$candidates = @($allowList.Keys | Where-Object { $installed -contains $_ -and $alreadyDisabled -notcontains $_ })
if ($candidates.Count -eq 0) {
    Write-Host 'No enabled package from the conservative optional-app allowlist was found. Nothing changed.'
    exit 0
}

Write-Host 'The following non-essential user-facing apps were detected:' -ForegroundColor Yellow
foreach ($package in $candidates) { Write-Host "  $package  -  $($allowList[$package])" }
Confirm-Continue 'Disable these packages for Android user 0? This is reversible.'

$disabledNow = @()
foreach ($package in $candidates) {
    Invoke-Adb shell pm disable-user --user 0 $package | Write-Host
    $disabledNow += $package
}
$stateDir = Get-StateDirectory
$statePath = Join-Path $stateDir ("debloat-{0}.json" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
@{ model = 'TB-X505F'; created = (Get-Date).ToString('o'); packages = $disabledNow } |
    ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
Write-Host "Disabled $($disabledNow.Count) optional package(s). Restore record: $statePath" -ForegroundColor Green
