param([string]$Serial = '')
. "$PSScriptRoot\common.ps1"
Initialize-Adb -Serial $Serial
Confirm-TBX505F
if (-not (Test-PackageInstalled)) { throw 'Install the controller APK first.' }

$owners = (Invoke-Adb shell dumpsys device_policy) -join "`n"
if ($owners -match [regex]::Escape($script:PackageName)) {
    Write-Host 'The controller is already registered in device policy.' -ForegroundColor Green
    Invoke-Adb shell am start -n $script:MainComponent | Out-Null
    exit 0
}

$accounts = (Invoke-Adb shell dumpsys account) -join "`n"
if ($accounts -match 'Account \{' -or $accounts -match 'Accounts:\s*[1-9]') {
    throw 'Device Owner cannot be added while accounts exist. Back up the tablet, factory-reset it, skip Google/Lenovo sign-in, enable USB debugging, reinstall the APK, then rerun this script.'
}

Write-Host @'
Device Owner gives this controller kiosk authority. Android normally accepts this only
on a freshly reset tablet with no accounts. Removing it later is supported from the
PIN-protected administrator menu, but enabling it again requires another factory reset.
'@ -ForegroundColor Yellow
Confirm-Continue 'Confirm that your data is backed up and this tablet contains no accounts.'

Invoke-Adb shell dpm set-device-owner $script:AdminComponent | Write-Host
Start-Sleep -Seconds 1
Invoke-Adb shell am start -n $script:MainComponent | Out-Null
$verify = (Invoke-Adb shell dumpsys device_policy) -join "`n"
if ($verify -notmatch [regex]::Escape($script:PackageName)) { throw 'Device Owner verification failed.' }
Write-Host 'Device Owner and kiosk policy configured. Reboot once to test automatic startup.' -ForegroundColor Green
