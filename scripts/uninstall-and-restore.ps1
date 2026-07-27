param([string]$Serial = '')
. "$PSScriptRoot\common.ps1"
Initialize-Adb -Serial $Serial
Confirm-TBX505F

$policy = (Invoke-Adb shell dumpsys device_policy) -join "`n"
if ($policy -match [regex]::Escape($script:PackageName)) {
    throw 'The controller is still Device Owner. On the tablet, hold the clock for 2.5 seconds, enter the PIN, choose Exit dedicated mode, then rerun this script.'
}

$stateDir = Get-StateDirectory
$latest = Get-ChildItem -LiteralPath $stateDir -Filter 'debloat-*.json' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($latest) {
    $state = Get-Content -Raw -LiteralPath $latest.FullName | ConvertFrom-Json
    foreach ($package in @($state.packages)) {
        & $script:AdbPath @script:AdbPrefix shell pm enable --user 0 $package 2>$null | Out-Null
    }
    Write-Host 'Restored packages recorded by the latest debloat run.'
}

& $script:AdbPath @script:AdbPrefix shell am force-stop $script:PackageName 2>$null
& $script:AdbPath @script:AdbPrefix shell cmd package clear-preferred-activities $script:PackageName 2>$null
if (Test-PackageInstalled) { Invoke-Adb uninstall $script:PackageName | Write-Host }
Write-Host 'Controller removed and recorded optional packages restored. Android will ask for a Home app if no default is selected.' -ForegroundColor Green
