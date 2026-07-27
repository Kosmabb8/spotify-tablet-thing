param(
    [string]$StateFile = '',
    [string]$Serial = ''
)
. "$PSScriptRoot\common.ps1"
Initialize-Adb -Serial $Serial
Confirm-TBX505F

if (-not $StateFile) {
    $stateDir = Get-StateDirectory
    $latest = Get-ChildItem -LiteralPath $stateDir -Filter 'debloat-*.json' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $latest) { throw 'No debloat state file exists. Pass -StateFile if it is stored elsewhere.' }
    $StateFile = $latest.FullName
}
$state = Get-Content -Raw -LiteralPath (Resolve-Path -LiteralPath $StateFile).Path | ConvertFrom-Json
if ($state.model -ne 'TB-X505F') { throw 'State file is not for TB-X505F.' }
foreach ($package in @($state.packages)) {
    Invoke-Adb shell pm enable --user 0 $package | Write-Host
}
Write-Host "Restored $(@($state.packages).Count) package(s) from $StateFile" -ForegroundColor Green
