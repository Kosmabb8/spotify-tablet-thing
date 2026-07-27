param(
    [string]$Apk = '',
    [string]$Serial = ''
)
. "$PSScriptRoot\common.ps1"
Initialize-Adb -Serial $Serial
Confirm-TBX505F

if (-not $Apk) {
    $release = Join-Path $PSScriptRoot '..\app\build\outputs\apk\release\app-release.apk'
    $debug = Join-Path $PSScriptRoot '..\app\build\outputs\apk\debug\app-debug.apk'
    if (Test-Path -LiteralPath $release) { $Apk = $release }
    elseif (Test-Path -LiteralPath $debug) { $Apk = $debug }
    else { throw 'No APK found. Run .\gradlew.bat assembleDebug or pass -Apk C:\path\controller.apk.' }
}
$resolved = (Resolve-Path -LiteralPath $Apk).Path
if ([IO.Path]::GetExtension($resolved) -ne '.apk') { throw 'The supplied file is not an APK.' }

Write-Host "Installing $resolved"
Invoke-Adb install -r --no-streaming $resolved | Write-Host
if (-not (Test-PackageInstalled)) { throw 'Installation returned successfully, but the package was not found.' }
Write-Host 'Controller installed. No firmware, bootloader, or system partition was modified.' -ForegroundColor Green
