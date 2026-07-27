param(
    [Parameter(Mandatory = $true)][string]$KeyStore,
    [Parameter(Mandatory = $true)][string]$KeyAlias
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$wrapper = Join-Path $root 'gradlew.bat'
if (-not (Test-Path -LiteralPath $wrapper)) { throw 'gradlew.bat is missing.' }
$store = (Resolve-Path -LiteralPath $KeyStore).Path
$originalStorePassword = [Environment]::GetEnvironmentVariable('CARTHING_STORE_PASSWORD', 'Process')
$originalKeyPassword = [Environment]::GetEnvironmentVariable('CARTHING_KEY_PASSWORD', 'Process')

function Read-MaskedText {
    param([string]$Prompt)
    $secure = Read-Host $Prompt -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

$storePassword = if ($originalStorePassword) { $originalStorePassword } else { Read-MaskedText 'Keystore password' }
$keyPassword = if ($originalKeyPassword) { $originalKeyPassword } else { Read-MaskedText 'Key password' }

Push-Location $root
try {
    [Environment]::SetEnvironmentVariable('CARTHING_STORE_PASSWORD', $storePassword, 'Process')
    [Environment]::SetEnvironmentVariable('CARTHING_KEY_PASSWORD', $keyPassword, 'Process')
    & $wrapper clean test assembleRelease "-PCARTHING_KEYSTORE=$store" "-PCARTHING_KEY_ALIAS=$KeyAlias"
    if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
} finally {
    [Environment]::SetEnvironmentVariable('CARTHING_STORE_PASSWORD', $originalStorePassword, 'Process')
    [Environment]::SetEnvironmentVariable('CARTHING_KEY_PASSWORD', $originalKeyPassword, 'Process')
    Pop-Location
}
Write-Host "Signed release: $root\app\build\outputs\apk\release\app-release.apk" -ForegroundColor Green
