[CmdletBinding()]
param(
    [string]$Serial = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $repoRoot 'scripts\common.ps1')

Write-Host 'Spotify Car Controller environment check' -ForegroundColor Cyan

$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) {
    throw 'Java was not found. Install JDK 17, reopen PowerShell, and run this check again.'
}
$javaVersion = (& $java.Source -version 2>&1 | Select-Object -First 1) -join ''
if ($javaVersion -notmatch '(?:version\s+")?17(?:[.\s"])') {
    throw "JDK 17 is required. Detected: $javaVersion"
}
Write-Host "JDK 17 verified: $javaVersion" -ForegroundColor Green

Initialize-Adb -Serial $Serial
Confirm-TBX505F
Write-Host 'ADB authorization and exact tablet model verified.' -ForegroundColor Green

$localProperties = Join-Path $repoRoot 'local.properties'
if (Test-Path -LiteralPath $localProperties) {
    Write-Host 'local.properties is present and ignored by Git.' -ForegroundColor Green
} else {
    Write-Warning 'local.properties is absent. Let Android Studio create it, or copy local.properties.example and replace only its placeholder SDK path.'
}

Write-Host 'Environment checks passed. No tablet or repository settings were changed.' -ForegroundColor Cyan
Write-Host 'Build command: .\gradlew.bat clean check assembleDebug'
