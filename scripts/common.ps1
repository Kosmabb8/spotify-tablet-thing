Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:PackageName = 'dev.carthingspotify.controller'
$script:AdminComponent = 'dev.carthingspotify.controller/.device.CarThingAdminReceiver'
$script:MainComponent = 'dev.carthingspotify.controller/.MainActivity'
$script:AdbPath = $null
$script:AdbPrefix = @()

function Initialize-Adb {
    param([string]$Serial = '')
    $command = Get-Command adb -ErrorAction SilentlyContinue
    $path = $null
    if ($command) {
        $path = $command.Source
    } else {
        $localPaths = @(
            "$PSScriptRoot\..\.android-sdk\platform-tools\adb.exe",
            "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
        )
        foreach ($p in $localPaths) {
            if (Test-Path $p) {
                $path = $p
                break
            }
        }
    }
    if (-not $path) {
        throw 'adb.exe was not found. Install Google Platform Tools and add its directory to PATH.'
    }
    $script:AdbPath = $path
    if ($Serial) { $script:AdbPrefix = @('-s', $Serial) } else { $script:AdbPrefix = @() }

    $lines = & $script:AdbPath @script:AdbPrefix get-state 2>&1
    if ($LASTEXITCODE -ne 0 -or ($lines -join '') -notmatch 'device') {
        throw 'No authorized Android device was found. Connect one tablet, enable USB debugging, and accept its RSA prompt.'
    }
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $oldEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $script:AdbPath @script:AdbPrefix @Arguments 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "adb failed: $($Arguments -join ' ')`n$($output -join [Environment]::NewLine)"
        }
        return $output
    } finally {
        $ErrorActionPreference = $oldEap
    }
}

function Confirm-TBX505F {
    $model = ((Invoke-Adb shell getprop ro.product.model) -join '').Trim()
    $name = ((Invoke-Adb shell getprop ro.product.name) -join '').Trim()
    $device = ((Invoke-Adb shell getprop ro.product.device) -join '').Trim()
    $combined = "$model $name $device"
    if ($combined -notmatch '(?i)(^|[^A-Z0-9])TB[-_]?X505F([^A-Z0-9]|$)') {
        throw "Safety stop: connected device is '$combined', not a Lenovo TB-X505F. Nothing was changed."
    }
    Write-Host "Verified target: $model ($device)" -ForegroundColor Green
}

function Test-PackageInstalled {
    param([string]$Name = $script:PackageName)
    $result = & $script:AdbPath @script:AdbPrefix shell pm path $Name 2>&1
    return ($LASTEXITCODE -eq 0 -and ($result -join '') -match 'package:')
}

function Get-StateDirectory {
    $directory = Join-Path $PSScriptRoot 'state'
    if (-not (Test-Path -LiteralPath $directory)) {
        New-Item -ItemType Directory -Path $directory | Out-Null
    }
    return $directory
}

function Confirm-Continue {
    param([string]$Prompt)
    $answer = Read-Host "$Prompt Type YES to continue"
    if ($answer -cne 'YES') { throw 'Cancelled; nothing was changed.' }
}
